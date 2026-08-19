import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent, type VideoTaskStatus,
} from '../api/agent';
import { assetUrl } from '../config';

/**
 * 停止生成(AbortController):
 * - activeAbort:当前活跃流的控制器,模块级(不进 zustand state,避免无关重渲染)
 * - userStopped:用户主动停止标记,用于区分 AbortError 与真实错误——主动停止不报「（回复失败）」,
 *   已收内容保留;下次 sendMessage/submitHumanInput 开头重置
 */
let activeAbort: AbortController | null = null;
let userStopped = false;

export interface HumanInputInfo {
  formToken: string;
  taskId: string;
  formContent: string;
  actions: { id: string; title: string }[];
  expirationTime: number;
  models?: { value: string; label: string; params?: string | null }[];
  imageModels?: { value: string; label: string; params?: string | null }[];
  videoModels?: { value: string; label: string; params?: string | null }[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  /** 资产选择卡片:可勾选的资产清单(后端下发时渲染勾选) */
  assets?: { id: string; name: string; type: string; image?: string }[];
}

/** 生成完成后的看图确认卡片(后端 confirm_result 事件) */
export interface ConfirmResultInfo {
  kind: 'script' | 'image' | 'video';
  url: string;
  assetId?: string;
  /** 多图结果(n>1 时后端下发全部 URL;未下发则回退单 url) */
  urls?: string[];
  assetIds?: string[];
  sceneCount?: number;
  actions: { id: string; title: string }[];
}

/** 图生视频方案确认卡片(后端 video_plan 事件):视觉模型看图设计的方案,确认后生成 */
export interface VideoPlanInfo {
  planToken: string;
  message: string;
  duration: number;
  picUrl: string;
  actions: { id: string; title: string }[];
  models?: { value: string; label: string; params?: string | null }[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
}

interface AgentState {
  // 项目
  projectId: string | null;
  setProjectId: (id: string) => void;
  /** 切换项目:重置会话/消息/卡片状态并加载新项目的会话列表 */
  switchProject: (id: string) => Promise<void>;

  // 会话
  conversations: AgentConversation[];
  activeConversationId: string | null;
  loadingConversations: boolean;
  loadConversations: () => Promise<void>;
  createConversation: () => Promise<void>;
  renameConversation: (id: string, title: string) => Promise<void>;
  setConversationStatus: (id: string, status: 'active' | 'archived') => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  clearMessages: () => Promise<void>;
  selectConversation: (id: string) => Promise<void>;

  // 消息
  messages: AgentMessage[];
  streaming: boolean;
  waitingHumanInput: HumanInputInfo | null;
  streamError: string | null;
  workflowHint: string;
  setWorkflowHint: (v: string) => void;
  pendingAssistantId: string | null;
  /** 停止当前正在生成的流:中断 SSE,保留已收内容,不报错误 */
  stopGenerate: () => void;

  // 资产
  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  // 发送
  sendMessage: (content: string, opts?: { picUrl?: string }) => Promise<void>;
  submitHumanInput: (actionId: string, customText?: string, params?: Record<string, string>, assetIds?: string[]) => Promise<void>;
  resetChatState: () => void;

  // 页面级状态
  activeModal: 'storyboard' | 'assets' | 'project' | null;
  setActiveModal: (modal: AgentState['activeModal']) => void;
  historyExpanded: boolean;
  setHistoryExpanded: (v: boolean) => void;

  // 输入参考图(图改图/图生视频)
  refImageUrl: string | null;
  setRefImageUrl: (v: string | null) => void;
  uploadRefImage: (file: File) => Promise<void>;

  // 图生视频方案确认卡片(video_plan 事件)
  waitingVideoPlan: VideoPlanInfo | null;
  submitVideoPlan: (actionId: string, params?: Record<string, string>) => Promise<void>;

