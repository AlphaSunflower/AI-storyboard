import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent, type VideoTaskStatus, type AssetOption,
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
  generating: Record<string, { hint: string; assistantId?: string; assistantContent?: string }>;
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
  uploadRefImage: (file: File) => Promise<void>;
  uploadRefImages: (files: File[]) => Promise<void>;

  // 互斥标志（会话级，刷新即恢复）
  agentGeneratedScenes: boolean;
  setAgentGeneratedScenes: (v: boolean) => void;

  // 资产
  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  // 发送
  sendMessage: (content: string, opts?: { picUrls?: string[]; assetIds?: string[] }) => Promise<void>;
  submitHumanInput: (actionId: string, customText?: string, params?: Record<string, string>, assetIds?: string[]) => Promise<void>;
  // 图生视频方案确认卡片（video_plan 事件）：开始生成视频 → 后端生成；继续完善 → 本地保留参考图
  waitingVideoPlan: VideoPlanInfo | null;
  /** 会话级视频方案确认卡片（发起时存；切回恢复；提交/跳过清） */
  hitlVideoPlan: Record<string, VideoPlanInfo>;
  submitVideoPlan: (actionId: string, params?: Record<string, string>, customText?: string) => Promise<void>;
  // 跳过当前 HITL 卡片（用户想切换话题时使用，checkpoint 30 分钟后自动过期）
  skipCurrentHITL: () => void;
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
    set((s) => { const h = { ...s.hitlVideoPlan }; delete h[id]; return { messages: [], waitingHumanInput: null, waitingVideoPlan: null, hitlVideoPlan: h, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null }; });
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
        pendingAssistantId: null, confirmResult: null, pendingPicUrls: [], refImageUrls: [],
      };
    });
    const res = await agentApi.listMessages(id);
    const g = get().generating[id];
    const base = res.data.data ?? [];
    set({ messages: g?.assistantId
      ? [...base, { id: g.assistantId, conversationId: id, role: 'assistant' as const, content: g.assistantContent ?? '', createdAt: new Date().toISOString() }]
      : base });
    const conv = get().conversations.find((c) => c.id === id);
    if (conv) {
      initialSceneCount = useProjectStore.getState().scenes.length;
      void get().loadAssets(1);
    }
    // 页面刷新恢复：检查是否有待处理的 HITL checkpoint，恢复 waitingHumanInput
    try {
      const cpRes = await agentApi.getPendingCheckpoint(id);
      const cp = cpRes.data.data as HumanInputInfo | null;
      if (cp && cp.formToken && cp.actions && cp.actions.length > 0) {
        set({ waitingHumanInput: cp });
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
  confirmResult: null,
  pendingPicUrls: [],
  pendingAssistantId: null,

  refImageUrls: [],
  setRefImageUrls: (v) => set({ refImageUrls: v }),
  addRefImageUrls: (urls) => set((s) => ({ refImageUrls: [...new Set([...s.refImageUrls, ...urls])] })),
  removeRefImageUrl: (index) => set((s) => ({ refImageUrls: s.refImageUrls.filter((_, i) => i !== index) })),
  uploadRefImage: async (file) => {
    const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined);
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
  uploadRefImages: async (files) => {
    for (const f of files) {
      try { await get().uploadRefImage(f); } catch { /* 单张失败跳过，不阻断其他张 */ }
    }
  },

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

  sendMessage: async (content, opts?: { picUrls?: string[]; assetIds?: string[] }) => {
    if (get().streaming || !content.trim()) return;
    // 快照参考图——createConversation → selectConversation 会把 refImageUrls/messages 清空，
    // 不先存会导致首条消息 picUrls 丢失（首会话上传图「说话后被吞」）
    const refBefore = get().refImageUrls.map((u) => u);
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
            set({
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
              },
            });
            break;
          case 'message_end':
            receivedMessageEnd = true;
            // 跨会话守卫：已切换会话则跳过互斥判断与刷新
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
            if (typeof e.sceneCount === 'number' && e.sceneCount !== initialSceneCount) {
              if (e.sceneCount > initialSceneCount) {
                get().setAgentGeneratedScenes(true);
              }
              // currentProject 守卫：项目不存在时跳过 loadProject 刷新
              const currentProject = useProjectStore.getState().currentProject;
              if (currentProject) {
                void useProjectStore.getState().loadProject(currentProject.id);
              }
            }
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({ confirmResult: e as ConfirmResultInfo });
            break;
          case 'video_plan':
            // 图生视频方案确认卡片（跨会话守卫：已切换会话则忽略旧流事件）
            if (get().activeConversationId !== snapshotId) break;
            set((s) => {
              const info = {
                // 后端 runHITLStage 统一下发 formToken（曾误读 planToken 恒空 → 提交 40401 checkpoint 不存在）
                planToken: e.formToken ?? e.planToken ?? '',
                message: e.message ?? '',
                duration: e.duration ?? 8,
                picUrl: e.picUrl ?? '',
                actions: e.actions ?? [],
                models: e.models,
                recommended: e.recommended,
                reasons: e.reasons,
              };
              return { waitingVideoPlan: info, hitlVideoPlan: { ...s.hitlVideoPlan, [snapshotId]: info } };
            });
            break;
          case 'error':
            // M3：跨会话守卫——已切换会话则忽略旧流错误
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      }, (opts?.assetIds && opts.assetIds.length > 0 ? opts.assetIds : []));
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误
      if (get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      // streaming=false 无条件（单流模型）；占位补写仅在仍处于原会话时执行
      // （已切换会话时旧占位消息已被 selectConversation 整组替换）
      // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
      const isThisStream = get().pendingAssistantId === assistantId;
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导
      const failedText = get().streamError ? '（回复失败）' : '（未收到回复）';
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
    }
    // 清空参考图（M4：仅当仍在原会话时清空，避免切换会话后误清新会话的参考图）
    if (get().activeConversationId === snapshotId) set({ refImageUrls: [] });
  },

  submitHumanInput: async (actionId, customText = '', params, assetIds) => {
    const info = get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    // 与 sendMessage 一致：提交时清除上次的 streamError，避免旧错误文案残留
    set((s) => ({
      streaming: true,
      generating: { ...s.generating, [id]: { hint: '' } },
      waitingHumanInput: null,
      streamError: null,
    }));
    // 刷新基准：提交时刻的项目分镜数（sendMessage 时的旧值可能已过期，
    // 例如 HITL 期间分镜被其他操作修改；确认写库后 sceneCount(总数) > 此值才触发列表刷新）
    initialSceneCount = useProjectStore.getState().scenes.length;

    // I2（2026-08-19 修正）：HITL 续流总是新建 assistant 气泡——与后端 persistAssistant
    // 每次 insert 对齐，避免确认后内容续写在旧方案气泡里（用户期望 HITL 后从新气泡开始）。
    // 方案文本（formContent）保留在 HITL 前的旧气泡：若旧气泡为空（方案未流式输出）则填入，
    // 避免残留空"…"占位；若已有流式方案内容则不动。
    const lastAssistant = [...get().messages].reverse().find((m) => m.role === 'assistant');
    if (info.formContent && lastAssistant && !lastAssistant.content) {
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === lastAssistant.id ? { ...m, content: info.formContent as string } : m),
      }));
    }
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

    // HITL 确认动作本地落位（与后端落库对齐，点击按钮后消息不消失）：
    // 1) 方案文本（formContent）填入 assistant 占位——后端在 HITL 暂停时已将其落库为 assistant 消息；
    // 2) 用户确认动作 push 为 user 消息——后端 submitFormAndResume 同步落库「确认：{标题}」。
    // 刷新后由后端持久化的同序消息替换本地临时项，顺序一致（方案在前、确认在后）。
    // 顺序：先 push 用户确认消息，再 push assistant 占位气泡——确认在前、回复在后
    const act = info.actions.find((a) => a.id === actionId);
    const confirmTitle = act?.title ?? actionId;
    const confirmMsg: AgentMessage = {
      id: `tmp-user-${Date.now()}`,
      conversationId: id,
      role: 'user',
      // 自定义输入：落库用户原文（与后端 persistUserConfirmation 对齐，不带「确认：」前缀）
      content: actionId === 'custom' ? customText : `确认：${confirmTitle}`,
      
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, confirmMsg] }));

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
            set({
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
              },
            });
            break;
          case 'message_end':
            receivedMessageEnd = true;
            // 跨会话守卫：已切换会话则跳过互斥判断与刷新
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
            if (typeof e.sceneCount === 'number' && e.sceneCount !== initialSceneCount) {
              if (e.sceneCount > initialSceneCount) {
                get().setAgentGeneratedScenes(true);
              }
              // currentProject 守卫：项目不存在时跳过 loadProject 刷新
              const currentProject = useProjectStore.getState().currentProject;
              if (currentProject) {
                void useProjectStore.getState().loadProject(currentProject.id);
              }
            }
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({ confirmResult: e as ConfirmResultInfo });
            break;
          case 'video_plan':
            // 图生视频方案确认卡片（跨会话守卫：已切换会话则忽略旧流事件）
            if (get().activeConversationId !== snapshotId) break;
            set((s) => {
              const info = {
                // 后端 runHITLStage 统一下发 formToken（曾误读 planToken 恒空 → 提交 40401 checkpoint 不存在）
                planToken: e.formToken ?? e.planToken ?? '',
                message: e.message ?? '',
                duration: e.duration ?? 8,
                picUrl: e.picUrl ?? '',
                actions: e.actions ?? [],
                models: e.models,
                recommended: e.recommended,
                reasons: e.reasons,
              };
              return { waitingVideoPlan: info, hitlVideoPlan: { ...s.hitlVideoPlan, [snapshotId]: info } };
            });
            break;
          case 'error':
            // M3：跨会话守卫——已切换会话则忽略旧流错误
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      }, customText, params, assetIds);
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误
      if (get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      // streaming=false 无条件（单流模型）；占位补写仅在仍处于原会话时执行
      // （已切换会话时旧占位消息已被 selectConversation 整组替换）
      // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
      const isThisStream = get().pendingAssistantId === assistantId;
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导
      const failedText = get().streamError ? '（回复失败）' : '（未收到回复）';
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
            case 'workflow':
              // 跨会话守卫：已切换会话则忽略旧流阶段提示（防 A 的阶段提示污染 B）
              if (get().activeConversationId !== snapshotId) break;
              set((s) => ({ workflowHint: e.title ?? '', generating: { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), hint: e.title ?? '' } } })); break;
            case 'human_input':
              if (get().activeConversationId !== snapshotId) break;
              receivedHumanInput = true;
              set({
                workflowHint: '',
                waitingHumanInput: {
                  formToken: e.formToken ?? '', taskId: e.taskId ?? '', formContent: e.formContent ?? '',
                  actions: e.actions ?? [], expirationTime: e.expirationTime ?? 0,
                  models: e.models, imageModels: e.imageModels, videoModels: e.videoModels,
                  recommended: e.recommended, reasons: e.reasons, assets: (e as any).assets,
                },
              });
              break;
            case 'video_plan':
              if (get().activeConversationId !== snapshotId) break;
              set((s) => {
                const info = {
                  planToken: e.formToken ?? e.planToken ?? '', message: e.message ?? '',
                  duration: e.duration ?? 8, picUrl: e.picUrl ?? '', actions: e.actions ?? [],
                  models: e.models, recommended: e.recommended, reasons: e.reasons,
                };
                return { waitingVideoPlan: info, hitlVideoPlan: { ...s.hitlVideoPlan, [snapshotId]: info } };
              });
              break;
            case 'message_end':
              if (get().activeConversationId !== snapshotId) break;
              receivedMessageEnd = true;
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
              break;
            case 'error':
              if (get().activeConversationId === snapshotId) set({ streamError: e.message ?? '对话出错，请重试' });
              break;
          }
        }, customText, params);
      } catch (err) {
        if (get().activeConversationId === snapshotId) set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      } finally {
        // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
        const isThisStream = get().pendingAssistantId === assistantId;
        const stillSame = get().activeConversationId === snapshotId;
        const failedText = get().streamError ? '（回复失败）' : '（未收到回复）';
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

    // 确认动作 push 为 user 消息（与后端 persistUserConfirmation 落库对齐，刷新后同序）
    const confirmMsg: AgentMessage = {
      id: `tmp-user-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content: '确认：开始生成视频',
      
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, confirmMsg] }));

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
      const timer = setInterval(async () => {
        if (acceptedTaskId === null) { clearInterval(timer); return; }
        pollTick += 1;
        try {
          const res = await agentApi.getVideoTaskStatus(taskId);
          const t: VideoTaskStatus = res.data.data;
          if (t.status === 'completed') {
            clearInterval(timer);
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
            clearInterval(timer);
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
      }, '', params);
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误
      if (get().activeConversationId === snapshotId) {
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
      const failedText = get().streamError ? '（生成失败）' : '（未收到回复）';
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

  /** 跳过当前 HITL 卡片：清除等待状态，让用户继续输入其他话题（checkpoint 30 分钟后自动过期） */
  skipCurrentHITL: () => {
    const id = get().activeConversationId;
    set((s) => { const h = { ...s.hitlVideoPlan }; if (id) delete h[id]; return { waitingHumanInput: null, waitingVideoPlan: null, hitlVideoPlan: h }; });
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

  resetChatState: () =>
    set({ messages: [], generating: {}, streaming: false, waitingHumanInput: null, waitingVideoPlan: null, hitlVideoPlan: {}, streamError: null, workflowHint: '', assets: null, refImageUrls: [], pendingAssistantId: null, confirmResult: null, pendingPicUrls: [] }),

  activeModal: null,
  setActiveModal: (modal) => set({ activeModal: modal }),
  historyExpanded: false,
  setHistoryExpanded: (v) => set({ historyExpanded: v }),
}));
