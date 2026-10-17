import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  AlertTriangle, ChevronDown, CircleStop,
  LoaderCircle, Paperclip, RefreshCw, Send, Trash2,
} from 'lucide-react';
import { jobAdapter } from '../services/capabilities';
import { useAgentStore, type AgentJobState, type ConversationSummary } from '../store/agentStore';
import { useCanvasStore } from '../store/canvasStore';
import type { JobAdapter, JobEvent, JobSnapshot, ProjectDocumentV2 } from '../types';
import { createId } from '../utils/id';
import { executeToolCall, getTool, shouldDryRun } from '../canvas/toolRegistry';
import type { AgentToolCall, AgentToolResult } from '../types';
import { materializeJobAsset } from '../canvas/generationRuntime';
import type { GenerationRecord } from '../canvas/graphContracts';
import { buildGenerationSources } from '../canvas/generationRecords';
import { recoveryActionForQueueItem, type GenerationQueueItem } from '../canvas/generationQueue';
import { listGenerationQueueItems, putGenerationQueueItem } from '../services/projectStorage';

const terminalStatuses = new Set<AgentJobState['status']>(['completed', 'failed', 'cancelled']);

/**
 * 任务快照里的终态集合。
 *
 * <p>比 {@link terminalStatuses} 多一个 `interrupted`：它同样是"不会再产生事件"的状态，
 * 刷新恢复时如果快照已经中断，就必须直接落终态而不是订阅一个永远不会再有事件的流。
 */
const terminalJobStatuses = new Set<JobSnapshot['status']>([
  'completed', 'failed', 'cancelled', 'interrupted',
]);

const messageTime = (createdAt: number) => new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
}).format(createdAt);

const jobStatusLabel: Record<AgentJobState['status'], string> = {
  submitting: '正在提交', queued: '等待执行', running: '正在执行', receiving: '正在接收', cancelling: '正在取消',
  completed: '已完成', failed: '执行失败', cancelled: '已取消',
};