  // 看图确认卡片(confirm_result 事件):继续完善 / 满意完成
  confirmResult: ConfirmResultInfo | null;
  refineAsset: () => void;
  dismissConfirm: () => Promise<void>;
  /** 继续完善参考图:点「继续完善」后不自动发送,暂存 PicUrl 等待用户输入完善需求(随下一条消息发送并消费) */
  pendingPicUrl: string | null;
  cancelRefine: () => void;
}

const PROJECT_ID_KEY = 'moon.projectId';

/** 记住上次选择的项目(登录后自动恢复,历史对话直接可见) */
function loadProjectId(): string | null {
  try { return localStorage.getItem(PROJECT_ID_KEY); } catch { return null; }
}
function saveProjectId(id: string | null) {
  try {
    if (id) localStorage.setItem(PROJECT_ID_KEY, id);
    else localStorage.removeItem(PROJECT_ID_KEY);
  } catch { /* ignore */ }
}

export const useAgentStore = create<AgentState>((set, get) => ({
  projectId: loadProjectId(),
  setProjectId: (id) => { set({ projectId: id }); saveProjectId(id); },
  switchProject: async (id) => {
    set({ projectId: id, activeConversationId: null, conversations: [] });
    saveProjectId(id);
    get().resetChatState();
    await get().loadConversations();
  },

  conversations: [],
  activeConversationId: null,
  loadingConversations: false,
  loadConversations: async () => {
    const projectId = get().projectId;
    if (!projectId) return;
    set({ loadingConversations: true });
    try {
      const res = await agentApi.listConversations(projectId);
      const list = res.data.data ?? [];
      set({ conversations: list, loadingConversations: false });
      if (list.length > 0 && !get().activeConversationId) {
        await get().selectConversation(list[0].id);
      }
    } catch {
      set({ loadingConversations: false });
    }
  },

  createConversation: async () => {
    const projectId = get().projectId;
    if (!projectId) return;
    const res = await agentApi.createConversation(projectId, '新对话');
    const conv = res.data.data;
    set((s) => ({ conversations: [conv, ...s.conversations] }));
    await get().selectConversation(conv.id);
  },

  renameConversation: async (id, title) => {
    const res = await agentApi.updateConversation(id, { title });
    const updated = res.data.data;
    set((s) => ({
      conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
    }));
  },

  setConversationStatus: async (id, status) => {
    const res = await agentApi.updateConversation(id, { status });
    const updated = res.data.data;
    set((s) => ({
      conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
    }));
  },

  deleteConversation: async (id) => {
    await agentApi.deleteConversation(id);
    set((s) => ({
      conversations: s.conversations.filter((c) => c.id !== id),
      activeConversationId: s.activeConversationId === id ? null : s.activeConversationId,
      messages: s.activeConversationId === id ? [] : s.messages,
      waitingHumanInput: s.activeConversationId === id ? null : s.waitingHumanInput,
      waitingVideoPlan: s.activeConversationId === id ? null : s.waitingVideoPlan,
      confirmResult: s.activeConversationId === id ? null : s.confirmResult,
      pendingPicUrl: s.activeConversationId === id ? null : s.pendingPicUrl,
    }));
  },

  clearMessages: async () => {
    const id = get().activeConversationId;
    if (!id || get().streaming || get().waitingHumanInput) return;
    await agentApi.clearMessages(id);
    set({ messages: [], waitingHumanInput: null, waitingVideoPlan: null, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null });
  },

  selectConversation: async (id) => {
    if (get().waitingHumanInput) return;
    set({
      activeConversationId: id, messages: [], waitingHumanInput: null,
      waitingVideoPlan: null, streamError: null, pendingAssistantId: null,
      confirmResult: null, pendingPicUrl: null, refImageUrl: null,
    });
    const res = await agentApi.listMessages(id);
    set({ messages: res.data.data ?? [] });
    void get().loadAssets(1);
  },

  messages: [],
  streaming: false,
  waitingHumanInput: null,
  waitingVideoPlan: null,
  confirmResult: null,
  pendingPicUrl: null,
  streamError: null,
  workflowHint: '',
  setWorkflowHint: (v) => set({ workflowHint: v }),

  stopGenerate: () => {
    userStopped = true;
    activeAbort?.abort();
    set({ streaming: false, workflowHint: '' });
  },
  pendingAssistantId: null,

  assets: null,
  loadAssets: async (page = 1) => {
    const convId = get().activeConversationId;
    if (!convId) return;
    const res = await agentApi.listAssets(convId, page, 20);
    if (get().activeConversationId !== convId) return;
    set({ assets: res.data.data });
  },
  deleteAsset: async (assetId) => {
    await agentApi.deleteAsset(assetId);
    const assets = get().assets;
    const page = assets?.page ?? 1;
    if ((assets?.records.length ?? 0) === 1 && page > 1) {
      void get().loadAssets(page - 1);
    } else {
      void get().loadAssets(page);
    }
  },

  refImageUrl: null,
  setRefImageUrl: (v) => set({ refImageUrl: v }),
  uploadRefImage: async (file) => {
    const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined);
    const url = res.data.data.url;
    set({ refImageUrl: url });
    // 参考图消息显示到对话窗口(与后端 upload 端点落库的 user 消息对齐,刷新后仍在);
    // 未选会话时后端不落库,前端也不 push(messages 为空)
    const cid = get().activeConversationId;
    if (cid) {
      const refMsg: AgentMessage = {
        id: `tmp-ref-${Date.now()}`,
        conversationId: cid,
        role: 'user',
        content: url,
        difyMessageId: null,
        createdAt: new Date().toISOString(),
      };
      set((s) => ({ messages: [...s.messages, refMsg] }));
    }
  },

  sendMessage: async (content, opts) => {
    if (get().streaming || !content.trim()) return;
    userStopped = false;
    let id = get().activeConversationId;
    if (!id) {
      const projectId = get().projectId;
      if (!projectId) { set({ streamError: '请先选择项目' }); return; }
      await get().createConversation();
      id = get().activeConversationId;
    }
    if (!id) return;
    // 继续完善参考图:点「继续完善」后暂存的 PicUrl,随本条用户消息发送并消费(只带一次,
    // 避免后续普通消息也误带图片导致后端误判 pic-refine 意图)
    const pendingPic = get().pendingPicUrl;
    if (pendingPic) set({ pendingPicUrl: null });

    const optimisticUser: AgentMessage = {
      id: `tmp-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content,
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: [...s.messages, optimisticUser],
      streaming: true,
      streamError: null,
      waitingHumanInput: null,
      waitingVideoPlan: null,
    }));

    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, optimisticAssistant], pendingAssistantId: assistantId }));

    const updateAssistant = (delta: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
      }));

    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
      }));

    const snapshotId = id;
    let receivedMessageEnd = false;
    let receivedHumanInput = false;

    // 停止生成:创建本次流的 AbortController,停止按钮通过 stopGenerate abort 中断 SSE
    const controller = new AbortController();
    activeAbort = controller;

    try {
      await streamChat(id, content, opts?.picUrl ?? pendingPic ?? get().refImageUrl ?? undefined, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            updateAssistant(e.content ?? '');
            break;
          case 'workflow':
            set({ workflowHint: e.title ?? '' });
            break;
          case 'human_input':
            if (get().activeConversationId !== snapshotId) break;
            receivedHumanInput = true;
            set({
              workflowHint: '',
              waitingHumanInput: {
                formToken: e.formToken ?? '',
                taskId: e.taskId ?? '',
                formContent: e.formContent ?? '',
                actions: e.actions ?? [],
                expirationTime: e.expirationTime ?? 0,
                models: e.models,
                imageModels: e.imageModels,
                videoModels: e.videoModels,
                recommended: e.recommended,
                reasons: e.reasons,
                assets: e.assets,
              },
            });
            break;
          case 'message_end':
            receivedMessageEnd = true;
            if (get().activeConversationId !== snapshotId) break;
            if (typeof e.content === 'string' && e.content) updateAssistantFull(e.content);
            if (typeof e.title === 'string' && e.title) {
              set((s) => ({
                conversations: s.conversations.map((c) =>
                  c.id === snapshotId && c.title !== e.title ? { ...c, title: e.title as string } : c),
              }));
            }
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({ confirmResult: e as unknown as ConfirmResultInfo });
            break;
          case 'video_plan':
            if (get().activeConversationId !== snapshotId) break;
            set({
              waitingVideoPlan: {
                // 后端 runHITLStage 统一下发 formToken(曾误读 planToken 恒空 → 提交 40401 checkpoint 不存在)
                planToken: e.formToken ?? e.planToken ?? '',
                message: e.message ?? '',
                duration: e.duration ?? 8,
                picUrl: e.picUrl ?? '',
                actions: e.actions ?? [],
                models: e.models,
                recommended: e.recommended,
                reasons: e.reasons,
              },
            });
            break;
          case 'error':
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      });
    } catch (err) {
      // 用户主动停止:AbortError 静默,不显示错误;其余错误照常上报
      const aborted = err instanceof DOMException && err.name === 'AbortError';
      if (!aborted && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      // 仅当仍是最新流时复位 streaming,避免旧流 finally 误清新流的进行中状态
      const isCurrent = activeAbort === controller;
      if (isCurrent) activeAbort = null;
      const stillSame = get().activeConversationId === snapshotId;
      const failedText = get().streamError ? '（回复失败）' : userStopped ? '' : '（未收到回复）';
      set((s) => ({
        streaming: isCurrent ? false : s.streaming,
        pendingAssistantId: null,
        messages: stillSame
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
      }));
    }
    // 清空参考图(仅当仍在原会话时清空,避免切换会话后误清新会话的参考图)
    if (get().activeConversationId === snapshotId) set({ refImageUrl: null });
  },

  submitHumanInput: async (actionId, customText = '', params, assetIds) => {
    const info = get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    userStopped = false;
    set({ streaming: true, waitingHumanInput: null, streamError: null });

    // HITL 续流复用同一 assistant 占位(与后端消息合并对应):
    // sendMessage 流结束(receivedHumanInput=true)时 pendingAssistantId 已被 finally 清空,
    // 故需兜底复用「最后一条空内容 assistant 占位」,避免新建重复气泡导致方案填错气泡
    let assistantId = get().pendingAssistantId ?? '';
    if (!assistantId || !get().messages.some((m) => m.id === assistantId)) {
      const lastEmptyAssistant = [...get().messages]
        .reverse()
        .find((m) => m.role === 'assistant' && !m.content);
      assistantId = lastEmptyAssistant?.id ?? `tmp-assistant-${Date.now()}`;
      if (!lastEmptyAssistant) {
        const optimisticAssistant: AgentMessage = {
          id: assistantId,
          conversationId: id,
          role: 'assistant',
          content: '',
          difyMessageId: null,
          createdAt: new Date().toISOString(),
        };
        set((s) => ({ messages: [...s.messages, optimisticAssistant], pendingAssistantId: assistantId }));
      }
    }

    const updateAssistant = (delta: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
      }));

    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
      }));

    // 本地落位:方案文本 + 用户确认(与后端落库对齐,点击按钮后消息不消失)
    const act = info.actions.find((a) => a.id === actionId);
    const confirmTitle = act?.title ?? actionId;
    if (info.formContent) updateAssistantFull(info.formContent);
    const confirmMsg: AgentMessage = {
      id: `tmp-user-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content: actionId === 'custom' ? customText : `确认：${confirmTitle}`,
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, confirmMsg] }));

