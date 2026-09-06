import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent, type VideoTaskStatus, type AssetOption,
  type StoryboardVideoQueueItem, type StoryboardVideoEnqueueItem,
  type StoryboardImageQueueItem, type StoryboardImageEnqueueItem,
  type AgentMessageMetadata, type AgentReasoning, type AgentTraceEvent,
} from '../api/agent';
import { useProjectStore } from './projectStore';
import { assetUrl, VIDEO_POLL_INTERVAL } from '../config';
import type { GatewayModelOption } from '../api/ai';

export interface HumanInputInfo {
  formToken: string;
  taskId: string;
  formContent: string;
  actions: { id: string; title: string }[];
  expirationTime: number;
  /** HITL 卡片对应的后端消息 id（human_input 事件 messageId；提交后写入 metadata.hitl） */
  messageId?: string;
  /** checkpoint 分类/事件名，用于刷新后区分 human_input 与 video_plan */
  kind?: 'human-input' | 'video-plan';
  hitlType?: string;
  /** video_plan checkpoint 恢复字段 */
  message?: string;
  duration?: number;
  picUrl?: string;
  // 卡片模型/参数选项 + LLM 推荐（后端 human_input 事件下发；无配置时为空数组/空对象）
  models?: GatewayModelOption[];
  imageModels?: GatewayModelOption[];
  videoModels?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  // 资产选择卡片：可勾选的资产清单（后端 human_input 事件下发；缺失时不渲染勾选）
  assets?: AssetOption[];
}

/** 生成完成后的看图确认卡片（后端 confirm_result 事件） */
export interface ConfirmResultInfo {
  kind: 'script' | 'image' | 'video';
  url: string;
  assetId?: string;
  /** 多图结果（n>1 时后端下发全部 URL；未下发则回退单 url） */
  urls?: string[];
  assetIds?: string[];
  sceneCount?: number;
  actions: { id: string; title: string }[];
}

/** 图生视频方案确认卡片（后端 video_plan 事件）：视觉模型看图设计的方案，确认后生成 */
export interface VideoPlanInfo {
  planToken: string;
  message: string;
  duration: number;
  picUrl: string;
  actions: { id: string; title: string }[];
  /** video_plan 事件携带的后端消息 id（human_input/video_plan 均带 messageId） */
  messageId?: string;
  // 卡片模型/参数选项 + LLM 推荐（后端 video_plan 事件下发；无配置时为空数组/空对象）
  models?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
}

interface AgentState {
  // 窗口
  windowOpen: boolean;
  setWindowOpen: (v: boolean) => void;

  // 会话
  conversations: AgentConversation[];
  activeConversationId: string | null;
  loadingConversations: boolean;
  loadConversations: () => Promise<void>;
  /** 新建对话：仅重置本地为空白草稿（不落库、不进列表）。真正建会话在首次发送消息时由 sendMessage 自动完成。 */
  newConversation: () => void;
  /** 真正创建并落库一个会话（sendMessage 首次发送时自动调用）：POST 后端 + 插到列表头 + 选中。 */
  createConversation: () => Promise<void>;
  renameConversation: (id: string, title: string) => Promise<void>;
  setConversationStatus: (id: string, status: 'active' | 'archived') => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  // 清空当前会话聊天记录（删消息 + 重置 AI 上下文；会话与资产保留，其他会话不受影响）
  clearMessages: () => Promise<void>;
  selectConversation: (id: string) => Promise<void>;

  // 消息
  messages: AgentMessage[];
  streaming: boolean;
  /** 会话级进行中生成状态（key=conversationId）：切走保留、切回恢复；streaming/workflowHint 由此派生 */
  generating: Record<string, {
    hint: string;
    assistantId?: string;
    assistantContent?: string;
    /** 实时推理草稿（reasoning_delta 累积；message_end 后清空并写入 assistantMessageMetadata） */
    reasoningDraft?: AgentReasoning;
    /** 实时轨迹草稿（agent_trace 追加；message_end 后清空并写入 assistantMessageMetadata） */
    traceDraft?: AgentTraceEvent[];
    /** message_end 最终消息元数据（切会话后恢复占位气泡用） */
    assistantMessageMetadata?: AgentMessageMetadata;
  }>;
  waitingHumanInput: HumanInputInfo | null;
  streamError: string | null;
  confirmResult: ConfirmResultInfo | null;
  // 当前运行阶段提示（workflow 事件标题 / 视频异步生成进度；渲染在聊天窗口「正在生成」行）
  workflowHint: string;
  setWorkflowHint: (v: string) => void;
  // 智能体视频异步任务（进行中；completed/failed 后清空）——任务中心悬浮球展示用
  agentVideoTask: { taskId: string; status: 'queued' | 'running'; waitingSec: number } | null;
  // I2：当前流式轮次的 assistant 占位 id（HITL 续流时复用同一气泡追加）
  pendingAssistantId: string | null;

  // 输入
  refImageUrls: string[];
  setRefImageUrls: (v: string[]) => void;
  addRefImageUrls: (urls: string[]) => void;
  removeRefImageUrl: (index: number) => void;
  uploadRefImage: (file: File, purpose?: string, kind?: 'reference' | 'asset') => Promise<void>;
  uploadRefImages: (files: File[], purposes?: string[], kinds?: Array<'reference' | 'asset'>) => Promise<void>;
  addLocalUserMessage: (content: string) => void;

  // 本次携带的资产ID（持久化到发送时消费，不随渲染丢失）
  pickedAssetIds: string[];
  addPickedAssetId: (id: string) => void;
  removePickedAssetId: (id: string) => void;
  clearPickedAssetIds: () => void;
  setPickedAssetIds: (ids: string[]) => void;

  // 互斥标志（会话级，刷新即恢复）
  agentGeneratedScenes: boolean;
  setAgentGeneratedScenes: (v: boolean) => void;

  // 资产
  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  // 发送
  sendMessage: (content: string, opts?: { picUrls?: string[]; assetIds?: string[]; routingHint?: string; cancelActiveTasks?: boolean }) => Promise<void>;
  submitHumanInput: (actionId: string, customText?: string, params?: Record<string, string>, assetIds?: string[]) => Promise<void>;
  // 图生视频方案确认卡片（video_plan 事件）：开始生成视频 → 后端生成；继续完善 → 本地保留参考图
  waitingVideoPlan: VideoPlanInfo | null;
  /** 会话级视频方案确认卡片（发起时存；切回恢复；提交/跳过清） */
  hitlVideoPlan: Record<string, VideoPlanInfo>;
  submitVideoPlan: (actionId: string, params?: Record<string, string>, customText?: string) => Promise<void>;
  // 跳过当前 HITL 卡片（用户想切换话题时使用，checkpoint 30 分钟后自动过期）
  skipCurrentHITL: () => void;
  /** 流结束后恢复已落库但未送达的待处理 HITL 卡片（SSE 被断开/超时后无需刷新即可补显）。 */
  recoverPendingCheckpoint: (conversationId: string) => Promise<void>;
  // ── 分镜视频批量生成队列（右上角任务队列） ──
  storyboardVideoQueue: StoryboardVideoQueueItem[];
  enqueueStoryboardVideoQueue: (items: StoryboardVideoEnqueueItem[], projectId?: string) => Promise<string[]>;
  refetchStoryboardVideoQueue: (projectId?: string) => Promise<void>;
  cancelStoryboardVideoQueue: (queueId: string) => Promise<void>;
  // ── 分镜「完善图片」队列 ──
  storyboardImageQueue: StoryboardImageQueueItem[];
  enqueueStoryboardImageRefine: (items: StoryboardImageEnqueueItem[], projectId?: string) => Promise<string[]>;
  refetchStoryboardImageQueue: (projectId?: string) => Promise<void>;
  cancelStoryboardImageQueue: (queueId: string) => Promise<void>;
  // 停止当前会话生成（「停止生成」按钮）：中断 SSE 流 + 后端协作式取消 + 复位视图
  stopGeneration: () => void;
  // 看图确认卡片（confirm_result 事件）：继续完善 / 满意完成
  refineAsset: () => void;
  dismissConfirm: () => void;
  // 继续完善参考图：点"继续完善"后不自动发送，暂存 PicUrls 等待用户输入完善需求（随下一条消息发送并消费）
  pendingPicUrls: string[];
  cancelRefine: () => void;
  resetChatState: () => void;

  // 页面级状态
  activeModal: 'storyboard' | 'assets' | 'project' | 'settings' | null;
  setActiveModal: (modal: AgentState['activeModal']) => void;
  historyExpanded: boolean;
  setHistoryExpanded: (v: boolean) => void;
}

let initialSceneCount = 0;

// 当前流式会话的 AbortController + 用户手动停止标志：点「停止生成」时中断 SSE fetch（typewriter 停）并委托后端协作式取消
let streamAbort: AbortController | null = null;
let manualStop = false;
// 视频异步任务轮询句柄 + 代次令牌：新一轮发送/停止时作废旧轮询，防止过期轮询结果污染新流
let videoPollTimer: ReturnType<typeof setInterval> | null = null;
let videoPollToken = 0;