export function AgentPanel({ project, adapter = jobAdapter }: { project: ProjectDocumentV2; adapter?: JobAdapter }) {
  const selectedNodeId = useCanvasStore((state) => state.selectedNodeId);
  const selectedLabel = useCanvasStore((state) => state.nodes.find((node) => node.id === state.selectedNodeId)?.data.label);
  const session = useAgentStore((state) => state.sessions[project.id]);
  const ensureSession = useAgentStore((state) => state.ensureSession);
  const setConversation = useAgentStore((state) => state.setConversation);
  const setDraft = useAgentStore((state) => state.setDraft);
  const appendMessage = useAgentStore((state) => state.appendMessage);
  const appendAgentDelta = useAgentStore((state) => state.appendAgentDelta);
  const putJob = useAgentStore((state) => state.putJob);
  const updateJob = useAgentStore((state) => state.updateJob);
  const clearSession = useAgentStore((state) => state.clearSession);
  const updateToolCall = useAgentStore((state) => state.updateToolCall);
  const conversationLists = useAgentStore((state) => state.conversationLists);
  const loadConversations = useAgentStore((state) => state.loadConversations);
  const selectConversation = useAgentStore((state) => state.selectConversation);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [convDropdownOpen, setConvDropdownOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [dropdownPos, setDropdownPos] = useState<{ top: number; left: number; width: number }>({ top: 0, left: 0, width: 0 });
  const threadEndRef = useRef<HTMLDivElement>(null);
  const subscriptionsRef = useRef(new Map<string, () => void>());
  const jobContextRef = useRef(new Map<string, {
    prompt: string;
    sourceNodeId?: string;
    sourceAssetIds: string[];
    capabilityId: string;
  }>());
  const seenEventIdsRef = useRef(new Set<string>());
  const seenAssetIdsRef = useRef(new Set<string>());
  const assetQueuesRef = useRef(new Map<string, Promise<void>>());
  const toolJobRef = useRef(new Map<string, Map<string, string>>());

  useEffect(() => {
    ensureSession(project.id);
    void loadConversations(project.id, adapter);
    // 数据同步：拉取共享会话 + 历史回显（画布后端按项目 createOrGet，保证与 MoonAgent 同一条 conversation）
    (async () => {
      if (!adapter.getConversation || !adapter.getConversationMessages) return;
      try {
        const conv = await adapter.getConversation(project.id);
        if (!conv) return;
        const history = await adapter.getConversationMessages(conv.id);
        setConversation(project.id, conv.id, history.map((m) => ({
          id: createId(), role: m.role === 'user' ? 'user' : 'agent', text: m.content, createdAt: Date.now(),
        })));
      } catch { /* 无共享会话/离线：保持本地空会话 */ }
    })();
  }, [ensureSession, project.id, adapter, setConversation]);

  useEffect(() => () => {
    subscriptionsRef.current.forEach((unsubscribe) => unsubscribe());
    subscriptionsRef.current.clear();
  }, []);

  // 点击外部关闭对话列表下拉
  useEffect(() => {
    if (!convDropdownOpen) return;
    const close = () => setConvDropdownOpen(false);
    document.addEventListener('click', close, { once: true });
    return () => document.removeEventListener('click', close);
  }, [convDropdownOpen]);

  useEffect(() => {
    if (typeof threadEndRef.current?.scrollIntoView === 'function') {
      threadEndRef.current.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }, [session?.messages.length]);

  const finishSubscription = useCallback((jobId: string) => {
    subscriptionsRef.current.get(jobId)?.();
    subscriptionsRef.current.delete(jobId);
  }, []);

  const updateJobRecord = useCallback((jobId: string, patch: Partial<GenerationRecord>) => {
    const recordId = `agent-${jobId}`;
    const current = useCanvasStore.getState().generationRecords.find((record) => record.id === recordId);
    if (current) useCanvasStore.getState().updateGenerationRecord({ ...current, ...patch });
  }, []);

  const persistJobQueue = useCallback((
    jobId: string,
    status: 'pending_submit' | 'running' | 'terminal' | 'interrupted',
    error?: string,
  ) => {
    const context = jobContextRef.current.get(jobId);
    if (!context) return;
    const now = Date.now();
    void putGenerationQueueItem({
      id: `agent-${jobId}`,
      projectId: project.id,
      canvasId: useCanvasStore.getState().canvasId ?? '',
      producerNodeId: context.sourceNodeId ?? '',
      generationRecordId: `agent-${jobId}`,
      requestSnapshot: {
        capabilityId: context.capabilityId,
        sourceAssetIds: context.sourceAssetIds,
        params: { prompt: context.prompt },
      },
      status,
      jobId,
      attempt: 1,
      error,
      createdAt: now,
      updatedAt: now,
    });
  }, [project.id]);

  const sendToolResult = useCallback(async (jobId: string, toolResult: AgentToolResult) => {
    if (!adapter.sendToolResult) throw new Error('执行服务未提供工具结果回传接口');
    await adapter.sendToolResult(jobId, toolResult);
  }, [adapter]);

  const applyToolCall = useCallback(async (jobId: string, call: AgentToolCall, messageId: string, dryRun: boolean) => {
    const tool = getTool(call.name);
    const result = await executeToolCall(project.id, call, { source: 'agent', dryRun, expectedRevision: useCanvasStore.getState().revision });
    updateToolCall(project.id, messageId, { status: result.ok && result.status === 'preview' ? 'waiting_for_approval' : result.ok ? 'completed' : 'failed', result: result.result, error: result.error?.message, transactionId: result.transactionId });
    if (!dryRun || !result.ok) {
      try { await sendToolResult(jobId, result); }
      catch (error) { appendMessage(project.id, { role: 'error', text: '工具结果回传失败', detail: error instanceof Error ? error.message : '执行服务未接受工具结果', jobId }); }
    }
    if (!tool) appendMessage(project.id, { role: 'error', text: '工具不存在', detail: call.name, jobId });
  }, [appendMessage, project.id, sendToolResult, updateToolCall]);

  const confirmToolCall = useCallback(async (jobId: string, messageId: string, call: AgentToolCall) => {
    updateToolCall(project.id, messageId, { status: 'executing' });
    const result = await executeToolCall(project.id, call, { source: 'agent', dryRun: false, expectedRevision: useCanvasStore.getState().revision });
    updateToolCall(project.id, messageId, { status: result.ok ? 'completed' : 'failed', result: result.result, error: result.error?.message, transactionId: result.transactionId });
    try { await sendToolResult(jobId, result); }
    catch (error) { appendMessage(project.id, { role: 'error', text: '工具结果回传失败', detail: error instanceof Error ? error.message : '执行服务未接受工具结果', jobId }); }
  }, [appendMessage, project.id, sendToolResult, updateToolCall]);

  const rejectToolCall = useCallback(async (jobId: string, messageId: string, call: AgentToolCall) => {
    const result: AgentToolResult = { toolCallId: call.id, name: call.name, ok: false, status: 'rejected', error: { code: 'USER_REJECTED', message: '用户取消了这项画布变更' } };
    updateToolCall(project.id, messageId, { status: 'cancelled', error: result.error?.message });
    try { await sendToolResult(jobId, result); }
    catch (error) { appendMessage(project.id, { role: 'error', text: '取消结果回传失败', detail: error instanceof Error ? error.message : '执行服务未接受工具结果', jobId }); }
  }, [appendMessage, project.id, sendToolResult, updateToolCall]);

  const materializeAsset = useCallback(async (event: Extract<JobEvent, { type: 'asset.created' }>) => {
    const dedupeKey = `${event.jobId}:${event.asset.id}`;
    if (seenAssetIdsRef.current.has(dedupeKey)) return;
    seenAssetIdsRef.current.add(dedupeKey);
    const job = jobContextRef.current.get(event.jobId);
    const sourceNode = job?.sourceNodeId ? useCanvasStore.getState().nodes.find((node) => node.id === job.sourceNodeId) : undefined;
    try {
      const recordId = `agent-${event.jobId}`;
      const existing = useCanvasStore.getState().generationRecords.find((record) => record.id === recordId);
      const record: GenerationRecord = existing ?? {
        id: recordId,
        producerNodeId: sourceNode?.id ?? '',
        actionId: 'agent.request',
        attempt: 1,
        jobId: event.jobId,
        status: 'completed',
        materializationStatus: 'pending',
        materializedAssetIds: [],
        resultNodeIds: [],
        sources: sourceNode?.data.assetId ? [{ nodeId: sourceNode.id, assetId: sourceNode.data.assetId, role: 'source', order: 0 }] : [],
        prompt: job?.prompt ?? '',
        effectivePrompt: job?.prompt ?? '',
        params: { capabilityId: job?.capabilityId ?? 'agent.request' },
        startedAt: Date.now() - 1,
      };
      if (!existing) useCanvasStore.getState().appendGenerationRecord(record);
      const settlement = await materializeJobAsset(record, {
        assetId: event.asset.id,
        name: event.asset.name,
        mimeType: event.asset.mimeType,
        downloadUrl: event.asset.downloadUrl,
      }, event.jobId);
      if (settlement.created) {
        appendMessage(project.id, { role: 'status', text: '真实结果已写入画布', detail: event.asset.name, jobId: event.jobId });
      } else if (settlement.record.materializationError) {
        throw new Error(settlement.record.materializationError);
      }
    } catch (error) {
      seenAssetIdsRef.current.delete(dedupeKey);
      appendMessage(project.id, {
        role: 'error', text: '结果素材落地失败',
        detail: error instanceof Error ? error.message : '无法下载或处理执行服务返回的素材', jobId: event.jobId,
      });
    }
  }, [appendMessage, project.id]);

  const handleJobEvent = useCallback((event: JobEvent) => {
    if (event.eventId) {
      if (seenEventIdsRef.current.has(event.eventId)) return;
      seenEventIdsRef.current.add(event.eventId);
    }
    if (event.type === 'job.queued') {
      updateJob(project.id, event.jobId, { status: 'queued' });
      updateJobRecord(event.jobId, { status: 'queued', queueStatus: 'queued' });
    } else if (event.type === 'job.started') {
      updateJob(project.id, event.jobId, { status: 'running' });
      updateJobRecord(event.jobId, { status: 'running', queueStatus: 'running' });
    }
    else if (event.type === 'job.progress') {
      const progress = Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress));
      updateJob(project.id, event.jobId, { status: 'running', progress });
      updateJobRecord(event.jobId, { status: 'running', queueStatus: 'running', progress });
    } else if (event.type === 'agent.message') {
      updateJob(project.id, event.jobId, { status: 'receiving' });
      updateJobRecord(event.jobId, { status: 'running', queueStatus: 'running' });
      if (event.delta) appendAgentDelta(project.id, event.jobId, event.message, event.messageId ?? 'default');
      else appendMessage(project.id, { role: 'agent', text: event.message, jobId: event.jobId, serverMessageId: event.messageId });
    } else if (event.type === 'agent.tool_call') {
      const messageId = `tool-${event.toolCall.id}`;
      const requiresApproval = shouldDryRun(event.toolCall.name, event.toolCall.arguments ?? {});
      appendMessage(project.id, { id: messageId, role: 'status', text: requiresApproval ? '设计助理请求确认一项画布变更' : '设计助理正在操作画布', jobId: event.jobId, toolCall: { id: event.toolCall.id, name: event.toolCall.name, arguments: event.toolCall.arguments, status: requiresApproval ? 'preview' : 'executing' } });
      if (!toolJobRef.current.has(event.jobId)) toolJobRef.current.set(event.jobId, new Map());
      toolJobRef.current.get(event.jobId)?.set(event.toolCall.id, messageId);
      void applyToolCall(event.jobId, event.toolCall, messageId, requiresApproval);
    } else if (event.type === 'asset.created') {
      updateJob(project.id, event.jobId, { status: 'receiving' });
      const previous = assetQueuesRef.current.get(event.jobId) ?? Promise.resolve();
      const queued = previous.then(() => materializeAsset(event)).finally(() => {
        if (assetQueuesRef.current.get(event.jobId) === queued) assetQueuesRef.current.delete(event.jobId);
      });
      assetQueuesRef.current.set(event.jobId, queued);
    } else if (event.type === 'job.completed') {
      updateJob(project.id, event.jobId, { status: 'completed', progress: 1 });
      const recordId = `agent-${event.jobId}`;
      const current = useCanvasStore.getState().generationRecords.find((record) => record.id === recordId);
      updateJobRecord(event.jobId, {
        status: 'completed',
        queueStatus: 'terminal',
        progress: 1,
        materializationStatus: current?.materializationStatus === 'materializing'
          ? 'materializing'
          : current?.materializedAssetIds.length ? current.materializationStatus : 'completed',
        completedAt: Date.now(),
      });
      persistJobQueue(event.jobId, 'terminal');
      finishSubscription(event.jobId);
    } else if (event.type === 'job.failed') {
      updateJob(project.id, event.jobId, { status: 'failed' });
      appendMessage(project.id, { role: 'error', text: '执行请求失败', detail: event.message, jobId: event.jobId });
      updateJobRecord(event.jobId, { status: 'failed', queueStatus: 'interrupted', error: event.message, completedAt: Date.now() });
      persistJobQueue(event.jobId, 'interrupted', event.message);
      finishSubscription(event.jobId);
    } else if (event.type === 'job.cancelled') {
      updateJob(project.id, event.jobId, { status: 'cancelled' });
      appendMessage(project.id, { role: 'status', text: '请求已取消', jobId: event.jobId });
      updateJobRecord(event.jobId, {
        status: 'cancelled',
        queueStatus: 'cancelled',
        materializationStatus: 'failed',
        materializationError: '任务已取消',
        completedAt: Date.now(),
      });
      persistJobQueue(event.jobId, 'terminal');
      finishSubscription(event.jobId);
    }
  }, [appendAgentDelta, appendMessage, applyToolCall, finishSubscription, materializeAsset, persistJobQueue, project.id, updateJob, updateJobRecord]);

  /**
   * 刷新/重开页面后重新接上仍在运行的设计助理任务。
   *
   * <p>画布服务的任务事件流本身支持续传（`GET /jobs/{jobId}/events` 认 `Last-Event-ID` /
   * `afterSeq`，见 canvas 服务 `DesignJobService#subscribeAndRun`），但刷新会丢掉本组件内存里的
   * `subscriptionsRef`：此前只回放历史文本，于是出现「服务端任务还在跑、面板却一直停在等待执行」。
   * 这里从 IndexedDB 生成队列里找出未终态的任务，先用真实快照分流，再决定"重新订阅"还是"落终态"。
   *
   * <p>与 `App.tsx` 启动时调用的 `recoverGenerationJobs()` 分工：那个负责画布生成记录与节点状态，
   * 且在没有对应 generationRecord 时会跳过重订阅；这里只负责本面板的任务卡片与消息。
   * 两条 SSE 订阅各自独立，服务端按 jobId 支持多连接回放。
   */
  const recoveredJobsProjectRef = useRef('');
  useEffect(() => {
    if (recoveredJobsProjectRef.current === project.id) return;
    recoveredJobsProjectRef.current = project.id;
    let cancelled = false;
    void (async () => {
      const canvasId = useCanvasStore.getState().canvasId ?? '';
      let items: GenerationQueueItem[] = [];
      try {
        items = await listGenerationQueueItems(project.id, canvasId);
      } catch {
        return; // 本地库不可用：保持现状，不阻断面板
      }
      for (const item of items) {
        if (cancelled) return;
        const jobId = item.jobId;
        if (!jobId || subscriptionsRef.current.has(jobId)) continue;
        // 只有"确实在服务端跑着"的任务才重订阅；pending_submit/interrupted 交给生成队列自己的恢复逻辑。
        if (recoveryActionForQueueItem(item) !== 'resume') continue;
        const prompt = typeof item.requestSnapshot?.params?.prompt === 'string'
          ? item.requestSnapshot.params.prompt
          : '';
        jobContextRef.current.set(jobId, {
          prompt,
          sourceNodeId: item.producerNodeId || undefined,
          sourceAssetIds: item.requestSnapshot?.sourceAssetIds ?? [],
          capabilityId: item.requestSnapshot?.capabilityId ?? 'agent.request',
        });
        let snapshot: JobSnapshot | undefined;
        try {
          snapshot = await adapter.getJob?.(jobId);
        } catch {
          // 快照取不到：仍然订阅，让事件流把后续状态推上来（一次查询失败不代表任务不存在）
        }
        if (cancelled || subscriptionsRef.current.has(jobId)) continue;
        if (snapshot && terminalJobStatuses.has(snapshot.status)) {
          const status: AgentJobState['status'] = snapshot.status === 'completed'
            ? 'completed'
            : snapshot.status === 'cancelled' ? 'cancelled' : 'failed';
          updateJob(project.id, jobId, { status, progress: snapshot.progress ?? 1 });
          updateJobRecord(jobId, {
            status,
            queueStatus: status === 'completed' ? 'terminal' : 'interrupted',
            progress: snapshot.progress ?? 1,
            error: snapshot.error,
            completedAt: Date.now(),
          });
          // 队列项也要落终态：否则每次挂载都会重新查一遍这个已经结束的任务。
          void putGenerationQueueItem({
            ...item,
            status: status === 'completed' ? 'terminal' : 'interrupted',
            error: snapshot.error,
            updatedAt: Date.now(),
          }).catch(() => undefined);
          continue;
        }
        putJob(project.id, {
          id: jobId,
          prompt,
          sourceNodeId: item.producerNodeId || undefined,
          capabilityId: item.requestSnapshot?.capabilityId ?? 'agent.request',
          status: 'running',
          progress: snapshot?.progress ?? 0,
          createdAt: item.createdAt,
        });
        subscriptionsRef.current.set(jobId, adapter.subscribe(jobId, handleJobEvent));
      }
    })();
    return () => { cancelled = true; };
  }, [adapter, handleJobEvent, project.id, putJob, updateJob, updateJobRecord]);

  const submitPrompt = useCallback(async (prompt: string, sourceNodeId = selectedNodeId ?? undefined) => {
    const text = prompt.trim();
    if (!text || isSubmitting) return;
    setIsSubmitting(true);
    appendMessage(project.id, { role: 'user', text });
    setDraft(project.id, '');
    try {
      const canvas = useCanvasStore.getState();
      const selected = sourceNodeId ? canvas.nodes.find((node) => node.id === sourceNodeId) : undefined;
      const currentSession = useAgentStore.getState().ensureSession(project.id);
      const capabilityId = 'agent.request';
      const { jobId } = await adapter.submit({
        clientRequestId: createId(), conversationId: currentSession.conversationId, capabilityId,
        sourceAssetIds: selected?.data.assetId ? [selected.data.assetId] : [],
        context: { projectId: project.id, nodeIds: canvas.nodes.map((node) => node.id), selectedNodeId: sourceNodeId },
        params: { prompt: text },
      });
      const job: AgentJobState = { id: jobId, prompt: text, sourceNodeId, capabilityId, status: 'queued', progress: 0, createdAt: Date.now() };
      const sourceAssetIds = selected?.data.assetId ? [selected.data.assetId] : [];
      jobContextRef.current.set(jobId, { prompt: text, sourceNodeId, sourceAssetIds, capabilityId });
      useCanvasStore.getState().appendGenerationRecord({
        id: `agent-${jobId}`,
        producerNodeId: sourceNodeId ?? '',
        actionId: 'agent.request',
        attempt: 1,
        jobId,
        status: 'running',
        progress: 0,
        queueStatus: 'running',
        materializationStatus: 'pending',
        materializedAssetIds: [],
        resultNodeIds: [],
        sources: buildGenerationSources({
          sourceAssetIds,
          sourceNodeIds: sourceNodeId ? [sourceNodeId] : [],
          nodes: canvas.nodes,
          fallbackRole: 'source',
        }),
        prompt: text,
        effectivePrompt: text,
        params: { capabilityId },
        startedAt: Date.now(),
      });
      persistJobQueue(jobId, 'running');
      putJob(project.id, job);
      subscriptionsRef.current.set(jobId, adapter.subscribe(jobId, handleJobEvent));
    } catch (error) {
      const failedId = `local-${createId()}`;
      putJob(project.id, { id: failedId, prompt: text, sourceNodeId, capabilityId: 'agent.request', status: 'failed', progress: 0, createdAt: Date.now() });
      appendMessage(project.id, {
        role: 'error', text: '执行服务未连接',
        detail: error instanceof Error ? error.message : '当前请求没有发送到远程模型。', jobId: failedId,
      });
    } finally {
      setIsSubmitting(false);
    }
  }, [adapter, appendMessage, handleJobEvent, isSubmitting, persistJobQueue, project.id, putJob, selectedNodeId, setDraft]);

  const submitRequest = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void submitPrompt(session?.draft ?? '');
  };

  const cancelJob = async (jobId: string) => {
    updateJob(project.id, jobId, { status: 'cancelling' });
    try {
      await adapter.cancel(jobId);
    } catch (error) {
      appendMessage(project.id, { role: 'error', text: '无法取消任务', detail: error instanceof Error ? error.message : '取消请求失败', jobId });
    }
  };

  const handleComposerKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  };

  const convList = conversationLists[project.id] ?? [];

  const handleSelectConversation = useCallback(async (conv: ConversationSummary) => {
    if (conv.id === session?.conversationId) return;
    if (!adapter.getConversationMessages) return;
    try {
      const history = await adapter.getConversationMessages(conv.id);
      selectConversation(project.id, conv.id, history.map((m) => ({
        id: createId(), role: m.role === 'user' ? 'user' : 'agent', text: m.content, createdAt: Date.now(),
      })));
    } catch { /* 静默 */ }
  }, [adapter, project.id, selectConversation, session?.conversationId]);

  const messages = session?.messages ?? [];
  const jobs = session ? Object.values(session.jobs) : [];
  const activeJobs = jobs.filter((job) => !terminalStatuses.has(job.status));
  const failedJobFor = (jobId?: string) => jobId ? session?.jobs[jobId] : undefined;

  return (
    <div className="connection-panel agent-panel-ready">
      <div className="agent-conv-bar">
        <button
          type="button"
          ref={triggerRef}
          className="agent-conv-trigger"
          onClick={(e) => {
            e.stopPropagation();
            if (!convDropdownOpen && triggerRef.current) {
              const r = triggerRef.current.getBoundingClientRect();
              setDropdownPos({ top: r.bottom + 2, left: r.left, width: r.width });
            }
            setConvDropdownOpen(!convDropdownOpen);
          }}
        >
          <strong>{convList.find((c) => c.id === session?.conversationId)?.title || '新会话'}</strong>
          <ChevronDown size={12} />
        </button>
        {convDropdownOpen && convList.length > 0 && (
          <div className="agent-conv-dropdown" style={{ position: 'fixed', top: dropdownPos.top, left: dropdownPos.left, minWidth: 280 }} onClick={(e) => e.stopPropagation()}>
            {convList.map((c) => (
              <button
                key={c.id}
                type="button"
                className={`agent-conv-item${c.id === session?.conversationId ? ' is-active' : ''}`}
                onClick={() => { setConvDropdownOpen(false); void handleSelectConversation(c); }}
                title={c.title || '未命名对话'}
              >
                <span className="agent-conv-title">{c.title || '未命名对话'}</span>
                <small>{c.updatedAt ? new Date(c.updatedAt).toLocaleDateString('zh-CN') : ''}</small>
              </button>
            ))}
          </div>
        )}
      </div>
      <section className="agent-thread" aria-label="设计助理消息">
        <header>
          <div><strong>{activeJobs.length ? `${activeJobs.length} 个任务运行中` : messages.length ? `${messages.length} 条消息` : ''}</strong></div>
          {messages.length > 0 && <button type="button" title="清空本地会话" aria-label="清空本地会话" disabled={activeJobs.length > 0} onClick={() => clearSession(project.id)}><Trash2 size={14} /></button>}
        </header>
        <div className="agent-thread-content">
          {messages.length === 0 ? null : messages.map((message) => {
            const failedJob = message.role === 'error' ? failedJobFor(message.jobId) : undefined;
            return <article key={message.id} className={`agent-message agent-message--${message.role}`} role={message.role === 'error' ? 'alert' : undefined}>
              {message.role === 'error' && <span className="agent-message-icon"><AlertTriangle size={15} /></span>}
              <div><small>{message.role === 'user' ? 'YOU' : message.role === 'agent' ? 'ASSISTANT' : message.role === 'error' ? 'ERROR' : 'SYSTEM'} · {messageTime(message.createdAt)}</small><strong>{message.text}</strong>{message.detail && <p>{message.detail}</p>}{message.toolCall && <div className="agent-tool-call" data-status={message.toolCall.status}><code>{message.toolCall.name}</code><span>{message.toolCall.status === 'waiting_for_approval' ? '等待确认' : message.toolCall.status === 'executing' ? '执行中' : message.toolCall.status === 'completed' ? '已完成' : message.toolCall.status === 'cancelled' ? '已取消' : message.toolCall.status === 'failed' ? '失败' : '预览'}</span>{message.toolCall.error && <p>{message.toolCall.error}</p>}{message.toolCall.status === 'waiting_for_approval' && <div><button type="button" onClick={() => void confirmToolCall(message.jobId ?? '', message.id, { id: message.toolCall!.id, name: message.toolCall!.name, arguments: message.toolCall!.arguments })}>确认执行</button><button type="button" onClick={() => void rejectToolCall(message.jobId ?? '', message.id, { id: message.toolCall!.id, name: message.toolCall!.name, arguments: message.toolCall!.arguments })}>取消</button></div>}</div>}{failedJob?.status === 'failed' && <button className="agent-message-retry" type="button" onClick={() => void submitPrompt(failedJob.prompt, failedJob.sourceNodeId)}><RefreshCw size={12} /> 重试</button>}</div>
            </article>;
          })}
          <div ref={threadEndRef} />
        </div>
      </section>

      {activeJobs.length > 0 && <div className="agent-active-jobs" aria-label="运行中的任务">{activeJobs.map((job) => <article key={job.id}><div><LoaderCircle className="is-spinning" size={14} /><span><strong>{jobStatusLabel[job.status]}</strong><small>{job.prompt}</small></span><b>{Math.round(job.progress * 100)}%</b><button type="button" title="取消任务" aria-label={`取消任务 ${job.prompt}`} onClick={() => void cancelJob(job.id)}><CircleStop size={15} /></button></div><i><span style={{ width: `${Math.max(3, job.progress * 100)}%` }} /></i></article>)}</div>}

      <form className="agent-composer" onSubmit={submitRequest}>
        <label htmlFor="agent-request">给设计助理发送请求</label>
        <textarea id="agent-request" value={session?.draft ?? ''} onChange={(event) => setDraft(project.id, event.target.value)} onKeyDown={handleComposerKeyDown} placeholder="描述希望分析、修改或生成的内容…" rows={3} />
        <div className="agent-composer-footer">
          <span className="agent-context-chip"><Paperclip size={12} />{selectedLabel ? `已附带：${selectedLabel}` : '画布上下文'}</span>
          <span className="agent-enter-hint">Shift + Enter 换行</span>
          <button className="agent-send" type="submit" disabled={!session?.draft.trim() || isSubmitting} aria-label="发送请求">{isSubmitting ? <LoaderCircle className="is-spinning" size={16} /> : <Send size={16} />}</button>
        </div>
      </form>
    </div>
  );
}