    const snapshotId = id;
    let receivedMessageEnd = false;
    let receivedHumanInput = false;

    // 停止生成:HITL 续流同样可中断(controller.signal 随 submitForm 透传)
    const controller = new AbortController();
    activeAbort = controller;

    try {
      await submitForm(id, info.formToken, info.taskId, actionId, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            updateAssistant(e.content ?? '');
            break;
          case 'workflow':
            set({ workflowHint: e.title ?? '' });
            break;
          case 'human_input':
            if (get().activeConversationId !== snapshotId) break;
            receivedHumanInput = true;
            set({
              workflowHint: '',
              waitingHumanInput: {
                formToken: e.formToken ?? '',
                taskId: e.taskId ?? '',
                formContent: e.formContent ?? '',
                actions: e.actions ?? [],
                expirationTime: e.expirationTime ?? 0,
                models: e.models,
                imageModels: e.imageModels,
                videoModels: e.videoModels,
                recommended: e.recommended,
                reasons: e.reasons,
                assets: e.assets,
              },
            });
            break;
          case 'message_end':
            receivedMessageEnd = true;
            if (get().activeConversationId !== snapshotId) break;
            if (typeof e.content === 'string' && e.content) updateAssistantFull(e.content);
            if (typeof e.title === 'string' && e.title) {
              set((s) => ({
                conversations: s.conversations.map((c) =>
                  c.id === snapshotId && c.title !== e.title ? { ...c, title: e.title as string } : c),
              }));
            }
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({ confirmResult: e as unknown as ConfirmResultInfo });
            break;
          case 'video_plan':
            if (get().activeConversationId !== snapshotId) break;
            set({
              waitingVideoPlan: {
                planToken: e.formToken ?? e.planToken ?? '',
                message: e.message ?? '',
                duration: e.duration ?? 8,
                picUrl: e.picUrl ?? '',
                actions: e.actions ?? [],
                models: e.models,
                recommended: e.recommended,
                reasons: e.reasons,
              },
            });
            break;
          case 'error':
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      }, customText, params, assetIds, controller.signal);
    } catch (err) {
      // 用户主动停止:AbortError 静默,不显示错误;其余错误照常上报
      const aborted = err instanceof DOMException && err.name === 'AbortError';
      if (!aborted && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      // 仅当仍是最新流时复位 streaming,避免旧流 finally 误清新流的进行中状态
      const isCurrent = activeAbort === controller;
      if (isCurrent) activeAbort = null;
      const stillSame = get().activeConversationId === snapshotId;
      const failedText = get().streamError ? '（回复失败）' : userStopped ? '' : '（未收到回复）';
      set((s) => ({
        streaming: isCurrent ? false : s.streaming,
        pendingAssistantId: null,
        messages: stillSame
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
      }));
    }
  },