/** 从会话 generating 记录恢复 assistant 占位气泡的消息元数据（切会话后重建占位用） */
function buildGeneratingMetadata(g?: { reasoningDraft?: AgentReasoning; traceDraft?: AgentTraceEvent[]; assistantMessageMetadata?: AgentMessageMetadata }): AgentMessageMetadata | undefined {
  if (g?.assistantMessageMetadata) return g.assistantMessageMetadata;
  if (g?.reasoningDraft || (g?.traceDraft && g.traceDraft.length > 0)) {
    return { kind: 'assistant-reply', reasoning: g.reasoningDraft, agentTrace: g.traceDraft };
  }
  return undefined;
}

/** 从消息元数据反解出 HITL 卡片信息（刷新后从 AgentMessage.metadata.hitl 恢复） */
export function hitlFromMetadata(metadata?: AgentMessageMetadata): HumanInputInfo | null {
  const h = metadata?.hitl;
  if (!h) return null;
  return {
    formToken: '',
    taskId: '',
    formContent: h.formContent ?? '',
    actions: h.actions ?? [],
    expirationTime: 0,
    messageId: h.messageId,
  };
}

/** 刷新当前项目的视频/图片任务队列：分镜审查触发的生成（全自动视频/完善图片）入队后即时可见，无需刷新页面。 */
function refreshStoryboardQueues() {
  const projectId = useProjectStore.getState().currentProject?.id;
  if (!projectId) return;
  void useAgentStore.getState().refetchStoryboardVideoQueue(projectId);
  void useAgentStore.getState().refetchStoryboardImageQueue(projectId);
}

