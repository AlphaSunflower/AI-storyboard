import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent, type VideoTaskStatus, type AssetOption,
} from '../api/agent';
import { useProjectStore } from './projectStore';
import { assetUrl } from '../config';
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
  refImageUrl: string | null;
  setRefImageUrl: (v: string | null) => void;
  uploadRefImage: (file: File) => Promise<void>;

  // 互斥标志（会话级，刷新即恢复）
  agentGeneratedScenes: boolean;
  setAgentGeneratedScenes: (v: boolean) => void;

  // 资产
  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  // 发送
  sendMessage: (content: string, opts?: { picUrl?: string }) => Promise<void>;
  submitHumanInput: (actionId: string, customText?: string, params?: Record<string, string>, assetIds?: string[]) => Promise<void>;
  // 图生视频方案确认卡片（video_plan 事件）：开始生成视频 → 后端生成；继续完善 → 本地保留参考图
  waitingVideoPlan: VideoPlanInfo | null;
  submitVideoPlan: (actionId: string, params?: Record<string, string>) => Promise<void>;
  // 看图确认卡片（confirm_result 事件）：继续完善 / 满意完成
  refineAsset: () => void;
  dismissConfirm: () => void;
  // 继续完善参考图：点"继续完善"后不自动发送，暂存 PicUrl 等待用户输入完善需求（随下一条消息发送并消费）
  pendingPicUrl: string | null;
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
    set((s) => ({
      conversations: s.conversations.filter((c) => c.id !== id),
      activeConversationId: s.activeConversationId === id ? null : s.activeConversationId,
      messages: s.activeConversationId === id ? [] : s.messages,
      waitingHumanInput: s.activeConversationId === id ? null : s.waitingHumanInput,
      waitingVideoPlan: s.activeConversationId === id ? null : s.waitingVideoPlan,
      confirmResult: s.activeConversationId === id ? null : s.confirmResult,
    }));
  },

  // 清空当前会话聊天记录：删消息 + 重置 AI 上下文（Dify 会话），会话/资产保留
  clearMessages: async () => {
    const id = get().activeConversationId;
    // 守卫：无会话 / 流式生成中 / HITL 等待期 / 图生视频方案确认期禁止清空（防止删除进行中的上下文导致流事件污染）
    if (!id || get().streaming || get().waitingHumanInput || get().waitingVideoPlan) return;
    await agentApi.clearMessages(id);
    set({ messages: [], waitingHumanInput: null, waitingVideoPlan: null, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null });
  },

  selectConversation: async (id) => {
    // I3 store 守卫：HITL 等待期 / 图生视频方案确认期禁止切换会话（UI 层另有禁用，双保险）
    if (get().waitingHumanInput || get().waitingVideoPlan) return;
    set({ activeConversationId: id, messages: [], waitingHumanInput: null, waitingVideoPlan: null, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null, refImageUrl: null });
    const res = await agentApi.listMessages(id);
    set({ messages: res.data.data ?? [] });
    const conv = get().conversations.find((c) => c.id === id);
    if (conv) {
      initialSceneCount = useProjectStore.getState().scenes.length;
      void get().loadAssets(1);
    }
  },

  messages: [],
  streaming: false,
  waitingHumanInput: null,
  waitingVideoPlan: null,
  streamError: null,
  workflowHint: '',
  setWorkflowHint: (v) => set({ workflowHint: v }),
  agentVideoTask: null,
  confirmResult: null,
  pendingPicUrl: null,
  pendingAssistantId: null,

  refImageUrl: null,
  setRefImageUrl: (v) => set({ refImageUrl: v }),
  uploadRefImage: async (file) => {
    const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined);
    const url = res.data.data.url;
    set({ refImageUrl: url });
    // 参考图消息显示到对话窗口（与后端 upload 端点落库的 user 消息对齐，刷新后仍在）；
    // 未选会话时后端不落库，前端也不 push（messages 为空）
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

  sendMessage: async (content, opts?: { picUrl?: string }) => {
    if (get().streaming || !content.trim()) return;
    let id = get().activeConversationId;
    // 未选对话但已选项目：自动新建对话并选中，避免用户消息被吞
    if (!id) {
      const projectId = useProjectStore.getState().currentProject?.id;
      if (!projectId) { set({ streamError: '请先在左上角选择一个项目' }); return; }
      await get().createConversation();
      id = get().activeConversationId;
    }
    if (!id) return;
    // 继续完善参考图：点"继续完善"后暂存的 PicUrl，随本条用户消息发送并消费（只带一次，
    // 避免后续普通消息也误带图片导致 Dify 误判 pic-refine 意图）
    const pendingPic = get().pendingPicUrl;
    if (pendingPic) set({ pendingPicUrl: null });

    // 追加 user 消息（乐观 UI）
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
    }));
    initialSceneCount = useProjectStore.getState().scenes.length;

    // 追加空 assistant 消息（流式填充）
    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    // I2：记录本轮 assistant 占位 id，HITL 续流时复用同一气泡追加（与后端消息合并对应）
    set((s) => ({ messages: [...s.messages, optimisticAssistant], pendingAssistantId: assistantId }));

    const updateAssistant = (delta: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
      }));

    // I5：message_end 携带后端本地化后的完整回复，整体覆盖占位气泡
    // （增量拼接的 Dify 签名 URL 已被后端替换为 /api/files/images/ 永久地址）
    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
      }));

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    // 结束标志：正常收尾（message_end / human_input）不算"未收到回复"
    let receivedMessageEnd = false;
    let receivedHumanInput = false;

    try {
      await streamChat(id, content, opts?.picUrl ?? pendingPic ?? get().refImageUrl ?? undefined, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            updateAssistant(e.content ?? '');
            break;
          case 'workflow':
            // 阶段进度提示（如「正在设计视频方案…」）：写入聊天窗口「正在生成」行；node_finished 无标题→清空
            set({ workflowHint: e.title ?? '' });
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
            // I5：后端已把消息里的 Dify 签名 URL 本地化，用完整文本覆盖占位气泡
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
            set({
              waitingVideoPlan: {
                // 后端 runHITLStage 统一下发 formToken（曾误读 planToken 恒空 → 提交 40401 checkpoint 不存在）
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
            // M3：跨会话守卫——已切换会话则忽略旧流错误
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      });
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误
      if (get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      // streaming=false 无条件（单流模型）；占位补写仅在仍处于原会话时执行
      // （已切换会话时旧占位消息已被 selectConversation 整组替换）
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导
      const failedText = get().streamError ? '（回复失败）' : '（未收到回复）';
      set((s) => ({
        streaming: false,
        pendingAssistantId: null,
        messages: stillSameConversation
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
      }));
    }
    // 清空参考图（M4：仅当仍在原会话时清空，避免切换会话后误清新会话的参考图）
    if (get().activeConversationId === snapshotId) set({ refImageUrl: null });
  },

  submitHumanInput: async (actionId, customText = '', params, assetIds) => {
    const info = get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    // 与 sendMessage 一致：提交时清除上次的 streamError，避免旧错误文案残留
    set({ streaming: true, waitingHumanInput: null, streamError: null });
    // 刷新基准：提交时刻的项目分镜数（sendMessage 时的旧值可能已过期，
    // 例如 HITL 期间分镜被其他操作修改；确认写库后 sceneCount(总数) > 此值才触发列表刷新）
    initialSceneCount = useProjectStore.getState().scenes.length;

    // I2：HITL 续流复用同一 assistant 占位——续写原占位气泡（与后端消息合并对应）。
    // sendMessage 流结束（receivedHumanInput=true）时 pendingAssistantId 已被 finally 清空，
    // 故需兜底复用「最后一条空内容 assistant 占位」，避免新建重复气泡导致方案填错气泡。
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

    // I5：message_end 携带后端本地化后的完整回复，整体覆盖占位气泡
    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
      }));

    // HITL 确认动作本地落位（与后端落库对齐，点击按钮后消息不消失）：
    // 1) 方案文本（formContent）填入 assistant 占位——后端在 HITL 暂停时已将其落库为 assistant 消息；
    // 2) 用户确认动作 push 为 user 消息——后端 submitFormAndResume 同步落库「确认：{标题}」。
    // 刷新后由后端持久化的同序消息替换本地临时项，顺序一致（方案在前、确认在后）。
    const act = info.actions.find((a) => a.id === actionId);
    const confirmTitle = act?.title ?? actionId;
    if (info.formContent) updateAssistantFull(info.formContent);
    const confirmMsg: AgentMessage = {
      id: `tmp-user-${Date.now()}`,
      conversationId: id,
      role: 'user',
      // 自定义输入：落库用户原文（与后端 persistUserConfirmation 对齐，不带「确认：」前缀）
      content: actionId === 'custom' ? customText : `确认：${confirmTitle}`,
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, confirmMsg] }));

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    // 结束标志：正常收尾（message_end / human_input）不算"未收到回复"
    let receivedMessageEnd = false;
    let receivedHumanInput = false;

    try {
      await submitForm(id, info.formToken, info.taskId, actionId, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            updateAssistant(e.content ?? '');
            break;
          case 'workflow':
            // 阶段进度提示（如「正在生成图片…」/「正在生成视频…」）
            set({ workflowHint: e.title ?? '' });
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
            // I5：后端已把消息里的 Dify 签名 URL 本地化，用完整文本覆盖占位气泡
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
            set({
              waitingVideoPlan: {
                // 后端 runHITLStage 统一下发 formToken（曾误读 planToken 恒空 → 提交 40401 checkpoint 不存在）
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
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导
      const failedText = get().streamError ? '（回复失败）' : '（未收到回复）';
      set((s) => ({
        streaming: false,
        pendingAssistantId: null,
        messages: stillSameConversation
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
   * 图生视频方案确认卡片（video_plan 事件）：
   * - 开始生成视频：统一走 /form/submit（action=generate_video → 后端 VideoIntentHandler.resume）
   *   → 立即收到 task_accepted（视频任务已受理）→ 前端 5s 轮询 GET /tasks/{taskId}，
   *   completed 后渲染视频结果 + confirm_result 卡片；failed 显示错误；
   * - 继续完善：本地关闭卡片，参考图转 pendingPicUrl 保留（与图片完善 refine 同语义），
   *   用户输入完善需求后随下一条消息发送（重新分流 → 再设计）。
   */
  submitVideoPlan: async (actionId, params) => {
    const info = get().waitingVideoPlan;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    // 继续完善：本地关闭卡片 + 保留参考图（assetUrl 转绝对 URL，后端容器内可访问）
    if (actionId === 'refine') {
      set({ waitingVideoPlan: null, pendingPicUrl: assetUrl(info.picUrl) });
      return;
    }
    // 开始生成视频：与 submitHumanInput 一致——提交时清除上次 streamError
    set({ streaming: true, waitingVideoPlan: null, streamError: null });

    // 确认动作 push 为 user 消息（与后端 persistUserConfirmation 落库对齐，刷新后同序）
    const confirmMsg: AgentMessage = {
      id: `tmp-user-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content: '确认：开始生成视频',
      difyMessageId: null,
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
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, optimisticAssistant], pendingAssistantId: assistantId }));

    const updateAssistantFull = (full: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: full } : m),
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
              workflowHint: '', // 生成完成，阶段提示清空
              pendingAssistantId: null,
              agentVideoTask: null,
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
              set({ streamError: t.error || '视频生成失败，请重试', workflowHint: '', agentVideoTask: null });
            }
          } else {
            // queued/running：阶段提示实时更新到聊天窗口（防「卡住」错觉）；继续轮询
            const waiting = Math.round(pollTick * 5);
            const stage = t.status === 'queued'
              ? `视频排队中…（已等待约 ${waiting} 秒）`
              : `视频生成中…（已等待约 ${waiting} 秒，通常 1~3 分钟）`;
            set({
              workflowHint: stage,
              agentVideoTask: { taskId, status: t.status === 'queued' ? 'queued' : 'running', waitingSec: waiting },
            });
          }
          // queued/running：继续轮询（5s 后下一次 tick）
        } catch {
          // 单次轮询失败不终止：下一 tick 重试（后端瞬时故障容错）
        }
      }, 5000);
    };

    try {
      // 统一 HITL 提交路径：video_plan 的 planToken 即 checkpoint formToken
      await submitForm(id, info.planToken, '', 'generate_video', (e: SseEvent) => {
        switch (e.type) {
          case 'task_accepted':
            // 视频任务已受理：流即将结束，转轮询取结果（跨会话守卫）
            if (get().activeConversationId !== snapshotId) break;
            acceptedTaskId = e.taskId ?? '';
            set({ workflowHint: e.message ?? '视频任务已受理，正在排队生成…' });
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
      // 失败（streamError 非空）时占位显示"（生成失败）"，避免空气泡误导
      const failedText = get().streamError ? '（生成失败）' : '（未收到回复）';
      set((s) => ({
        streaming: false,
        pendingAssistantId: null,
        messages: stillSameConversation
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedResult
                ? { ...m, content: failedText }
                : m,
            )
          : s.messages,
      }));
    }
  },

  /** 看图确认卡片：继续完善 → 暂存当前图 PicUrl，不自动发送；用户输入完善需求后随下一条消息发送 */
  refineAsset: () => {
    const { confirmResult } = get();
    if (!confirmResult || confirmResult.kind === 'script') return;
    // 审查修复：confirmResult.url 是后端相对路径（/api/files/images/x.png），Dify 容器内无法访问；
    // assetUrl() 拼接 BACKEND 前缀转绝对 URL（http/data: 透传）后再发给 Dify
    const picUrl = assetUrl(confirmResult.url);
    set({ confirmResult: null, pendingPicUrl: picUrl });
  },
  /** 取消继续完善：清空暂存参考图（输入框提示条上的 ✕ 触发） */
  cancelRefine: () => set({ pendingPicUrl: null }),
  /**
   * 看图确认卡片：满意完成 → 通知后端清空 Dify storage_pic_talk 变量（下次图片需求走全新设计）。
   * 成功收起卡片 + 刷新资产；失败保留卡片并提示（可重试）。纯前端收起（旧行为）会让 Dify
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
    set({ messages: [], waitingHumanInput: null, waitingVideoPlan: null, streamError: null, workflowHint: '', assets: null, refImageUrl: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null }),

  activeModal: null,
  setActiveModal: (modal) => set({ activeModal: modal }),
  historyExpanded: false,
  setHistoryExpanded: (v) => set({ historyExpanded: v }),
}));
