import { create } from 'zustand';
import { projectApi } from '../api/projects';
import type { ProjectResponse, SceneResponse } from '../api/projects';
import { sceneApi } from '../api/scenes';
import { aiApi } from '../api/ai';
import { DEFAULT_IMAGE_MODEL, DEFAULT_VIDEO_MODEL, DEFAULT_IMAGE_SIZE, DEFAULT_IMAGE_QUALITY, DEFAULT_UNDERSTANDING_MODEL, IMAGE_MODELS, VIDEO_MODELS, UNDERSTANDING_MODELS, type ModelOption } from '../config';
import { storage } from '../utils/storage';

export interface ToastMessage {
  id: string;
  sceneId: string;
  sceneNumber: number;
  type: 'success' | 'error';
  kind: 'image' | 'video';
}

const LAST_PROJECT_KEY = 'lastOpenedProjectId';

function safeParseParams(json: string): ModelOption['params'] {
  try {
    const obj = JSON.parse(json);
    return obj && typeof obj === 'object' ? obj : null;
  } catch {
    return null;
  }
}

interface ProjectState {
  projects: ProjectResponse[];
  currentProject: ProjectResponse | null;
  scenes: SceneResponse[];
  selectedSceneId: string | null;
  isLoading: boolean;
  generatingImage: Record<string, boolean>;
  generatingVideo: Record<string, boolean>;
  videoProgress: Record<string, number>;
  scriptGenerationStatus: 'idle' | 'generating' | 'done' | 'error';
  scriptGenerationMessage: string;
  imageModel: string;
  videoModel: string;
  imageSize: string;
  imageQuality: string;
  imageN: number;
  imageModelOptions: ModelOption[];
  videoModelOptions: ModelOption[];
  understandingModel: string;
  understandingModelOptions: ModelOption[];
  toasts: ToastMessage[];
  addToast: (toast: Omit<ToastMessage, 'id'>) => void;
  dismissToast: (id: string) => void;

  setImageModel: (m: string) => void;
  setVideoModel: (m: string) => void;
  setUnderstandingModel: (m: string) => void;
  setImageSize: (s: string) => void;
  setImageQuality: (q: string) => void;
  setImageN: (n: number) => void;
  fetchAiModels: () => Promise<void>;

  loadProjects: () => Promise<void>;
  loadLastProject: () => Promise<void>;
  createProject: (name: string, creationType: string, aspectRatio: string) => Promise<ProjectResponse>;
  loadProject: (id: string) => Promise<void>;
  updateProject: (id: string, data: Record<string, unknown>) => Promise<void>;
  deleteProject: (id: string) => Promise<void>;
  selectScene: (sceneId: string) => void;
  generateScript: (projectId: string, scriptText: string, creationType: string, aspectRatio: string, model?: string, understandingModel?: string, referenceImages?: string[], assetIds?: string[], mode?: string) => Promise<void>;
  generateImage: (sceneId: string, prompt: string, model?: string, referenceImages?: string[], mode?: string, generatedImageUrl?: string, opts?: { size?: string; quality?: string; n?: number; aspectRatio?: string }) => Promise<string>;
  generateVideo: (sceneId: string, prompt: string, model?: string, referenceImages?: string[], generatedImageUrl?: string, opts?: { aspectRatio?: string; duration?: number; resolution?: string; size?: string }) => Promise<string>;
  pollVideoTask: (sceneId: string, taskId: string) => Promise<void>;
  refreshScenes: () => Promise<void>;
  setGeneratingImage: (sceneId: string, v: boolean) => void;
  setGeneratingVideo: (sceneId: string, v: boolean) => void;
  updateSceneInStore: (sceneId: string, data: Partial<SceneResponse>) => void;
  markDirty: () => void;
}

let toastIdCounter = 0;