  /**
   * 图生视频方案确认卡片(video_plan 事件):
   * - 开始生成视频:统一走 /form/submit(action=generate_video → 后端 VideoIntentHandler.resume)
   *   → 立即收到 task_accepted(视频任务已受理)→ 前端 5s 轮询 GET /tasks/{taskId},
   *   completed 后渲染视频结果 + confirm_result 卡片;failed 显示错误;
   * - 继续完善:本地关闭卡片,参考图转 pendingPicUrl 保留(与图片完善 refine 同语义),
   *   用户输入完善需求后随下一条消息发送(重新分流 → 再设计)。
   */
  submitVideoPlan: async (actionId, params) => {
    const info = get().waitingVideoPlan;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    // 继续完善:本地关闭卡片 + 保留参考图(assetUrl 转绝对 URL)
    if (actionId === 'refine') {
      set({ waitingVideoPlan: null, pendingPicUrl: assetUrl(info.picUrl) });
      return;
    }
    set({ streaming: true, waitingVideoPlan: null, streamError: null });

    const confirmMsg: AgentMessage = {
      id: `tmp-user-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content: '确认：开始生成视频',
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, confirmMsg] }));

    // 新建 assistant 占位气泡:视频异步生成期间为空,完成后由轮询结果填充
    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, optimisticAssistant], pendingAssistantId: assistantId }));

    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
      }));

    const snapshotId = id;
    let receivedResult = false;
    // 视频异步任务:task_accepted 后启动 5s 轮询(跨会话守卫;completed/failed 停止)
    let acceptedTaskId: string | null = null;
    let pollTick = 0;
    const startTaskPolling = (taskId: string) => {
      const timer = setInterval(async () => {
        if (get().activeConversationId !== snapshotId || acceptedTaskId === null) {
          clearInterval(timer);
          return;
        }
        pollTick += 1;
        try {
          const res = await agentApi.getVideoTaskStatus(taskId);
          const t: VideoTaskStatus = res.data.data;
          if (t.status === 'completed') {
            clearInterval(timer);
            receivedResult = true;
            updateAssistantFull(t.url || '');
            set({
              streaming: false,
              workflowHint: '',
              pendingAssistantId: null,
              confirmResult: {
                kind: 'video',
                url: t.url || '',
                assetId: t.assetId,
                sceneCount: 0,
                actions: [
                  { id: 'refine', title: '继续完善' },
                  { id: 'done', title: '满意完成' },
                ],
              },
            });
            void get().loadAssets();
          } else if (t.status === 'failed') {
            clearInterval(timer);
            if (get().activeConversationId === snapshotId) {
              set({ streamError: t.error || '视频生成失败，请重试', workflowHint: '' });
            }
          } else {
            // queued/running:阶段提示实时更新(防「卡住」错觉);继续轮询
            const waiting = Math.round(pollTick * 5);
            const stage = t.status === 'queued'
              ? `视频排队中…（已等待约 ${waiting} 秒）`
              : `视频生成中…（已等待约 ${waiting} 秒，通常 1~3 分钟）`;
            set({ workflowHint: stage });
          }
        } catch {
          // 单次轮询失败不终止:下一 tick 重试(后端瞬时故障容错)
        }
      }, 5000);
    };

    try {
      // 统一 HITL 提交路径:video_plan 的 planToken 即 checkpoint formToken
      await submitForm(id, info.planToken, '', 'generate_video', (e: SseEvent) => {
        switch (e.type) {
          case 'task_accepted':
            if (get().activeConversationId !== snapshotId) break;
            acceptedTaskId = e.taskId ?? '';
            set({ workflowHint: e.message ?? '视频任务已受理，正在排队生成…' });
            if (acceptedTaskId) startTaskPolling(acceptedTaskId);
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            receivedResult = true;
            set({ confirmResult: e as unknown as ConfirmResultInfo });
            break;
          case 'error':
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '生成出错，请重试' });
            }
            break;
        }
      }, '', params);
    } catch (err) {
      if (get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '生成出错，请重试' });
      }
    } finally {
      const stillSame = get().activeConversationId === snapshotId;
      // 异步视频任务已受理:保持 streaming(轮询 completed 时关闭),占位气泡不补写
      if (acceptedTaskId) {
        if (!get().streaming) set({ streaming: true });
        return;
      }
      const failedText = get().streamError ? '（生成失败）' : '（未收到回复）';
      set((s) => ({
        streaming: false,
        pendingAssistantId: null,
        messages: stillSame
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedResult
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
      }));
    }
  },

  /** 看图确认卡片:继续完善 → 暂存当前图 PicUrl,不自动发送;用户输入完善需求后随下一条消息发送 */
  refineAsset: () => {
    const { confirmResult } = get();
    if (!confirmResult || confirmResult.kind === 'script') return;
    // confirmResult.url 是后端相对路径(/api/files/images/x.png),assetUrl() 拼接 BACKEND 前缀转绝对 URL
    const picUrl = assetUrl(confirmResult.url);
    set({ confirmResult: null, pendingPicUrl: picUrl });
  },
  /** 取消继续完善:清空暂存参考图(输入框提示条上的 ✕ 触发) */
  cancelRefine: () => set({ pendingPicUrl: null }),
  /**
   * 看图确认卡片:满意完成 → 通知后端清空 storage_pic_talk 变量(下次图片需求走全新设计)。
   * 成功收起卡片 + 刷新资产;失败保留卡片并提示(可重试)。纯前端收起会让后端
   * 变量残留 → 下次图片需求误走完善路径。
   */
  dismissConfirm: async () => {
    const id = get().activeConversationId;
    if (!id) {
      set({ confirmResult: null });
      return;
    }
    try {
      await agentApi.confirmDone(id);
      set({ confirmResult: null });
      void get().loadAssets();
    } catch {
      alert('操作失败，请重试');
    }
  },

  resetChatState: () =>
    set({
      messages: [], waitingHumanInput: null, waitingVideoPlan: null, streamError: null,
      workflowHint: '', assets: null, refImageUrl: null, pendingAssistantId: null,
      confirmResult: null, pendingPicUrl: null,
    }),

  activeModal: null,
  setActiveModal: (modal) => set({ activeModal: modal }),
  historyExpanded: true,
  setHistoryExpanded: (v) => set({ historyExpanded: v }),
}));
