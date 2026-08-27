import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  AlertTriangle, ChevronDown, CircleStop,
  LoaderCircle, Paperclip, RefreshCw, Send, Trash2,
} from 'lucide-react';
import { jobAdapter } from '../services/capabilities';
import { saveAsset } from '../services/projectStorage';
import { useAgentStore, type AgentJobState, type ConversationSummary } from '../store/agentStore';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { JobAdapter, JobEvent, PersistedAsset, ProjectDocumentV2 } from '../types';
import { createId } from '../utils/id';
import { createImageAsset, createVideoAsset, getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import { executeToolCall, getTool, shouldDryRun } from '../canvas/toolRegistry';
import type { AgentToolCall, AgentToolResult } from '../types';

const terminalStatuses = new Set<AgentJobState['status']>(['completed', 'failed', 'cancelled']);

const messageTime = (createdAt: number) => new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
}).format(createdAt);

const jobStatusLabel: Record<AgentJobState['status'], string> = {
  submitting: '正在提交', queued: '等待执行', running: '正在执行', receiving: '正在接收', cancelling: '正在取消',
  completed: '已完成', failed: '执行失败', cancelled: '已取消',
};

const outputPosition = () => {
  const nodes = useCanvasStore.getState().nodes;
  if (!nodes.length) return { x: 300, y: 200 };
  return {
    x: Math.max(...nodes.map((node) => node.position.x + (node.width ?? node.measured?.width ?? 250))) + 150,
    y: Math.min(...nodes.map((node) => node.position.y)),
  };
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
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [convDropdownOpen, setConvDropdownOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [dropdownPos, setDropdownPos] = useState<{ top: number; left: number; width: number }>({ top: 0, left: 0, width: 0 });
  const threadEndRef = useRef<HTMLDivElement>(null);
  const subscriptionsRef = useRef(new Map<string, () => void>());
  const jobContextRef = useRef(new Map<string, { prompt: string; sourceNodeId?: string; capabilityId: string }>());
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
    const sourceAssetId = sourceNode?.data.assetId;
    try {
      const file = await adapter.fetchAsset(event.asset);
      const operation = { type: 'remote' as const, params: { jobId: event.jobId, remoteAssetId: event.asset.id, capabilityId: job?.capabilityId ?? 'agent.request' } };
      const asset: PersistedAsset = file.type.startsWith('video/')
        ? await createVideoAsset(file)
        : await createImageAsset(file, undefined, { derivedFrom: sourceAssetId, operation });
      asset.operation = operation;
      asset.derivedFrom = sourceAssetId;
      const { projectId, canvasId } = useCanvasStore.getState();
      if (asset.projectId === undefined) asset.projectId = projectId ?? undefined;
      if (asset.canvasId === undefined) asset.canvasId = canvasId ?? undefined;
      await saveAsset(asset);
      upsertAsset(asset);
      const label = file.name.replace(/\.[^.]+$/, '') || 'Agent 结果';
      const size = asset.kind === 'video' ? getVideoNodeSize(asset.width, asset.height) : getMediaNodeSize(asset.width, asset.height);
      if (sourceNode) {
        useCanvasStore.getState().createDerivedNodes(sourceNode.id, [{ data: { kind: asset.kind, assetId: asset.id, label, operation, mediaLoadState: 'ready', status: 'success' }, size }]);
      } else {
        const item = { assetId: asset.id, label, position: outputPosition(), size, operation };
        if (asset.kind === 'video') useCanvasStore.getState().addImportedVideos([item]);
        else useCanvasStore.getState().addImportedImages([item]);
      }
      appendMessage(project.id, { role: 'status', text: '真实结果已写入画布', detail: file.name, jobId: event.jobId });
    } catch (error) {
      seenAssetIdsRef.current.delete(dedupeKey);
      appendMessage(project.id, {
        role: 'error', text: '结果素材落地失败',
        detail: error instanceof Error ? error.message : '无法下载或处理执行服务返回的素材', jobId: event.jobId,
      });
    }
  }, [adapter, appendMessage, project.id, upsertAsset]);

  const handleJobEvent = useCallback((event: JobEvent) => {
    if (event.eventId) {
      if (seenEventIdsRef.current.has(event.eventId)) return;
      seenEventIdsRef.current.add(event.eventId);
    }
    if (event.type === 'job.queued') updateJob(project.id, event.jobId, { status: 'queued' });
    else if (event.type === 'job.started') updateJob(project.id, event.jobId, { status: 'running' });
    else if (event.type === 'job.progress') {
      const progress = Math.max(0, Math.min(1, event.progress > 1 ? event.progress / 100 : event.progress));
      updateJob(project.id, event.jobId, { status: 'running', progress });
    } else if (event.type === 'agent.message') {
      updateJob(project.id, event.jobId, { status: 'receiving' });
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
      finishSubscription(event.jobId);
    } else if (event.type === 'job.failed') {
      updateJob(project.id, event.jobId, { status: 'failed' });
      appendMessage(project.id, { role: 'error', text: '执行请求失败', detail: event.message, jobId: event.jobId });
      finishSubscription(event.jobId);
    } else if (event.type === 'job.cancelled') {
      updateJob(project.id, event.jobId, { status: 'cancelled' });
      appendMessage(project.id, { role: 'status', text: '请求已取消', jobId: event.jobId });
      finishSubscription(event.jobId);
    }
  }, [appendAgentDelta, appendMessage, applyToolCall, finishSubscription, materializeAsset, project.id, updateJob]);

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
      jobContextRef.current.set(jobId, { prompt: text, sourceNodeId, capabilityId });
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
  }, [adapter, appendMessage, handleJobEvent, isSubmitting, project.id, putJob, selectedNodeId, setDraft]);

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
