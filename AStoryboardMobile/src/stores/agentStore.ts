import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent, type VideoTaskStatus, type AssetOption,
  type GatewayModelOption,
} from '../api/agent';
import { useProjectStore } from './projectStore';
import { assetUrl } from '../config';

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
  assets?: AssetOption[];
}

export interface ConfirmResultInfo {
  kind: 'script' | 'image' | 'video';
  url: string;
  assetId?: string;
  urls?: string[];
  assetIds?: string[];
  sceneCount?: number;
  actions: { id: string; title: string }[];
}

export interface VideoPlanInfo {
  planToken: string;
  message: string;
  duration: number;
  picUrl: string;
  actions: { id: string; title: string }[];
  models?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
}

interface AgentState {
  conversations: AgentConversation[];
  activeConversationId: string | null;
  loadingConversations: boolean;
  loadConversations: () => Promise<void>;
  newConversation: () => void;
  createConversation: () => Promise<void>;
  renameConversation: (id: string, title: string) => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  archiveConversation: (id: string) => Promise<void>;
  clearMessages: () => Promise<void>;
  selectConversation: (id: string) => Promise<void>;

  messages: AgentMessage[];
  streaming: boolean;
  waitingHumanInput: HumanInputInfo | null;
  waitingVideoPlan: VideoPlanInfo | null;
  streamError: string | null;
  confirmResult: ConfirmResultInfo | null;
  workflowHint: string;
  setWorkflowHint: (v: string) => void;
  pendingAssistantId: string | null;
  pendingPicUrl: string | null;

  refImageUrl: string | null;
  setRefImageUrl: (v: string | null) => void;

  agentGeneratedScenes: boolean;
  setAgentGeneratedScenes: (v: boolean) => void;

  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  sendMessage: (content: string, opts?: { picUrl?: string }) => Promise<void>;
  submitHumanInput: (actionId: string, customText?: string, params?: Record<string, string>, assetIds?: string[]) => Promise<void>;
  submitVideoPlan: (actionId: string, params?: Record<string, string>, customText?: string) => Promise<void>;
  skipCurrentHITL: () => void;
  refineAsset: () => void;
  dismissConfirm: () => void;
  cancelRefine: () => void;
  resetChatState: () => void;
}

let initialSceneCount = 0;