export const useAgentStore = create<AgentState>((set, get) => ({
  windowOpen: false,
  setWindowOpen: (v) => set({ windowOpen: v }),

  conversations: [],
  activeConversationId: null,
  loadingConversations: false,
  loadConversations: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) return;
    set({ loadingConversations: true });
    const res = await agentApi.listConversations(projectId);
    const list = res.data.data ?? [];
    set({ conversations: list, loadingConversations: false });
    // 自动选中最近会话
    if (list.length > 0 && !get().activeConversationId) {
      await get().selectConversation(list[0].id);
    }
  },

  // 新建对话：仅重置本地为空白草稿，不落库、不进列表——避免反复点「新对话」堆积空会话。
  // 真正建会话在 sendMessage 首次发送时由 createConversation 完成。
  newConversation: () => {
    set({
      activeConversationId: null,
      messages: [],
      streaming: false,
      waitingHumanInput: null,
      
      waitingVideoPlan: null,
      streamError: null,
      pendingAssistantId: null,
      confirmResult: null,
      pendingPicUrls: [],
      refImageUrls: [],
      workflowHint: '',
      generating: {},
    });
  },

  createConversation: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
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
    set((s) => {
      const g = { ...s.generating };
      delete g[id];
      const h = { ...s.hitlVideoPlan };
      delete h[id];
      const active = s.activeConversationId === id ? null : s.activeConversationId;
      const isActive = s.activeConversationId === id;
      const gview = active ? g[active] : undefined;
      return {
        conversations: s.conversations.filter((c) => c.id !== id),
        activeConversationId: active,
        generating: g,
        streaming: !!gview,
        workflowHint: gview?.hint ?? '',
        messages: isActive ? [] : s.messages,
        waitingHumanInput: isActive ? null : s.waitingHumanInput, 
        waitingVideoPlan: isActive ? null : s.waitingVideoPlan,
        hitlVideoPlan: h,
        confirmResult: isActive ? null : s.confirmResult,
      };
    });
  },

  // 清空当前会话聊天记录：删消息 + 重置 AI 上下文（会话），会话/资产保留
  clearMessages: async () => {
    const id = get().activeConversationId;
    // 守卫：无会话 / 流式生成中 / HITL 等待期 / 图生视频方案确认期禁止清空（防止删除进行中的上下文导致流事件污染）
    if (!id || get().streaming || get().waitingHumanInput || get().waitingVideoPlan) return;
    await agentApi.clearMessages(id);
    set((s) => { const h = { ...s.hitlVideoPlan }; delete h[id]; return { messages: [], waitingHumanInput: null,
       waitingVideoPlan: null, hitlVideoPlan: h, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null }; });
  },

  selectConversation: async (id) => {
    // HITL 按会话隔离（getPendingCheckpoint/hitlVideoPlan 各自恢复），允许切换会话
    set((s) => {
      const g = s.generating[id];
      return {
        activeConversationId: id,
        messages: [],
        streaming: !!g,
        workflowHint: g?.hint ?? '',
        waitingHumanInput: null,
      
        waitingVideoPlan: s.hitlVideoPlan[id] ?? null,
        streamError: null,
        pendingAssistantId: null, confirmResult: null, pendingPicUrls: [], refImageUrls: [], pickedAssetIds: [],
      };
    });
    const res = await agentApi.listMessages(id);
    const g = get().generating[id];
    const base = res.data.data ?? [];
    set({ messages: g?.assistantId
      ? [...base, { id: g.assistantId, conversationId: id, role: 'assistant' as const, content: g.assistantContent ?? '', createdAt: new Date().toISOString(), metadata: buildGeneratingMetadata(g) }]
      : base });
    const conv = get().conversations.find((c) => c.id === id);
    if (conv) {
      initialSceneCount = useProjectStore.getState().scenes.length;
      void get().loadAssets(1);
    }
    // 页面刷新恢复：检查是否有待处理的 HITL checkpoint，恢复 waitingHumanInput
    try {
      const cpRes = await agentApi.getPendingCheckpoint(id);
      const cp = cpRes.data.data as (HumanInputInfo & Partial<VideoPlanInfo>) | null;
      if (cp && cp.formToken && cp.actions && cp.actions.length > 0) {
        if (cp.kind === 'video-plan' || cp.hitlType === 'generate_video') {
          const videoInfo: VideoPlanInfo = {
            planToken: cp.formToken,
            message: cp.message ?? cp.formContent,
            duration: cp.duration ?? 8,
            picUrl: cp.picUrl ?? '',
            actions: cp.actions,
            messageId: cp.messageId,
            models: cp.models,
            recommended: cp.recommended,
            reasons: cp.reasons,
          };
          set((s) => ({ waitingVideoPlan: videoInfo, hitlVideoPlan: { ...s.hitlVideoPlan, [id]: videoInfo } }));
        } else {
          set({ waitingHumanInput: cp });
        }
      }
    } catch { /* 静默失败，不影响正常加载 */ }
  },

  messages: [],
  streaming: false,
  generating: {},
  waitingHumanInput: null,
      
  waitingVideoPlan: null,
  hitlVideoPlan: {},
  streamError: null,
  workflowHint: '',
  setWorkflowHint: (v) => set({ workflowHint: v }),
  agentVideoTask: null,
  storyboardVideoQueue: [],
  storyboardImageQueue: [],
  confirmResult: null,
  pendingPicUrls: [],
  pendingAssistantId: null,

  refImageUrls: [],
  setRefImageUrls: (v) => set({ refImageUrls: v }),
  addRefImageUrls: (urls) => set((s) => ({ refImageUrls: [...new Set([...s.refImageUrls, ...urls])] })),
  removeRefImageUrl: (index) => set((s) => ({ refImageUrls: s.refImageUrls.filter((_, i) => i !== index) })),

  pickedAssetIds: [],
  addPickedAssetId: (id) => set((s) => ({ pickedAssetIds: [...new Set([...s.pickedAssetIds, id])] })),
  removePickedAssetId: (id) => set((s) => ({ pickedAssetIds: s.pickedAssetIds.filter((a) => a !== id) })),
  clearPickedAssetIds: () => set({ pickedAssetIds: [] }),
  setPickedAssetIds: (ids) => set(() => ({ pickedAssetIds: [...new Set(ids)] })),

  uploadRefImage: async (file, purpose?, kind?) => {
    const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined, purpose, kind);
    const url = res.data.data.url;
    set((s) => ({ refImageUrls: [...s.refImageUrls, url] }));
    // 参考图消息显示到对话窗口（与后端 upload 端点落库的 user 消息对齐，刷新后仍在）。
    // 首会话（未选会话，cid 为空）也 push——后端 upload 未归属，但此时可见；首条消息发出去后
    // 后端 streamMessage 会把该 url 的未归属资产认领到新会话（见 AgentChatServiceImpl）。
    const cid = get().activeConversationId;
    const refMsg: AgentMessage = {
      id: `tmp-ref-${Date.now()}`,
      conversationId: cid ?? '',
      role: 'user',
      content: url,
      
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, refMsg] }));
  },
  uploadRefImages: async (files, purposes, kinds) => {
    for (let i = 0; i < files.length; i++) {
      try { await get().uploadRefImage(files[i], purposes?.[i], kinds?.[i]); } catch { /* 单张失败跳过，不阻断其他张 */ }
    }
  },
  addLocalUserMessage: (content) => set((s) => ({
    messages: [...s.messages, {
      id: `tmp-asset-${Date.now()}`,
      conversationId: s.activeConversationId ?? '',
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    }],
  })),

  agentGeneratedScenes: false,
  setAgentGeneratedScenes: (v) => set({ agentGeneratedScenes: v }),

  assets: null,
  loadAssets: async (page = 1) => {
    // M6：请求发起时的会话快照——返回时若已切换会话，丢弃过期分页数据
    const convId = get().activeConversationId;
    if (!convId) return;
    const res = await agentApi.listAssets(convId, page, 20);
    if (get().activeConversationId !== convId) return;
    set({ assets: res.data.data });
  },
  deleteAsset: async (assetId) => {
    await agentApi.deleteAsset(assetId);
    // M10：删除后若当前页只剩 1 条且非第一页，回退一页加载，避免停留在空页
    const assets = get().assets;
    const page = assets?.page ?? 1;
    if ((assets?.records.length ?? 0) === 1 && page > 1) {
      void get().loadAssets(page - 1);
    } else {
      void get().loadAssets(page);
    }
  },

  sendMessage: async (content, opts?: { picUrls?: string[]; assetIds?: string[]; routingHint?: string; cancelActiveTasks?: boolean }) => {
    // 输入禁用条件：(streaming && !agentVideoTask) || waitingHumanInput || waitingVideoPlan
    // 视频异步任务轮询期间（agentVideoTask 存在）允许继续发送新消息
    if ((get().streaming && !get().agentVideoTask) || get().waitingHumanInput || get().waitingVideoPlan || !content.trim()) return;
    // 新一轮发送：清除上次手动停止标志并新建 AbortController（供「停止生成」中断本流）。
    // 若视频异步任务仍轮询，先停止旧轮询并告知后端取消在途任务（cancelActiveTasks: true）。
    if (get().agentVideoTask) {
      if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
      videoPollToken += 1;
      set({ agentVideoTask: null });
      opts = { ...opts, cancelActiveTasks: true };
    }
    manualStop = false;
    streamAbort = new AbortController();
    const signal = streamAbort.signal;
    // 快照参考图——createConversation → selectConversation 会把 refImageUrls/messages 清空，
    // 不先存会导致首条消息 picUrls 丢失（首会话上传图「说话后被吞」）
    const refBefore = get().refImageUrls.map((u) => u);
    // 同样的坑：createConversation → selectConversation 会清空 pickedAssetIds，必须先快照
    const assetIdsBefore = get().pickedAssetIds.map((id) => id);
    let id = get().activeConversationId;
    // 未选对话但已选项目：自动新建对话并选中，避免用户消息被吞
    if (!id) {
      const projectId = useProjectStore.getState().currentProject?.id;
      if (!projectId) { set({ streamError: '请先在左上角选择一个项目' }); return; }
      await get().createConversation();
      id = get().activeConversationId;
    }
    if (!id) return;
    // 首会话：selectConversation 已重置 messages，把参考图消息插回（保持对话窗可见）
    if (refBefore.length > 0 && get().messages.length === 0) {
      set({ messages: refBefore.map((u, i) => ({ id: `tmp-ref-${Date.now()}-${i}`, conversationId: id, role: 'user' as const, content: u, createdAt: new Date().toISOString() })) });
    }
    // 继续完善参考图：点"继续完善"后暂存的 PicUrls，随本条用户消息发送并消费（只带一次，
    // 避免后续普通消息也误带图片导致误判 pic-refine 意图）
    const pendingPic = get().pendingPicUrls;
    if (pendingPic.length > 0) set({ pendingPicUrls: [] });

    // 追加 user 消息（乐观 UI）
    const optimisticUser: AgentMessage = {
      id: `tmp-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content,

      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: [...s.messages, optimisticUser],
      streaming: true,
      generating: { ...s.generating, [id]: { hint: '' } },
      streamError: null,
      waitingHumanInput: null,
      
    }));
    initialSceneCount = useProjectStore.getState().scenes.length;

    // 追加空 assistant 消息（流式填充）
    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      
      createdAt: new Date().toISOString(),
    };
    // I2：记录本轮 assistant 占位 id，HITL 续流时复用同一气泡追加（与后端消息合并对应）
    set((s) => ({
      messages: [...s.messages, optimisticAssistant],
      pendingAssistantId: assistantId,
      generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
    }));

    const updateAssistant = (delta: string) => {
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
        generating: { ...s.generating, [id]: { ...(s.generating[id] ?? { hint: '' }), assistantId, assistantContent: (s.generating[id]?.assistantContent ?? '') + delta } },
      }));
    };

    // I5：message_end 携带后端本地化后的完整回复，整体覆盖占位气泡
    // （增量拼接的签名 URL 已被后端替换为 /api/files/images/ 永久地址）
    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
        generating: { ...s.generating, [id]: { ...(s.generating[id] ?? { hint: '' }), assistantId, assistantContent: full } },
      }));

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    // 推理/轨迹实时草稿（reasoning_delta / agent_trace）写入属主会话 generating + 当前气泡 metadata
    const updateLiveReasoning = (reasoning: AgentReasoning) => {
      set((s) => {
        const g = s.generating[snapshotId] ?? { hint: '' };
        const nextG = { ...g, reasoningDraft: reasoning };
        if (s.activeConversationId !== snapshotId) return { generating: { ...s.generating, [snapshotId]: nextG } };
        return {
          messages: s.messages.map((m) =>
            m.id === assistantId ? { ...m, metadata: { ...(m.metadata ?? {}), kind: m.metadata?.kind ?? 'assistant-reply', reasoning } } : m),
          generating: { ...s.generating, [snapshotId]: nextG },
        };
      });
    };
    const updateLiveTrace = (ev: AgentTraceEvent) => {
      set((s) => {
        const g = s.generating[snapshotId] ?? { hint: '' };
        const existingIndex = (g.traceDraft ?? []).findIndex((item) => item.id === ev.id);
        const nextTrace = existingIndex >= 0
          ? (g.traceDraft ?? []).map((item) => item.id === ev.id ? ev : item)
          : [...(g.traceDraft ?? []), ev];
        const nextG = { ...g, traceDraft: nextTrace };
        if (s.activeConversationId !== snapshotId) return { generating: { ...s.generating, [snapshotId]: nextG } };
        return {
          messages: s.messages.map((m) =>
            m.id === assistantId ? { ...m, metadata: { ...(m.metadata ?? {}), kind: m.metadata?.kind ?? 'assistant-reply', agentTrace: nextTrace } } : m),
          generating: { ...s.generating, [snapshotId]: nextG },
        };
      });
    };
    const setFinalMetadata = (metadata: AgentMessageMetadata | undefined) => {
      set((s) => {
        const g = s.generating[snapshotId] ?? { hint: '' };
        const nextG = { ...g, assistantMessageMetadata: metadata };
        delete nextG.reasoningDraft;
        delete nextG.traceDraft;
        const base = { ...s.generating, [snapshotId]: nextG };
        if (s.activeConversationId !== snapshotId) return { generating: base };
        return {
          messages: s.messages.map((m) => m.id === assistantId ? { ...m, metadata } : m),
          generating: base,
        };
      });
    };
    const mergeFinalMetadata = (e: SseEvent) => {
      const g = get().generating[snapshotId] ?? { hint: '' };
      const reasoning = e.reasoning ?? g.reasoningDraft;
      const agentTrace = e.agentTrace ?? (g.traceDraft && g.traceDraft.length > 0 ? g.traceDraft : undefined);
      const suggestedActions = e.suggestedActions;
      if (reasoning || (agentTrace && agentTrace.length > 0) || suggestedActions) {
        setFinalMetadata({ kind: 'assistant-reply', reasoning, agentTrace, suggestedActions });
      }
    };
    /** 从 agent_trace 事件里提取轨迹事件（兼容单条直发 / traceEvent 包装 / agentTrace 数组） */
    const collectTraceEvents = (e: SseEvent): AgentTraceEvent[] => {
      const out: AgentTraceEvent[] = [];
      if (e.traceEvent) out.push(e.traceEvent);
      if (e.agentTrace) out.push(...e.agentTrace);
      const raw = e as unknown as AgentTraceEvent;
      if (raw.id && raw.phase && raw.agent) out.push(raw);
      return out;
    };
    // 结束标志：正常收尾（message_end / human_input）不算"未收到回复"
    let receivedMessageEnd = false;
    let receivedHumanInput = false;

    try {
      await streamChat(id, content,
        (opts?.picUrls && opts.picUrls.length > 0) ? opts.picUrls
          : (pendingPic && pendingPic.length > 0) ? pendingPic
          : refBefore, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            // 跨会话守卫：已切走则把增量累积到属主会话 generating（切回恢复），不写当前视图消息
            if (get().activeConversationId !== snapshotId) {
              set((s) => ({ generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), assistantId, assistantContent: (s.generating[snapshotId]?.assistantContent ?? '') + (e.content ?? '') } } }));
              break;
            }
            updateAssistant(e.content ?? '');
            break;
          case 'reasoning_delta': {
            // 实时推理：拼接 details、按需更新 summary/durationMs/source，写入当前气泡 metadata.reasoning
            const prev = get().generating[snapshotId]?.reasoningDraft;
            const reasoning: AgentReasoning = {
              summary: prev?.summary ?? e.summary ?? '',
              details: (prev?.details ?? '') + (e.details ?? ''),
              durationMs: e.durationMs ?? prev?.durationMs,
              source: e.source ?? prev?.source ?? 'assistant',
            };
            updateLiveReasoning(reasoning);
            break;
          }
          case 'agent_trace': {
            const events = collectTraceEvents(e);
            if (events.length > 0) for (const ev of events) updateLiveTrace(ev);
            break;
          }
          case 'workflow':
            // 跨会话守卫：已切换会话则忽略旧流阶段提示（防 A 的阶段提示污染 B）
            if (get().activeConversationId !== snapshotId) break;
            // 阶段进度提示：写入属主会话 generating（切走保留、切回恢复）；当前视图同步 workflowHint
            set((s) => ({
              workflowHint: e.title ?? '',
              generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), hint: e.title ?? '' } },
            }));
            break;
          case 'human_input':
            // 跨会话守卫：已切换会话则忽略旧流事件
            if (get().activeConversationId !== snapshotId) break;
            receivedHumanInput = true;
            {
              const cardId = e.messageId ?? `hitl-card-${Date.now()}`;
              set((s) => ({
                workflowHint: '', // HITL 卡片接管展示，阶段提示清空
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
                  messageId: cardId,
                },
                // 未提交期间不渲染卡片，但把占位气泡绑定到后端 messageId，提交后原位复用
                messages: s.messages.map((m) =>
                  m.id === assistantId ? {
                    ...m,
                    id: cardId,
                    content: e.formContent ?? '',
                    metadata: {
                      ...(m.metadata ?? {}),
                      kind: 'hitl',
                      hitl: {
                        formContent: e.formContent ?? '',
                        actions: e.actions ?? [],
                        pending: true,
                        messageId: cardId,
                      },
                    },
                  } : m),
                generating: {
                  ...s.generating,
                  [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), assistantId, assistantContent: s.generating[snapshotId]?.assistantContent ?? '' },
                },
              }));
            }
            break;
          case 'message_end':
            receivedMessageEnd = true;
            // 跨会话守卫：已切换则仅累积到属主会话 generating（metadata/草稿），不写当前视图
            mergeFinalMetadata(e);
            if (get().activeConversationId !== snapshotId) break;
            // I5：后端已把消息里的签名 URL 本地化，用完整文本覆盖占位气泡
            if (typeof e.content === 'string' && e.content) updateAssistantFull(e.content);
            // 首条消息异步 AI 重命名标题：后端在 message_end 一次性携带新 title（仅本轮），就地更新会话列表
            if (typeof e.title === 'string' && e.title) {
              set((s) => ({
                conversations: s.conversations.map((c) =>
                  c.id === snapshotId && c.title !== e.title ? { ...c, title: e.title as string } : c),
              }));
            }
            // 分镜数量变化（增加=agent 生成、减少=agent 删除）都刷新分镜列表；
            // agentGeneratedScenes 互斥标志仅在数量增加时置位（删除后应恢复手动输入）
            if (typeof e.sceneCount === 'number' && e.sceneCount >= 0) {
              if (e.sceneCount > initialSceneCount) {
                get().setAgentGeneratedScenes(true);
              }
              // currentProject 守卫：项目不存在时跳过 loadProject 刷新
              const currentProject = useProjectStore.getState().currentProject;
              if (currentProject) {
                void useProjectStore.getState().loadProject(currentProject.id);
              }
            }
            // 分镜审查触发的生成（全自动生成视频/完善图片）可能刚入队 → 刷新任务队列面板，无需刷新网页
            refreshStoryboardQueues();
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({ confirmResult: e as ConfirmResultInfo });
            break;
          case 'video_plan':
            // 图生视频方案确认卡片（跨会话守卫：已切换会话则忽略旧流事件）
            if (get().activeConversationId !== snapshotId) break;
            set((s) => {
              const cardId = e.messageId ?? `video-plan-${Date.now()}`;
              const info = {
                // 后端 runHITLStage 统一下发 formToken（曾误读 planToken 恒空 → 提交 40401 checkpoint 不存在）
                planToken: e.formToken ?? e.planToken ?? '',
                message: e.message ?? '',
                duration: e.duration ?? 8,
                picUrl: e.picUrl ?? '',
                actions: e.actions ?? [],
                messageId: cardId,
                models: e.models,
                recommended: e.recommended,
                reasons: e.reasons,
              };
              const videoPlanMeta = {
                message: info.message,
                duration: info.duration,
                picUrl: info.picUrl,
                actions: info.actions,
                formToken: info.planToken,
                messageId: cardId,
                models: info.models,
                recommended: info.recommended,
                reasons: info.reasons,
              };
              return {
                waitingVideoPlan: info,
                hitlVideoPlan: { ...s.hitlVideoPlan, [snapshotId]: info },
                messages: s.messages.map((m) =>
                  m.id === assistantId ? {
                    ...m,
                    id: cardId,
                    content: info.message || info.picUrl || '',
                    metadata: {
                      ...(m.metadata ?? {}),
                      kind: 'video-plan',
                      hitl: {
                        formContent: info.message,
                        actions: info.actions,
                        pending: true,
                        messageId: cardId,
                      },
                      videoPlan: videoPlanMeta,
                    },
                  } : m),
              };
            });
            break;
          case 'error':
            // M3：跨会话守卫——已切换会话则忽略旧流错误
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      }, (opts?.assetIds && opts.assetIds.length > 0 ? opts.assetIds
        : assetIdsBefore.length > 0 ? assetIdsBefore
        : get().pickedAssetIds.length > 0 ? get().pickedAssetIds : []), signal, opts);
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误；用户主动停止不视为错误（不弹错误条）
      if (!manualStop && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      // streaming=false 无条件（单流模型）；占位补写仅在仍处于原会话时执行
      // （已切换会话时旧占位消息已被 selectConversation 整组替换）
      // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
      const isThisStream = get().pendingAssistantId === assistantId;
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导；手动停止显示"（已停止）"
      const failedText = manualStop ? '（已停止）' : (get().streamError ? '（回复失败）' : '（未收到回复）');
      set((s) => {
        const g = { ...s.generating };
        delete g[snapshotId];
        const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
        return {
          generating: g,
          streaming: !!gview,
          workflowHint: gview?.hint ?? '',
          ...(isThisStream ? { pendingAssistantId: null } : {}),
          messages: stillSameConversation
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
        };
      });
      // SSE 被网关/代理断开或超时：HITL 卡片可能已落库但未送达，立即恢复补显（无需刷新）
      if (stillSameConversation && !receivedMessageEnd && !receivedHumanInput && !manualStop) {
        void get().recoverPendingCheckpoint(snapshotId);
      }
    }
    // 不再自动清空参考图和资产ID——用户要求持久化到意图节点消费
    // if (get().activeConversationId === snapshotId) set({ refImageUrls: [] });
  },

  submitHumanInput: async (actionId, customText = '', params, assetIds) => {
    const info = get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    // 新一轮发送：清除上次手动停止标志并新建 AbortController（供「停止生成」中断本流）
    manualStop = false;
    streamAbort = new AbortController();
    const signal = streamAbort.signal;
    // 与 sendMessage 一致：提交时清除上次的 streamError，避免旧错误文案残留
    // 保存已提交的人工介入信息：human_input 事件已把当前 assistant 占位气泡转为 HITL 卡片
    // （metadata.hitl），提交后仅更新该卡片选中状态（不再 push 新的用户「确认：xxx」气泡）。
    const act = info.actions.find((a) => a.id === actionId);
    const cardId = info.messageId ?? `hitl-${Date.now()}`;
    const hitlMeta: AgentMessageMetadata = {
      kind: 'hitl',
      pending: false,
      hitl: {
        formContent: info.formContent || '',
        actions: info.actions || [],
        pending: false,
        selectedActionId: actionId,
        selectedActionTitle: act?.title ?? actionId,
        customText: actionId === 'custom' ? customText : undefined,
        params,
        assetIds,
        messageId: cardId,
      },
    };
    set((s) => {
      const hasCard = s.messages.some((m) => m.id === cardId && m.metadata?.kind === 'hitl');
      const newCard: AgentMessage = {
        id: cardId,
        conversationId: id,
        role: 'assistant',
        content: info.formContent || '',
        createdAt: new Date().toISOString(),
        metadata: hitlMeta,
      };
      return {
        streaming: true,
        generating: { ...s.generating, [id]: { hint: '' } },
        waitingHumanInput: null,
        streamError: null,
        messages: hasCard
          ? s.messages.map((m) => (m.id === cardId ? { ...m, metadata: { ...(m.metadata ?? {}), ...hitlMeta } } : m))
          : [...s.messages, newCard],
      };
    });

    const assistantId = `tmp-assistant-${Date.now()}`;
    const updateAssistant = (delta: string) => {
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
        generating: { ...s.generating, [id]: { ...(s.generating[id] ?? { hint: '' }), assistantId, assistantContent: (s.generating[id]?.assistantContent ?? '') + delta } },
      }));
    };

    // I5：message_end 携带后端本地化后的完整回复，整体覆盖占位气泡
    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
        generating: { ...s.generating, [id]: { ...(s.generating[id] ?? { hint: '' }), assistantId, assistantContent: full } },
      }));

    // 预定义选项不 push 用户气泡（选中状态已标记在卡片 metadata.hitl 上）；
    // action=custom 只 push 用户原文气泡（与后端 persistUserConfirmation 对齐，不带「确认：」前缀）
    if (actionId === 'custom' && customText) {
      const confirmMsg: AgentMessage = {
        id: `tmp-user-${Date.now()}`,
        conversationId: id,
        role: 'user',
        content: customText,
        createdAt: new Date().toISOString(),
      };
      set((s) => ({ messages: [...s.messages, confirmMsg] }));
    }

    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      
      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: [...s.messages, optimisticAssistant],
      pendingAssistantId: assistantId,
      generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
    }));

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    // 推理/轨迹实时草稿写入属主会话 generating + 当前气泡 metadata（与 sendMessage 一致）
    const updateLiveReasoning = (reasoning: AgentReasoning) => {
      set((s) => {
        const g = s.generating[snapshotId] ?? { hint: '' };
        const nextG = { ...g, reasoningDraft: reasoning };
        if (s.activeConversationId !== snapshotId) return { generating: { ...s.generating, [snapshotId]: nextG } };
        return {
          messages: s.messages.map((m) =>
            m.id === assistantId ? { ...m, metadata: { ...(m.metadata ?? {}), kind: m.metadata?.kind ?? 'assistant-reply', reasoning } } : m),
          generating: { ...s.generating, [snapshotId]: nextG },
        };
      });
    };
    const updateLiveTrace = (ev: AgentTraceEvent) => {
      set((s) => {
        const g = s.generating[snapshotId] ?? { hint: '' };
        const existingIndex = (g.traceDraft ?? []).findIndex((item) => item.id === ev.id);
        const nextTrace = existingIndex >= 0
          ? (g.traceDraft ?? []).map((item) => item.id === ev.id ? ev : item)
          : [...(g.traceDraft ?? []), ev];
        const nextG = { ...g, traceDraft: nextTrace };
        if (s.activeConversationId !== snapshotId) return { generating: { ...s.generating, [snapshotId]: nextG } };
        return {
          messages: s.messages.map((m) =>
            m.id === assistantId ? { ...m, metadata: { ...(m.metadata ?? {}), kind: m.metadata?.kind ?? 'assistant-reply', agentTrace: nextTrace } } : m),
          generating: { ...s.generating, [snapshotId]: nextG },
        };
      });
    };
    const setFinalMetadata = (metadata: AgentMessageMetadata | undefined) => {
      set((s) => {
        const g = s.generating[snapshotId] ?? { hint: '' };
        const nextG = { ...g, assistantMessageMetadata: metadata };
        delete nextG.reasoningDraft;
        delete nextG.traceDraft;
        const base = { ...s.generating, [snapshotId]: nextG };
        if (s.activeConversationId !== snapshotId) return { generating: base };
        return {
          messages: s.messages.map((m) => m.id === assistantId ? { ...m, metadata } : m),
          generating: base,
        };
      });
    };
    const mergeFinalMetadata = (e: SseEvent) => {
      const g = get().generating[snapshotId] ?? { hint: '' };
      const reasoning = e.reasoning ?? g.reasoningDraft;
      const agentTrace = e.agentTrace ?? (g.traceDraft && g.traceDraft.length > 0 ? g.traceDraft : undefined);
      const suggestedActions = e.suggestedActions;
      if (reasoning || (agentTrace && agentTrace.length > 0) || suggestedActions) {
        setFinalMetadata({ kind: 'assistant-reply', reasoning, agentTrace, suggestedActions });
      }
    };
    const collectTraceEvents = (e: SseEvent): AgentTraceEvent[] => {
      const out: AgentTraceEvent[] = [];
      if (e.traceEvent) out.push(e.traceEvent);
      if (e.agentTrace) out.push(...e.agentTrace);
      const raw = e as unknown as AgentTraceEvent;
      if (raw.id && raw.phase && raw.agent) out.push(raw);
      return out;
    };
    // 结束标志：正常收尾（message_end / human_input）不算"未收到回复"
    let receivedMessageEnd = false;
    let receivedHumanInput = false;

    try {
      await submitForm(id, info.formToken, info.taskId, actionId, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            // 跨会话守卫：已切走则把增量累积到属主会话 generating（切回恢复），不写当前视图消息
            if (get().activeConversationId !== snapshotId) {
              set((s) => ({ generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), assistantId, assistantContent: (s.generating[snapshotId]?.assistantContent ?? '') + (e.content ?? '') } } }));
              break;
            }
            updateAssistant(e.content ?? '');
            break;
          case 'reasoning_delta': {
            const prev = get().generating[snapshotId]?.reasoningDraft;
            const reasoning: AgentReasoning = {
              summary: prev?.summary ?? e.summary ?? '',
              details: (prev?.details ?? '') + (e.details ?? ''),
              durationMs: e.durationMs ?? prev?.durationMs,
              source: e.source ?? prev?.source ?? 'assistant',
            };
            updateLiveReasoning(reasoning);
            break;
          }
          case 'agent_trace': {
            const events = collectTraceEvents(e);
            if (events.length > 0) for (const ev of events) updateLiveTrace(ev);
            break;
          }
          case 'workflow':
            // 跨会话守卫：已切换会话则忽略旧流阶段提示（防 A 的阶段提示污染 B）
            if (get().activeConversationId !== snapshotId) break;
            // 阶段进度提示：写入属主会话 generating（切走保留、切回恢复）；当前视图同步 workflowHint
            set((s) => ({
              workflowHint: e.title ?? '',
              generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), hint: e.title ?? '' } },
            }));
            break;
          case 'human_input':
            // 跨会话守卫：已切换会话则忽略旧流事件
            if (get().activeConversationId !== snapshotId) break;
            receivedHumanInput = true;
            {
              const cardId = e.messageId ?? `hitl-card-${Date.now()}`;
              set((s) => ({
                workflowHint: '', // HITL 卡片接管展示，阶段提示清空
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
                  messageId: cardId,
                },
                // 未提交期间不渲染卡片，但把占位气泡绑定到后端 messageId，提交后原位复用
                messages: s.messages.map((m) =>
                  m.id === assistantId ? {
                    ...m,
                    id: cardId,
                    content: e.formContent ?? '',
                    metadata: {
                      ...(m.metadata ?? {}),
                      kind: 'hitl',
                      hitl: {
                        formContent: e.formContent ?? '',
                        actions: e.actions ?? [],
                        pending: true,
                        messageId: cardId,
                      },
                    },
                  } : m),
                generating: {
                  ...s.generating,
                  [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), assistantId, assistantContent: s.generating[snapshotId]?.assistantContent ?? '' },
                },
              }));
            }
            break;
          case 'message_end':
            receivedMessageEnd = true;
            // 跨会话守卫：已切换则仅累积到属主会话 generating（metadata/草稿），不写当前视图
            mergeFinalMetadata(e);
            if (get().activeConversationId !== snapshotId) break;
            // I5：后端已把消息里的签名 URL 本地化，用完整文本覆盖占位气泡
            if (typeof e.content === 'string' && e.content) updateAssistantFull(e.content);
            // 首条消息异步 AI 重命名标题：后端在 message_end 一次性携带新 title（仅本轮），就地更新会话列表
            if (typeof e.title === 'string' && e.title) {
              set((s) => ({
                conversations: s.conversations.map((c) =>
                  c.id === snapshotId && c.title !== e.title ? { ...c, title: e.title as string } : c),
              }));
            }
            // 分镜数量变化（增加=agent 生成、减少=agent 删除）都刷新分镜列表；
            // agentGeneratedScenes 互斥标志仅在数量增加时置位（删除后应恢复手动输入）
            if (typeof e.sceneCount === 'number' && e.sceneCount >= 0) {
              if (e.sceneCount > initialSceneCount) {
                get().setAgentGeneratedScenes(true);
              }
              // currentProject 守卫：项目不存在时跳过 loadProject 刷新
              const currentProject = useProjectStore.getState().currentProject;
              if (currentProject) {
                void useProjectStore.getState().loadProject(currentProject.id);
              }
            }
            // 分镜审查触发的生成（全自动生成视频/完善图片）可能刚入队 → 刷新任务队列面板，无需刷新网页
            refreshStoryboardQueues();
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({ confirmResult: e as ConfirmResultInfo });
            break;
          case 'video_plan':
            // 图生视频方案确认卡片（跨会话守卫：已切换会话则忽略旧流事件）
            if (get().activeConversationId !== snapshotId) break;
            set((s) => {
              const cardId = e.messageId ?? `video-plan-${Date.now()}`;
              const info = {
                // 后端 runHITLStage 统一下发 formToken（曾误读 planToken 恒空 → 提交 40401 checkpoint 不存在）
                planToken: e.formToken ?? e.planToken ?? '',
                message: e.message ?? '',
                duration: e.duration ?? 8,
                picUrl: e.picUrl ?? '',
                actions: e.actions ?? [],
                messageId: cardId,
                models: e.models,
                recommended: e.recommended,
                reasons: e.reasons,
              };
              const videoPlanMeta = {
                message: info.message,
                duration: info.duration,
                picUrl: info.picUrl,
                actions: info.actions,
                formToken: info.planToken,
                messageId: cardId,
                models: info.models,
                recommended: info.recommended,
                reasons: info.reasons,
              };
              return {
                waitingVideoPlan: info,
                hitlVideoPlan: { ...s.hitlVideoPlan, [snapshotId]: info },
                messages: s.messages.map((m) =>
                  m.id === assistantId ? {
                    ...m,
                    id: cardId,
                    content: info.message || info.picUrl || '',
                    metadata: {
                      ...(m.metadata ?? {}),
                      kind: 'video-plan',
                      hitl: {
                        formContent: info.message,
                        actions: info.actions,
                        pending: true,
                        messageId: cardId,
                      },
                      videoPlan: videoPlanMeta,
                    },
                  } : m),
              };
            });
            break;
          case 'error':
            // M3：跨会话守卫——已切换会话则忽略旧流错误
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      }, customText, params, assetIds, signal);
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误；用户主动停止不视为错误（不弹错误条）
      if (!manualStop && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      // streaming=false 无条件（单流模型）；占位补写仅在仍处于原会话时执行
      // （已切换会话时旧占位消息已被 selectConversation 整组替换）
      // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
      const isThisStream = get().pendingAssistantId === assistantId;
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导；手动停止显示"（已停止）"
      const failedText = manualStop ? '（已停止）' : (get().streamError ? '（回复失败）' : '（未收到回复）');
      set((s) => {
        const g = { ...s.generating };
        delete g[snapshotId];
        const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
        return {
          generating: g,
          streaming: !!gview,
          workflowHint: gview?.hint ?? '',
          ...(isThisStream ? { pendingAssistantId: null } : {}),
          messages: stillSameConversation
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
        };
      });
      // SSE 被网关/代理断开或超时：HITL 卡片可能已落库但未送达，立即恢复补显（无需刷新）
      if (stillSameConversation && !receivedMessageEnd && !receivedHumanInput && !manualStop) {
        void get().recoverPendingCheckpoint(snapshotId);
      }
    }
  },

  /**
   * 图生视频方案确认卡片（video_plan 事件）：
   * - 开始生成视频：统一走 /form/submit（action=generate_video → 后端 VideoIntentHandler.resume）
   *   → 立即收到 task_accepted（视频任务已受理）→ 前端 5s 轮询 GET /tasks/{taskId}，
   *   completed 后渲染视频结果 + confirm_result 卡片；failed 显示错误；
   * - 继续完善：本地关闭卡片，参考图转 pendingPicUrl 保留（与图片完善 refine 同语义），
   *   用户输入完善需求后随下一条消息发送（重新分流 → 再设计）。
   */
  submitVideoPlan: async (actionId, params, customText) => {
    const info = get().waitingVideoPlan;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    // 新一轮发送：清除上次手动停止标志并新建 AbortController（供「停止生成」中断本流）
    manualStop = false;
    streamAbort = new AbortController();
    const signal = streamAbort.signal;
    const cardId = info.messageId ?? `video-plan-${Date.now()}`;
    const selectedActionTitle = info.actions.find((a) => a.id === actionId)?.title ?? actionId;
    const videoPlanMeta = {
      message: info.message,
      duration: info.duration,
      picUrl: info.picUrl,
      actions: info.actions,
      formToken: info.planToken,
      messageId: cardId,
      models: info.models,
      recommended: info.recommended,
      reasons: info.reasons,
    };
    const markVideoCardSubmitted = () => set((s) => {
      const hasCard = s.messages.some((m) => m.id === cardId);
      const nextCard: AgentMessage = {
        id: cardId,
        conversationId: id,
        role: 'assistant',
        content: info.message,
        createdAt: new Date().toISOString(),
        metadata: {
          kind: 'video-plan',
          hitl: {
            formContent: info.message,
            actions: info.actions,
            pending: false,
            selectedActionId: actionId,
            selectedActionTitle,
            customText: actionId === 'custom' ? customText : undefined,
            params,
            messageId: cardId,
          },
          videoPlan: videoPlanMeta,
        },
      };
      return {
        messages: hasCard
          ? s.messages.map((m) => m.id === cardId ? {
              ...m,
              metadata: {
                ...(m.metadata ?? {}),
                ...(nextCard.metadata ?? {}),
              } as AgentMessageMetadata,
            } : m)
          : [...s.messages, nextCard],
      };
    });
    markVideoCardSubmitted();
    // 继续完善：本地关闭卡片 + 保留参考图（assetUrl 转绝对 URL，后端容器内可访问）
    if (actionId === 'refine') {
      set((s) => { const h = { ...s.hitlVideoPlan }; delete h[id]; return { waitingVideoPlan: null, hitlVideoPlan: h, pendingPicUrl: assetUrl(info.picUrl) }; });
      return;
    }
    // 自定义输入：走 submitForm 发送 custom action + 自定义文本，与 HumanInputCard 的 custom 流程对齐
    if (actionId === 'custom') {
      set((s) => {
        const h = { ...s.hitlVideoPlan }; delete h[id];
        return { streaming: true, generating: { ...s.generating, [id]: { hint: '' } }, waitingVideoPlan: null, hitlVideoPlan: h, streamError: null };
      });
      const assistantId = `tmp-assistant-${Date.now()}`;
      set((s) => ({
        messages: [...s.messages, {
          id: `tmp-user-${Date.now()}`, conversationId: id, role: 'user' as const,
          content: customText || '',  createdAt: new Date().toISOString(),
        }, {
          id: assistantId, conversationId: id, role: 'assistant' as const,
          content: '',  createdAt: new Date().toISOString(),
        }],
        pendingAssistantId: assistantId,
        generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
      }));
      const updateAssistant = (delta: string) =>
        set((s) => ({ messages: s.messages.map((m) => m.id === assistantId ? { ...m, content: m.content + delta } : m), generating: { ...s.generating, [id]: { ...(s.generating[id] ?? { hint: '' }), assistantId, assistantContent: (s.generating[id]?.assistantContent ?? '') + delta } } }));
      const updateAssistantFull = (full: string) =>
        set((s) => ({ messages: s.messages.map((m) => m.id === assistantId ? { ...m, content: full } : m), generating: { ...s.generating, [id]: { ...(s.generating[id] ?? { hint: '' }), assistantId, assistantContent: full } } }));
      const snapshotId = id;
      const updateLiveReasoning = (reasoning: AgentReasoning) => {
        set((s) => {
          const g = s.generating[snapshotId] ?? { hint: '' };
          const nextG = { ...g, reasoningDraft: reasoning };
          if (s.activeConversationId !== snapshotId) return { generating: { ...s.generating, [snapshotId]: nextG } };
          return {
            messages: s.messages.map((m) =>
              m.id === assistantId ? { ...m, metadata: { ...(m.metadata ?? {}), kind: m.metadata?.kind ?? 'assistant-reply', reasoning } } : m),
            generating: { ...s.generating, [snapshotId]: nextG },
          };
        });
      };
      const updateLiveTrace = (ev: AgentTraceEvent) => {
        set((s) => {
          const g = s.generating[snapshotId] ?? { hint: '' };
          const existingIndex = (g.traceDraft ?? []).findIndex((item) => item.id === ev.id);
          const nextTrace = existingIndex >= 0
            ? (g.traceDraft ?? []).map((item) => item.id === ev.id ? ev : item)
            : [...(g.traceDraft ?? []), ev];
          const nextG = { ...g, traceDraft: nextTrace };
          if (s.activeConversationId !== snapshotId) return { generating: { ...s.generating, [snapshotId]: nextG } };
          return {
            messages: s.messages.map((m) =>
              m.id === assistantId ? { ...m, metadata: { ...(m.metadata ?? {}), kind: m.metadata?.kind ?? 'assistant-reply', agentTrace: nextTrace } } : m),
            generating: { ...s.generating, [snapshotId]: nextG },
          };
        });
      };
      const setFinalMetadata = (metadata: AgentMessageMetadata | undefined) => {
        set((s) => {
          const g = s.generating[snapshotId] ?? { hint: '' };
          const nextG = { ...g, assistantMessageMetadata: metadata };
          delete nextG.reasoningDraft;
          delete nextG.traceDraft;
          const base = { ...s.generating, [snapshotId]: nextG };
          if (s.activeConversationId !== snapshotId) return { generating: base };
          return {
            messages: s.messages.map((m) => m.id === assistantId ? { ...m, metadata } : m),
            generating: base,
          };
        });
      };
      const mergeFinalMetadata = (e: SseEvent) => {
        const g = get().generating[snapshotId] ?? { hint: '' };
        const reasoning = e.reasoning ?? g.reasoningDraft;
        const agentTrace = e.agentTrace ?? (g.traceDraft && g.traceDraft.length > 0 ? g.traceDraft : undefined);
        const suggestedActions = e.suggestedActions;
        if (reasoning || (agentTrace && agentTrace.length > 0) || suggestedActions) {
          setFinalMetadata({ kind: 'assistant-reply', reasoning, agentTrace, suggestedActions });
        }
      };
      const collectTraceEvents = (e: SseEvent): AgentTraceEvent[] => {
        const out: AgentTraceEvent[] = [];
        if (e.traceEvent) out.push(e.traceEvent);
        if (e.agentTrace) out.push(...e.agentTrace);
        const raw = e as unknown as AgentTraceEvent;
        if (raw.id && raw.phase && raw.agent) out.push(raw);
        return out;
      };
      let receivedMessageEnd = false;
      let receivedHumanInput = false;
      try {
        await submitForm(id, info.planToken, '', 'custom', (e: SseEvent) => {
          switch (e.type) {
            case 'message':
              // 跨会话守卫：已切走则把增量累积到属主会话 generating（切回恢复），不写当前视图消息
              if (get().activeConversationId !== snapshotId) {
                set((s) => ({ generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), assistantId, assistantContent: (s.generating[snapshotId]?.assistantContent ?? '') + (e.content ?? '') } } }));
                break;
              }
              updateAssistant(e.content ?? ''); break;
            case 'reasoning_delta': {
              const prev = get().generating[snapshotId]?.reasoningDraft;
              const reasoning: AgentReasoning = {
                summary: prev?.summary ?? e.summary ?? '',
                details: (prev?.details ?? '') + (e.details ?? ''),
                durationMs: e.durationMs ?? prev?.durationMs,
                source: e.source ?? prev?.source ?? 'assistant',
              };
              updateLiveReasoning(reasoning);
              break;
            }
            case 'agent_trace': {
              const events = collectTraceEvents(e);
              if (events.length > 0) for (const ev of events) updateLiveTrace(ev);
              break;
            }
            case 'workflow':
              // 跨会话守卫：已切换会话则忽略旧流阶段提示（防 A 的阶段提示污染 B）
              if (get().activeConversationId !== snapshotId) break;
              set((s) => ({ workflowHint: e.title ?? '', generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), hint: e.title ?? '' } } })); break;
            case 'human_input':
              if (get().activeConversationId !== snapshotId) break;
              receivedHumanInput = true;
              {
                const cardId = e.messageId ?? `hitl-card-${Date.now()}`;
                set((s) => ({
                  workflowHint: '',
                  waitingHumanInput: {
                    formToken: e.formToken ?? '', taskId: e.taskId ?? '', formContent: e.formContent ?? '',
                    actions: e.actions ?? [], expirationTime: e.expirationTime ?? 0,
                    models: e.models, imageModels: e.imageModels, videoModels: e.videoModels,
                    recommended: e.recommended, reasons: e.reasons, assets: (e as any).assets,
                    messageId: cardId,
                  },
                  // 未提交期间不渲染卡片，但把占位气泡绑定到后端 messageId，提交后原位复用
                  messages: s.messages.map((m) =>
                    m.id === assistantId ? {
                      ...m,
                      id: cardId,
                      content: e.formContent ?? '',
                      metadata: {
                        ...(m.metadata ?? {}),
                        kind: 'hitl',
                        hitl: {
                          formContent: e.formContent ?? '',
                          actions: e.actions ?? [],
                          pending: true,
                          messageId: cardId,
                        },
                      },
                    } : m),
                  generating: {
                    ...s.generating,
                    [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), assistantId, assistantContent: s.generating[snapshotId]?.assistantContent ?? '' },
                  },
                }));
              }
              break;
            case 'video_plan':
              if (get().activeConversationId !== snapshotId) break;
              set((s) => {
                const cardId = e.messageId ?? `video-plan-${Date.now()}`;
                const info = {
                  planToken: e.formToken ?? e.planToken ?? '', message: e.message ?? '',
                  duration: e.duration ?? 8, picUrl: e.picUrl ?? '', actions: e.actions ?? [],
                  messageId: cardId,
                  models: e.models, recommended: e.recommended, reasons: e.reasons,
                };
                const videoPlanMeta = {
                  message: info.message,
                  duration: info.duration,
                  picUrl: info.picUrl,
                  actions: info.actions,
                  formToken: info.planToken,
                  messageId: cardId,
                  models: info.models,
                  recommended: info.recommended,
                  reasons: info.reasons,
                };
                return {
                  waitingVideoPlan: info,
                  hitlVideoPlan: { ...s.hitlVideoPlan, [snapshotId]: info },
                  messages: s.messages.map((m) =>
                    m.id === assistantId ? {
                      ...m,
                      id: cardId,
                      content: info.message || info.picUrl || '',
                      metadata: {
                        ...(m.metadata ?? {}),
                        kind: 'video-plan',
                        hitl: {
                          formContent: info.message,
                          actions: info.actions,
                          pending: true,
                          messageId: cardId,
                        },
                        videoPlan: videoPlanMeta,
                      },
                    } : m),
                };
              });
              break;
            case 'message_end':
              receivedMessageEnd = true;
              mergeFinalMetadata(e);
              if (get().activeConversationId !== snapshotId) break;
              if (e.content) updateAssistantFull(e.content);
              if (e.title) {
                set((s) => ({
                  conversations: s.conversations.map((c) => c.id === id && c.title !== e.title ? { ...c, title: e.title! } : c),
                }));
              }
              if (typeof e.sceneCount === 'number' && e.sceneCount >= 0) {
                const proj = useProjectStore.getState().currentProject;
                if (proj) void useProjectStore.getState().loadProject(proj.id);
              }
              refreshStoryboardQueues();
              break;
            case 'error':
              if (get().activeConversationId === snapshotId) set({ streamError: e.message ?? '对话出错，请重试' });
              break;
          }
        }, customText, params, undefined, signal);
      } catch (err) {
        if (!manualStop && get().activeConversationId === snapshotId) set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      } finally {
        // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
        const isThisStream = get().pendingAssistantId === assistantId;
        const stillSame = get().activeConversationId === snapshotId;
        const failedText = manualStop ? '（已停止）' : (get().streamError ? '（回复失败）' : '（未收到回复）');
        set((s) => {
          const g = { ...s.generating };
          delete g[snapshotId];
          const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
          return {
            generating: g,
            streaming: !!gview,
            workflowHint: gview?.hint ?? '',
            ...(isThisStream ? { pendingAssistantId: null } : {}),
            messages: stillSame
            ? s.messages.map((m) => m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput ? { ...m, content: failedText } : m)
            : s.messages,
            };
            });
      }
      return;
    }
    // 开始生成视频：与 submitHumanInput 一致——提交时清除上次 streamError
    set((s) => {
      const h = { ...s.hitlVideoPlan }; delete h[id];
      return { streaming: true, generating: { ...s.generating, [id]: { hint: '' } }, waitingVideoPlan: null, hitlVideoPlan: h, streamError: null };
    });

    // 新建 assistant 占位气泡：视频异步生成期间为空（AgentChatPanel 显示生成中加载条），
    // 完成后由轮询结果填充，MessageBubble 渲染 <video> 播放器
    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      
      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: [...s.messages, optimisticAssistant],
      pendingAssistantId: assistantId,
      generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
    }));

    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
        generating: { ...s.generating, [id]: { ...(s.generating[id] ?? { hint: '' }), assistantId, assistantContent: full } },
      }));

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    let receivedResult = false;
    // 视频异步任务：task_accepted 后启动 5s 轮询（跨会话守卫；completed/failed 停止）
    let acceptedTaskId: string | null = null;
    let pollTick = 0; // 轮询次数（阶段提示「已等待约 X 秒」用，5s/tick）
    const startTaskPolling = (taskId: string) => {
      // 任务中心：任务开始，暴露进行中状态（completed/failed 清空）
      set({ agentVideoTask: { taskId, status: 'queued', waitingSec: 0 } });
      const myToken = ++videoPollToken;
      const timer = setInterval(async () => {
        if (acceptedTaskId === null) { clearInterval(timer); videoPollTimer = null; return; }
        // 新一轮发送/停止生成时已作废旧轮询：直接退出（防止过期任务结果污染新流）
        if (videoPollToken !== myToken || get().agentVideoTask?.taskId !== taskId) { clearInterval(timer); return; }
        pollTick += 1;
        try {
          const res = await agentApi.getVideoTaskStatus(taskId);
          const t: VideoTaskStatus = res.data.data;
          if (t.status === 'completed') {
            clearInterval(timer); videoPollTimer = null;
            receivedResult = true;
            updateAssistantFull(t.url || '');
            set((s) => {
              const g = { ...s.generating };
              delete g[snapshotId];
              const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
              return {
                generating: g,
                streaming: !!gview,
                workflowHint: gview?.hint ?? '',
                pendingAssistantId: null,
                agentVideoTask: null,
                confirmResult: {
                  kind: 'video', url: t.url || '', assetId: t.assetId, sceneCount: 0,
                  actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
                },
              };
            });
            void get().loadAssets();
          } else if (t.status === 'failed') {
            clearInterval(timer); videoPollTimer = null;
            set((s) => {
              const g = { ...s.generating };
              delete g[snapshotId];
              const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
              const isThis = s.activeConversationId === snapshotId;
              return {
                generating: g,
                streaming: !!gview,
                workflowHint: gview?.hint ?? '',
                agentVideoTask: null,
                ...(isThis ? { streamError: t.error || '视频生成失败，请重试' } : {}),
              };
            });
          } else {
            // queued/running：写入属主会话 generating（切走后台继续、切回见最新进度）；仅当正看该会话才同步视图
            const waiting = Math.round(pollTick * 5);
            const stage = t.status === 'queued'
              ? `视频排队中…（已等待约 ${waiting} 秒）`
              : `视频生成中…（已等待约 ${waiting} 秒，通常 1~3 分钟）`;
            set((s) => {
              const g = { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), hint: stage } };
              const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
              return {
                generating: g,
                streaming: !!gview,
                workflowHint: gview?.hint ?? '',
                agentVideoTask: { taskId, status: t.status === 'queued' ? 'queued' : 'running', waitingSec: waiting },
              };
            });
          }
          // queued/running：继续轮询（5s 后下一次 tick）
        } catch {
          // 单次轮询失败不终止：下一 tick 重试（后端瞬时故障容错）
        }
      }, VIDEO_POLL_INTERVAL);
      videoPollTimer = timer;
    };

    try {
      // 统一 HITL 提交路径：video_plan 的 planToken 即 checkpoint formToken
      await submitForm(id, info.planToken, '', 'generate_video', (e: SseEvent) => {
        switch (e.type) {
          case 'task_accepted':
            // 视频任务已受理：流即将结束，转轮询取结果（跨会话守卫）
            if (get().activeConversationId !== snapshotId) break;
            acceptedTaskId = e.taskId ?? '';
            set((s) => ({
              workflowHint: e.message ?? '视频任务已受理，正在排队生成…',
              generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), hint: e.message ?? '视频任务已受理，正在排队生成…' } },
            }));
            if (acceptedTaskId) startTaskPolling(acceptedTaskId);
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            receivedResult = true;
            set({ confirmResult: e as ConfirmResultInfo });
            break;
          case 'error':
            // M3：跨会话守卫——已切换会话则忽略旧流错误
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '生成出错，请重试' });
            }
            break;
        }
      }, '', params, undefined, signal);
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误；用户主动停止不视为错误（不弹错误条）
      if (!manualStop && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '生成出错，请重试' });
      }
    } finally {
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 异步视频任务已受理：保持 streaming（轮询 completed 时关闭），占位气泡不补写
      if (acceptedTaskId) {
        if (!get().streaming) set({ streaming: true });
        return;
      }
      // 未受理（异常/失败）：该会话不在生成，删除 generating + 按 active 会话重置视图
      const failedText = manualStop ? '（已停止）' : (get().streamError ? '（生成失败）' : '（未收到回复）');
      set((s) => {
        const g = { ...s.generating };
        delete g[snapshotId];
        const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
        return {
          generating: g,
          streaming: !!gview,
          workflowHint: gview?.hint ?? '',
          pendingAssistantId: null,
          messages: stillSameConversation
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedResult
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
        };
      });
    }
  },

  /** 停止当前会话生成（「停止生成」按钮）：中断 SSE 流（打字机停）+ 后端协作式取消 + 复位视图 */
  stopGeneration: () => {
    const id = get().activeConversationId;
    manualStop = true;
    // 中断当前 SSE fetch（若有）：consumeSse 循环检测 signal.aborted 会立即跳出 → 触发 sendMessage 等的 finally 清理
    streamAbort?.abort();
    streamAbort = null;
    // 视频异步任务轮询期：停止轮询并作废代次令牌
    if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
    videoPollToken += 1;
    // 后端协作式取消：置位会话级 interrupt 标志（编排流式循环/阶段边界检测后停止生成、释放会话锁）
    if (id) void agentApi.interrupt(id).catch(() => { /* 静默：后端取消尽力而为 */ });
    // 视频异步任务轮询期 / 无活跃 SSE 流：直接复位当前会话视图（后端视频任务仍会后台跑完）
    set((s) => {
      const g = { ...s.generating };
      if (id) delete g[id];
      const gview = id && s.activeConversationId ? g[s.activeConversationId] : undefined;
      return { generating: g, streaming: !!gview, workflowHint: gview?.hint ?? '' };
    });
  },

  /** 跳过当前 HITL 卡片：清除等待状态，让用户继续输入其他话题（checkpoint 30 分钟后自动过期） */
  skipCurrentHITL: () => {
    const id = get().activeConversationId;
    // 后端作废 pending checkpoint，防止刷新/切换会话后旧卡片恢复
    if (id) void agentApi.skipPendingCheckpoint(id).catch(() => {});
    set((s) => { const h = { ...s.hitlVideoPlan }; if (id) delete h[id]; return { waitingHumanInput: null,
       waitingVideoPlan: null, hitlVideoPlan: h }; });
  },

  /** 流结束后恢复已落库的待处理 HITL 卡片：SSE 被网关/代理断开或超时，卡片只落库未送达时，可在不刷新页面的情况下补显。 */
  recoverPendingCheckpoint: async (conversationId: string) => {
    if (!conversationId) return;
    try {
      const cpRes = await agentApi.getPendingCheckpoint(conversationId);
      const cp = cpRes.data.data as (HumanInputInfo & Partial<VideoPlanInfo>) | null;
      if (!cp || !cp.formToken || !cp.actions || cp.actions.length === 0) return;
      const isActive = get().activeConversationId === conversationId;
      if (cp.kind === 'video-plan' || cp.hitlType === 'generate_video') {
        const videoInfo: VideoPlanInfo = {
          planToken: cp.formToken,
          message: cp.message ?? cp.formContent,
          duration: cp.duration ?? 8,
          picUrl: cp.picUrl ?? '',
          actions: cp.actions,
          messageId: cp.messageId,
          models: cp.models,
          recommended: cp.recommended,
          reasons: cp.reasons,
        };
        set((s) => ({
          waitingVideoPlan: videoInfo,
          hitlVideoPlan: { ...s.hitlVideoPlan, [conversationId]: videoInfo },
          workflowHint: isActive ? '' : s.workflowHint,
        }));
      } else {
        set((s) => ({
          waitingHumanInput: cp,
          workflowHint: isActive ? '' : s.workflowHint,
        }));
      }
    } catch { /* 静默：网络抖动不打断聊天 */ }
  },

  /** 分镜视频批量入队（全自动生成视频门禁确认后调用）。 */
  enqueueStoryboardVideoQueue: async (items, projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    const res = await agentApi.enqueueStoryboardVideos({
      conversationId: get().activeConversationId ?? undefined,
      projectId: pid,
      items,
    });
    const created = res.data.data ?? [];
    // 入队后刷新整队（后端已启动串行处理）
    if (pid) void get().refetchStoryboardVideoQueue(pid);
    return created.map((row) => row.id);
  },

  /** 刷新任务队列（右上角面板轮询 + 分镜行状态）。 */
  refetchStoryboardVideoQueue: async (projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    if (!pid) return;
    try {
      const res = await agentApi.listStoryboardVideoQueue(pid);
      set({ storyboardVideoQueue: res.data.data ?? [] });
    } catch {
      /* 静默：网络抖动不打断聊天 */
    }
  },

  /** 取消单个队列任务。 */
  cancelStoryboardVideoQueue: async (queueId) => {
    await agentApi.cancelStoryboardVideoQueue(queueId);
    const pid = useProjectStore.getState().currentProject?.id;
    if (pid) void get().refetchStoryboardVideoQueue(pid);
  },

  /** 分镜「完善图片」批量入队（图改图模式）。 */
  enqueueStoryboardImageRefine: async (items, projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    const res = await agentApi.enqueueStoryboardImageRefine({
      conversationId: get().activeConversationId ?? undefined,
      projectId: pid,
      items,
    });
    const created = res.data.data ?? [];
    if (pid) void get().refetchStoryboardImageQueue(pid);
    return created.map((row) => row.id);
  },

  /** 刷新「完善图片」队列（右上角面板轮询 + 分镜行状态）。 */
  refetchStoryboardImageQueue: async (projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    if (!pid) return;
    try {
      const res = await agentApi.listStoryboardImageQueue(pid);
      set({ storyboardImageQueue: res.data.data ?? [] });
    } catch {
      /* 静默 */
    }
  },

  /** 取消单个「完善图片」队列任务。 */
  cancelStoryboardImageQueue: async (queueId) => {
    await agentApi.cancelStoryboardImageQueue(queueId);
    const pid = useProjectStore.getState().currentProject?.id;
    if (pid) void get().refetchStoryboardImageQueue(pid);
  },

  /** 看图确认卡片：继续完善 → 暂存当前图 PicUrl，不自动发送；用户输入完善需求后随下一条消息发送 */
  refineAsset: () => {
    const { confirmResult } = get();
    if (!confirmResult || confirmResult.kind === 'script') return;
    // 审查修复：confirmResult.url 是后端相对路径（/api/files/images/x.png），容器内无法访问；
    // assetUrl() 拼接 BACKEND 前缀转绝对 URL（http/data: 透传）后再发给后端
    const picUrl = assetUrl(confirmResult.url);
    set({ confirmResult: null, pendingPicUrls: [picUrl] });
  },
  /** 取消继续完善：清空暂存参考图（输入框提示条上的 ✕ 触发） */
  cancelRefine: () => set({ pendingPicUrls: [] }),
  /**
   * 看图确认卡片：满意完成 → 通知后端清空图片上下文变量（下次图片需求走全新设计）。
   * 成功收起卡片 + 刷新资产；失败保留卡片并提示（可重试）。纯前端收起（旧行为）会
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

  resetChatState: () => {
    if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
    videoPollToken += 1;
    set({ messages: [], generating: {}, streaming: false, waitingHumanInput: null,
       waitingVideoPlan: null, hitlVideoPlan: {}, streamError: null, workflowHint: '', assets: null, refImageUrls: [], pendingAssistantId: null, confirmResult: null, pendingPicUrls: [], storyboardImageQueue: [] });
  },

  activeModal: null,
  setActiveModal: (modal) => set({ activeModal: modal }),
  historyExpanded: false,
  setHistoryExpanded: (v) => set({ historyExpanded: v }),
}));
