import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent, type GatewayModelOption,
} from '../api/agent';

export interface HumanInputInfo {
  formToken: string;
  taskId: string;
  formContent: string;
  actions: { id: string; title: string }[];
  expirationTime: number;
  models?: GatewayModelOption[];
  imageModels?: GatewayModelOption[];
  videoModels?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
}

interface AgentState {
  // 项目
  projectId: string | null;
  setProjectId: (id: string) => void;

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

  // 资产
  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  // 发送
  sendMessage: (content: string) => Promise<void>;
  submitHumanInput: (actionId: string, customText?: string) => Promise<void>;
  resetChatState: () => void;

  // 页面级状态
  activeModal: 'storyboard' | 'assets' | 'project' | 'settings' | null;
  setActiveModal: (modal: AgentState['activeModal']) => void;
  historyExpanded: boolean;
  setHistoryExpanded: (v: boolean) => void;
}

export const useAgentStore = create<AgentState>((set, get) => ({
  projectId: null,
  setProjectId: (id) => set({ projectId: id }),

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
    }));
  },

  clearMessages: async () => {
    const id = get().activeConversationId;
    if (!id || get().streaming || get().waitingHumanInput) return;
    await agentApi.clearMessages(id);
    set({ messages: [], waitingHumanInput: null, streamError: null, pendingAssistantId: null });
  },

  selectConversation: async (id) => {
    if (get().waitingHumanInput) return;
    set({ activeConversationId: id, messages: [], waitingHumanInput: null, streamError: null, pendingAssistantId: null });
    const res = await agentApi.listMessages(id);
    set({ messages: res.data.data ?? [] });
    void get().loadAssets(1);
  },

  messages: [],
  streaming: false,
  waitingHumanInput: null,
  streamError: null,
  workflowHint: '',
  setWorkflowHint: (v) => set({ workflowHint: v }),
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

  sendMessage: async (content) => {
    if (get().streaming || !content.trim()) return;
    let id = get().activeConversationId;
    if (!id) {
      const projectId = get().projectId;
      if (!projectId) { set({ streamError: '请先设置项目 ID' }); return; }
      await get().createConversation();
      id = get().activeConversationId;
    }
    if (!id) return;

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

    try {
      await streamChat(id, content, undefined, (e: SseEvent) => {
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
          case 'error':
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      });
    } catch (err) {
      if (get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      const stillSame = get().activeConversationId === snapshotId;
      const failedText = get().streamError ? '（回复失败）' : '（未收到回复）';
      set((s) => ({
        streaming: false,
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

  submitHumanInput: async (actionId, customText = '') => {
    const info = get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    set({ streaming: true, waitingHumanInput: null, streamError: null });

    // HITL 后新建气泡：pendingAssistantId = null，由 submitHumanInput 创建新占位
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

    // 本地落位：方案文本 + 用户确认
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
          case 'error':
            if (get().activeConversationId === snapshotId) {
              set({ streamError: e.message ?? '对话出错，请重试' });
            }
            break;
        }
      }, customText);
    } catch (err) {
      if (get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      const stillSame = get().activeConversationId === snapshotId;
      const failedText = get().streamError ? '（回复失败）' : '（未收到回复）';
      set((s) => ({
        streaming: false,
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

  resetChatState: () =>
    set({ messages: [], waitingHumanInput: null, streamError: null, workflowHint: '', assets: null, pendingAssistantId: null }),

  activeModal: null,
  setActiveModal: (modal) => set({ activeModal: modal }),
  historyExpanded: false,
  setHistoryExpanded: (v) => set({ historyExpanded: v }),
}));