export const useAgentStore = create<AgentState>((set, get) => ({
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
    if (list.length > 0 && !get().activeConversationId) {
      await get().selectConversation(list[0].id);
    }
  },

  newConversation: () => {
    set({
      activeConversationId: null,
      messages: [],
      waitingHumanInput: null,
      waitingVideoPlan: null,
      streamError: null,
      pendingAssistantId: null,
      confirmResult: null,
      pendingPicUrl: null,
      refImageUrl: null,
      workflowHint: '',
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

  archiveConversation: async (id) => {
    await agentApi.updateConversation(id, { status: 'archived' });
    set((s) => ({
      conversations: s.conversations.map((c) => c.id === id ? { ...c, status: 'archived' as const } : c),
    }));
  },

  clearMessages: async () => {
    const id = get().activeConversationId;
    if (!id || get().streaming || get().waitingHumanInput || get().waitingVideoPlan) return;
    await agentApi.clearMessages(id);
    set({ messages: [], waitingHumanInput: null, waitingVideoPlan: null, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null });
  },

  selectConversation: async (id) => {
    if (get().waitingHumanInput || get().waitingVideoPlan) return;
    set({ activeConversationId: id, messages: [], waitingHumanInput: null, waitingVideoPlan: null, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null, refImageUrl: null });
    const res = await agentApi.listMessagesPage(id);
    set({ messages: res.data.data?.records ?? [] });
    initialSceneCount = useProjectStore.getState().scenes.length;
    void get().loadAssets(1);
    try {
      const cpRes = await agentApi.getPendingCheckpoint(id);
      const cp = cpRes.data.data as HumanInputInfo | null;
      if (cp && cp.formToken && cp.actions && cp.actions.length > 0) {
        set({ waitingHumanInput: cp });
      }
    } catch { /* 静默 */ }
  },

  messages: [],
  streaming: false,
  waitingHumanInput: null,
  waitingVideoPlan: null,
  streamError: null,
  workflowHint: '',
  setWorkflowHint: (v) => set({ workflowHint: v }),
  confirmResult: null,
  pendingPicUrl: null,
  pendingAssistantId: null,

  refImageUrl: null,
  setRefImageUrl: (v) => set({ refImageUrl: v }),

  agentGeneratedScenes: false,
  setAgentGeneratedScenes: (v) => set({ agentGeneratedScenes: v }),

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

  sendMessage: async (content, opts) => {
    if (get().streaming || !content.trim()) return;
    let id = get().activeConversationId;
    if (!id) {
      const projectId = useProjectStore.getState().currentProject?.id;
      if (!projectId) { set({ streamError: '请先选择一个项目' }); return; }
      await get().createConversation();
      id = get().activeConversationId;
    }
    if (!id) return;
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
    }));
    initialSceneCount = useProjectStore.getState().scenes.length;

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
            if (get().activeConversationId !== snapshotId) break;
            if (typeof e.content === 'string' && e.content) updateAssistantFull(e.content);
            if (typeof e.title === 'string' && e.title) {
              set((s) => ({
                conversations: s.conversations.map((c) =>
                  c.id === snapshotId && c.title !== e.title ? { ...c, title: e.title as string } : c),
              }));
            }
            if (typeof e.sceneCount === 'number' && e.sceneCount > initialSceneCount) {
              set({ agentGeneratedScenes: true });
              void useProjectStore.getState().loadProject(useProjectStore.getState().currentProject!.id);
            }
            break;
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({
              confirmResult: {
                kind: e.kind ?? 'image',
                url: e.url ?? '',
                assetId: e.assetId,
                urls: e.urls,
                assetIds: e.assetIds,
                sceneCount: e.sceneCount,
                actions: e.actions ?? [],
              },
            });
            break;
          case 'video_plan':
            if (get().activeConversationId !== snapshotId) break;
            set({
              waitingVideoPlan: {
                planToken: e.planToken ?? '',
                message: e.content ?? '',
                duration: e.duration ?? 6,
                picUrl: e.picUrl ?? '',
                actions: e.actions ?? [],
                models: e.models,
                recommended: e.recommended,
                reasons: e.reasons,
              },
            });
            break;
          case 'task_accepted':
            if (get().activeConversationId !== snapshotId) break;
            set({ workflowHint: '视频生成中...' });
            break;
          case 'error':
            set({ streamError: e.message ?? '请求失败' });
            break;
        }
      });
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '请求失败';
      set({ streamError: msg });
    } finally {
      set({ streaming: false, pendingAssistantId: null, workflowHint: '' });
    }
  },

  submitHumanInput: async (actionId, customText, params, assetIds) => {
    const { activeConversationId, waitingHumanInput } = get();
    if (!activeConversationId || !waitingHumanInput) return;
    set({ streaming: true, streamError: null, waitingHumanInput: null });

    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: activeConversationId,
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

    const snapshotId = activeConversationId;

    try {
      await submitForm(
        activeConversationId,
        waitingHumanInput.formToken,
        waitingHumanInput.taskId,
        actionId,
        (e: SseEvent) => {
          switch (e.type) {
            case 'message':
              updateAssistant(e.content ?? '');
              break;
            case 'workflow':
              set({ workflowHint: e.title ?? '' });
              break;
            case 'human_input':
              if (get().activeConversationId !== snapshotId) break;
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
              if (get().activeConversationId !== snapshotId) break;
              if (typeof e.content === 'string' && e.content) {
                set((s) => ({
                  messages: s.messages.map((m) =>
                    m.id === assistantId ? { ...m, content: e.content! } : m),
                }));
              }
              if (typeof e.title === 'string' && e.title) {
                set((s) => ({
                  conversations: s.conversations.map((c) =>
                    c.id === snapshotId && c.title !== e.title ? { ...c, title: e.title as string } : c),
                }));
              }
              if (typeof e.sceneCount === 'number' && e.sceneCount > initialSceneCount) {
                set({ agentGeneratedScenes: true });
                void useProjectStore.getState().loadProject(useProjectStore.getState().currentProject!.id);
              }
              break;
            case 'task_accepted':
              if (get().activeConversationId !== snapshotId) break;
              set({ workflowHint: '视频生成中...' });
              break;
            case 'confirm_result':
              if (get().activeConversationId !== snapshotId) break;
              set({
                confirmResult: {
                  kind: e.kind ?? 'image',
                  url: e.url ?? '',
                  assetId: e.assetId,
                  urls: e.urls,
                  assetIds: e.assetIds,
                  sceneCount: e.sceneCount,
                  actions: e.actions ?? [],
                },
              });
              break;
            case 'video_plan':
              if (get().activeConversationId !== snapshotId) break;
              set({
                waitingVideoPlan: {
                  planToken: e.planToken ?? '',
                  message: e.content ?? '',
                  duration: e.duration ?? 6,
                  picUrl: e.picUrl ?? '',
                  actions: e.actions ?? [],
                  models: e.models,
                  recommended: e.recommended,
                  reasons: e.reasons,
                },
              });
              break;
            case 'error':
              set({ streamError: e.message ?? '请求失败' });
              break;
          }
        },
        customText,
        params,
        assetIds,
      );
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '请求失败';
      set({ streamError: msg });
    } finally {
      set({ streaming: false, pendingAssistantId: null, workflowHint: '' });
    }
  },

  submitVideoPlan: async (actionId, params, customText) => {
    const { activeConversationId, waitingVideoPlan } = get();
    if (!activeConversationId || !waitingVideoPlan) return;
    set({ streaming: true, streamError: null, waitingVideoPlan: null });

    const assistantId = `tmp-assistant-${Date.now()}`;
    set((s) => ({ messages: [...s.messages, {
      id: assistantId,
      conversationId: activeConversationId,
      role: 'assistant' as const,
      content: '',
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    }], pendingAssistantId: assistantId }));

    const snapshotId = activeConversationId;

    try {
      await submitForm(
        activeConversationId,
        waitingVideoPlan.planToken,
        '',
        actionId,
        (e: SseEvent) => {
          switch (e.type) {
            case 'message':
              set((s) => ({
                messages: s.messages.map((m) =>
                  m.id === assistantId ? { ...m, content: m.content + (e.content ?? '') } : m),
              }));
              break;
            case 'task_accepted':
              if (get().activeConversationId !== snapshotId) break;
              set({ workflowHint: '视频生成中...' });
              break;
            case 'message_end':
              if (get().activeConversationId !== snapshotId) break;
              if (typeof e.content === 'string' && e.content) {
                set((s) => ({
                  messages: s.messages.map((m) =>
                    m.id === assistantId ? { ...m, content: e.content! } : m),
                }));
              }
              break;
            case 'error':
              set({ streamError: e.message ?? '请求失败' });
              break;
          }
        },
        customText,
        params,
      );
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '请求失败';
      set({ streamError: msg });
    } finally {
      set({ streaming: false, pendingAssistantId: null, workflowHint: '' });
    }
  },

  skipCurrentHITL: () => set({ waitingHumanInput: null, waitingVideoPlan: null }),
  refineAsset: () => {
    const cr = get().confirmResult;
    if (cr?.url) set({ pendingPicUrl: cr.url, confirmResult: null });
  },
  dismissConfirm: () => set({ confirmResult: null }),
  cancelRefine: () => set({ pendingPicUrl: null }),
  resetChatState: () => set({
    messages: [],
    waitingHumanInput: null,
    waitingVideoPlan: null,
    streamError: null,
    confirmResult: null,
    pendingPicUrl: null,
    refImageUrl: null,
    workflowHint: '',
    pendingAssistantId: null,
  }),
}));