export const useProjectStore = create<ProjectState>((set, get) => ({
  projects: [],
  currentProject: null,
  scenes: [],
  selectedSceneId: null,
  isLoading: false,
  generatingImage: {},
  generatingVideo: {},
  videoProgress: {},
  scriptGenerationStatus: 'idle',
  scriptGenerationMessage: '',
  imageModel: DEFAULT_IMAGE_MODEL,
  videoModel: DEFAULT_VIDEO_MODEL,
  imageSize: DEFAULT_IMAGE_SIZE,
  imageQuality: DEFAULT_IMAGE_QUALITY,
  imageN: 1,
  imageModelOptions: IMAGE_MODELS.map((m) => ({ value: m.value, label: m.label })),
  videoModelOptions: VIDEO_MODELS.map((m) => ({ value: m.value, label: m.label })),
  understandingModel: DEFAULT_UNDERSTANDING_MODEL,
  understandingModelOptions: UNDERSTANDING_MODELS.map((m) => ({ value: m.value, label: m.label })),
  toasts: [],

  addToast: (toast) => {
    const id = `toast-${++toastIdCounter}-${Date.now()}`;
    set((s) => ({ toasts: [...s.toasts, { ...toast, id }] }));
    setTimeout(() => get().dismissToast(id), 3000);
  },
  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),

  setImageModel: (m) => set({ imageModel: m }),
  setVideoModel: (m) => set({ videoModel: m }),
  setUnderstandingModel: (m) => set({ understandingModel: m }),
  setImageSize: (s) => set({ imageSize: s }),
  setImageQuality: (q) => set({ imageQuality: q }),
  setImageN: (n) => set({ imageN: n }),

  fetchAiModels: async () => {
    try {
      const res = await aiApi.aiModels();
      const imageModels = res.data.data?.imageModels ?? [];
      const videoModels = res.data.data?.videoModels ?? [];
      const understandingModels = res.data.data?.understandingModels ?? [];
      const parsed = (ms: { value: string; label: string; params?: string | null }[]) =>
        ms.map((m) => ({ ...m, params: m.params ? safeParseParams(m.params) : null }));
      set((s) => ({
        imageModelOptions: parsed(imageModels).length ? parsed(imageModels) : s.imageModelOptions,
        videoModelOptions: parsed(videoModels).length ? parsed(videoModels) : s.videoModelOptions,
        understandingModelOptions: parsed(understandingModels).length ? parsed(understandingModels) : s.understandingModelOptions,
      }));
    } catch { /* 静默 */ }
  },

  loadProjects: async () => {
    set({ isLoading: true });
    const res = await projectApi.list();
    set({ projects: res.data.data || [], isLoading: false });
  },

  loadLastProject: async () => {
    if (get().currentProject) return;
    try {
      const lastId = await storage.getItem(LAST_PROJECT_KEY);
      if (lastId && get().projects.some((p) => p.id === lastId)) {
        await get().loadProject(lastId);
        return;
      }
      // 兜底：无记录或已失效时，取最近更新的项目（列表按 updated_at DESC，首项即最近）
      if (get().projects.length === 0) await get().loadProjects();
      const first = get().projects[0];
      if (first) await get().loadProject(first.id);
    } catch { /* 静默 */ }
  },

  createProject: async (name, creationType, aspectRatio) => {
    const res = await projectApi.create({ name, creationType, aspectRatio, status: 'draft' });
    const project = res.data.data;
    set((s) => ({ projects: [project, ...s.projects] }));
    return project;
  },

  loadProject: async (id) => {
    set({ isLoading: true });
    const res = await projectApi.get(id);
    const project = res.data.data;
    set({
      currentProject: project,
      scenes: project.scenes || [],
      selectedSceneId: null,
      isLoading: false,
    });
    try { await storage.setItem(LAST_PROJECT_KEY, id); } catch { /* 忽略 */ }
    // 拉取网关模型路由（图片/视频/理解模型），失败静默
    void get().fetchAiModels();
  },

  updateProject: async (id, data) => {
    const res = await projectApi.update(id, data);
    const updated = res.data.data;
    set((s) => ({
      currentProject: s.currentProject?.id === id ? updated : s.currentProject,
      projects: s.projects.map(p => p.id === id ? updated : p),
    }));
  },

  deleteProject: async (id) => {
    await projectApi.delete(id);
    set((s) => ({
      projects: s.projects.filter((p) => p.id !== id),
      currentProject: s.currentProject?.id === id ? null : s.currentProject,
    }));
  },

  selectScene: (sceneId) => set({ selectedSceneId: sceneId }),

  generateScript: async (projectId, scriptText, creationType, aspectRatio, model, understandingModel, referenceImages, assetIds, mode) => {
    set({ isLoading: true, scriptGenerationStatus: 'generating', scriptGenerationMessage: '正在连接 AI...' });
    try {
      await aiApi.generateScript({ projectId, scriptText, creationType, aspectRatio, model, understandingModel, referenceImages, assetIds, mode });
      set({ scriptGenerationMessage: 'AI 正在分析剧本...' });
      await get().loadProject(projectId);
      set({ isLoading: false, scriptGenerationStatus: 'done', scriptGenerationMessage: '分镜生成完成' });
      get().markDirty();
      setTimeout(() => {
        if (get().scriptGenerationStatus === 'done') set({ scriptGenerationStatus: 'idle', scriptGenerationMessage: '' });
      }, 4000);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : '分镜生成失败';
      set({ isLoading: false, scriptGenerationStatus: 'error', scriptGenerationMessage: message });
    }
  },

  generateImage: async (sceneId, prompt, model, referenceImages, mode, generatedImageUrl, opts) => {
    set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: true } }));
    try {
      const { imageSize, imageQuality, imageN } = get();
      const size = opts?.size ?? imageSize;
      const quality = opts?.quality ?? imageQuality;
      const n = opts?.n ?? imageN;
      const res = await aiApi.generateImage({ sceneId, prompt, model, referenceImages, mode, generatedImageUrl, size, quality, n, aspectRatio: opts?.aspectRatio });
      if (get().currentProject) await get().loadProject(get().currentProject!.id);
      get().markDirty();
      const scene = get().scenes.find(s => s.id === sceneId);
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'success', kind: 'image' });
      return res.data.data.imageUrl;
    } catch (err: unknown) {
      const scene = get().scenes.find(s => s.id === sceneId);
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'error', kind: 'image' });
      throw err;
    } finally {
      set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: false } }));
    }
  },

  generateVideo: async (sceneId, prompt, model, referenceImages, generatedImageUrl, opts) => {
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: true } }));
    try {
      const res = await aiApi.generateVideo({ sceneId, prompt, model, referenceImages, generatedImageUrl, aspectRatio: opts?.aspectRatio ?? '16:9', duration: opts?.duration ?? 8, resolution: opts?.resolution, size: opts?.size });
      const taskId = res.data.data.taskId;
      await get().pollVideoTask(sceneId, taskId);
      return taskId;
    } catch (err: unknown) {
      const scene = get().scenes.find(s => s.id === sceneId);
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'error', kind: 'video' });
      throw err;
    } finally {
      set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: false }, videoProgress: { ...s.videoProgress, [sceneId]: 0 } }));
    }
  },

  pollVideoTask: async (sceneId, taskId) => {
    let attempts = 0;
    const poll = async () => {
      attempts++;
      const statusRes = await aiApi.getTaskStatus(taskId);
      const status = statusRes.data.data;
      if (status.progress) set((s) => ({ videoProgress: { ...s.videoProgress, [sceneId]: parseInt(status.progress!) } }));
      if (status.status === 'completed') {
        if (get().currentProject) await get().loadProject(get().currentProject!.id);
        const scene = get().scenes.find(s => s.id === sceneId);
        get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'success', kind: 'video' });
      } else if (status.status === 'failed') {
        const scene = get().scenes.find(s => s.id === sceneId);
        get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'error', kind: 'video' });
      } else if (attempts < 120) {
        await new Promise(r => setTimeout(r, 5000));
        await poll();
      }
    };
    await poll();
    get().markDirty();
  },

  refreshScenes: async () => {
    const { currentProject } = get();
    if (!currentProject) return;
    const res = await projectApi.get(currentProject.id);
    const project = res.data.data;
    set(() => ({ currentProject: project, scenes: project.scenes || [] }));
  },

  setGeneratingImage: (sceneId, v) => set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: v } })),
  setGeneratingVideo: (sceneId, v) => set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: v } })),

  updateSceneInStore: (sceneId, data) => set((s) => ({
    scenes: s.scenes.map((sc) => sc.id === sceneId ? { ...sc, ...data } : sc),
  })),

  markDirty: () => {
    const { currentProject, updateProject } = get();
    if (currentProject && currentProject.status === 'active') {
      updateProject(currentProject.id, { status: 'draft' });
    }
  },
}));
