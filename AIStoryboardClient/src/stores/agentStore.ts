import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent,
} from '../api/agent';
import { useProjectStore } from './projectStore';

export interface HumanInputInfo {
  formToken: string;
  taskId: string;
  formContent: string;
  actions: { id: string; title: string }[];
  expirationTime: number;
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
  selectConversation: (id: string) => Promise<void>;

  // 消息
  messages: AgentMessage[];
  streaming: boolean;
  waitingHumanInput: HumanInputInfo | null;
  streamError: string | null;
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
  sendMessage: (content: string) => Promise<void>;
  submitHumanInput: (actionId: string) => Promise<void>;
  resetChatState: () => void;
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
    }));
  },

  selectConversation: async (id) => {
    // I3 store 守卫：HITL 等待期禁止切换会话（UI 层另有禁用，双保险）
    if (get().waitingHumanInput) return;
    set({ activeConversationId: id, messages: [], waitingHumanInput: null, streamError: null, pendingAssistantId: null });
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
  streamError: null,
  pendingAssistantId: null,

  refImageUrl: null,
  setRefImageUrl: (v) => set({ refImageUrl: v }),
  uploadRefImage: async (file) => {
    const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined);
    set({ refImageUrl: res.data.data.url });
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

  sendMessage: async (content) => {
    const id = get().activeConversationId;
    if (!id || get().streaming || !content.trim()) return;

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

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    // 结束标志：正常收尾（message_end / human_input）不算"未收到回复"
    let receivedMessageEnd = false;
    let receivedHumanInput = false;

    try {
      await streamChat(id, content, get().refImageUrl ?? undefined, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            updateAssistant(e.content ?? '');
            break;
          case 'workflow':
            break; // 进度提示可后续在 UI 展示，本期仅打字机
          case 'human_input':
            // 跨会话守卫：已切换会话则忽略旧流事件
            if (get().activeConversationId !== snapshotId) break;
            receivedHumanInput = true;
            set({
              waitingHumanInput: {
                formToken: e.formToken ?? '',
                taskId: e.taskId ?? '',
                formContent: e.formContent ?? '',
                actions: e.actions ?? [],
                expirationTime: e.expirationTime ?? 0,
              },
            });
            break;
          case 'message_end':
            receivedMessageEnd = true;
            // 跨会话守卫：已切换会话则跳过互斥判断与刷新
            if (get().activeConversationId !== snapshotId) break;
            if (typeof e.sceneCount === 'number' && e.sceneCount > initialSceneCount) {
              get().setAgentGeneratedScenes(true);
              // currentProject 守卫：项目不存在时跳过 loadProject 刷新
              const currentProject = useProjectStore.getState().currentProject;
              if (currentProject) {
                void useProjectStore.getState().loadProject(currentProject.id);
              }
            }
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
      set((s) => ({
        streaming: false,
        pendingAssistantId: null,
        messages: stillSameConversation
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                ? { ...m, content: '（未收到回复）' }
                : m,
            )
          : s.messages,
      }));
    }
    // 清空参考图（M4：仅当仍在原会话时清空，避免切换会话后误清新会话的参考图）
    if (get().activeConversationId === snapshotId) set({ refImageUrl: null });
  },

  submitHumanInput: async (actionId) => {
    const info = get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    set({ streaming: true, waitingHumanInput: null });

    // I2：HITL 续流复用同一 assistant 占位——续写原占位气泡（与后端消息合并对应），
    // 找不到（如刷新后 pendingAssistantId 丢失）则新建占位兜底
    let assistantId = get().pendingAssistantId ?? '';
    if (!assistantId || !get().messages.some((m) => m.id === assistantId)) {
      assistantId = `tmp-assistant-${Date.now()}`;
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

    const updateAssistant = (delta: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
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
            updateAssistant(e.content ?? '');
            break;
          case 'human_input':
            // 跨会话守卫：已切换会话则忽略旧流事件
            if (get().activeConversationId !== snapshotId) break;
            receivedHumanInput = true;
            set({
              waitingHumanInput: {
                formToken: e.formToken ?? '',
                taskId: e.taskId ?? '',
                formContent: e.formContent ?? '',
                actions: e.actions ?? [],
                expirationTime: e.expirationTime ?? 0,
              },
            });
            break;
          case 'message_end':
            receivedMessageEnd = true;
            // 跨会话守卫：已切换会话则跳过互斥判断与刷新
            if (get().activeConversationId !== snapshotId) break;
            if (typeof e.sceneCount === 'number' && e.sceneCount > initialSceneCount) {
              get().setAgentGeneratedScenes(true);
              // currentProject 守卫：项目不存在时跳过 loadProject 刷新
              const currentProject = useProjectStore.getState().currentProject;
              if (currentProject) {
                void useProjectStore.getState().loadProject(currentProject.id);
              }
            }
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
      set((s) => ({
        streaming: false,
        pendingAssistantId: null,
        messages: stillSameConversation
          ? s.messages.map((m) =>
              m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                ? { ...m, content: '（未收到回复）' }
                : m,
            )
          : s.messages,
      }));
    }
  },

  resetChatState: () =>
    set({ messages: [], waitingHumanInput: null, streamError: null, assets: null, refImageUrl: null, pendingAssistantId: null }),
}));
