import { create } from 'zustand';
import { projectApi } from '../api/projects';
import type { ProjectResponse, SceneResponse } from '../api/projects';
import { sceneApi } from '../api/scenes';
import { aiApi } from '../api/ai';
import { DEFAULT_IMAGE_MODEL, DEFAULT_VIDEO_MODEL, VIDEO_PRESETS, DEFAULT_VIDEO_PRESET, DEFAULT_IMAGE_SIZE, DEFAULT_IMAGE_QUALITY } from '../config';

// 生成完成后的 toast 通知
export interface ToastMessage {
  id: string;
  sceneId: string;
  sceneNumber: number;
  type: 'success' | 'error';
  kind: 'image' | 'video';
}

interface ProjectState {
  projects: ProjectResponse[];
  currentProject: ProjectResponse | null;
  scenes: SceneResponse[];
  selectedSceneId: string | null;
  isLoading: boolean;
  generatingImage: Record<string, boolean>;
  generatingVideo: Record<string, boolean>;
  videoProgress: Record<string, number>; // sceneId -> progress (0-100)
  scriptGenerationStatus: 'idle' | 'generating' | 'done' | 'error';
  scriptGenerationMessage: string;
  imageModel: string;
  videoModel: string;
  videoPreset: string;
  imageSize: string;
  imageQuality: string;

  // toast 通知
  toasts: ToastMessage[];
  addToast: (toast: Omit<ToastMessage, 'id'>) => void;
  dismissToast: (id: string) => void;

  // 未读提示（新生成完成的分镜）
  unreadScenes: Set<string>;
  markSceneRead: (sceneId: string) => void;

  setImageModel: (m: string) => void;
  setVideoModel: (m: string) => void;
  setVideoPreset: (p: string) => void;
  setImageSize: (s: string) => void;
  setImageQuality: (q: string) => void;

  // scene ref state — stored as JSON in soundDesign field
  getSceneRefs: (sceneId: string) => { images: string[]; useForImage: boolean; useForVideo: boolean };
  setSceneRefs: (sceneId: string, refs: { images: string[]; useForImage: boolean; useForVideo: boolean }) => Promise<void>;

  loadProjects: () => Promise<void>;
  createProject: (name: string, creationType: string, aspectRatio: string) => Promise<ProjectResponse>;
  loadProject: (id: string) => Promise<void>;
  updateProject: (id: string, data: Record<string, unknown>) => Promise<void>;
  deleteProject: (id: string) => Promise<void>;
  checkDraft: () => Promise<ProjectResponse | null>;
  selectScene: (sceneId: string) => void;
  generateScript: (projectId: string, scriptText: string, creationType: string, aspectRatio: string, model?: string) => Promise<void>;
  generateImage: (sceneId: string, prompt: string, model?: string, referenceImages?: string[], mode?: string, generatedImageUrl?: string) => Promise<string>;
  generateVideo: (sceneId: string, prompt: string, model?: string, referenceImages?: string[], generatedImageUrl?: string) => Promise<string>;
  setGeneratingImage: (sceneId: string, v: boolean) => void;
  setGeneratingVideo: (sceneId: string, v: boolean) => void;
  addScene: (projectId: string) => Promise<void>;
  deleteScene: (sceneId: string) => Promise<void>;
  markDirty: () => void;
  updateSceneInStore: (sceneId: string, data: Record<string, unknown>) => void;
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
  videoPreset: DEFAULT_VIDEO_PRESET,
  imageSize: DEFAULT_IMAGE_SIZE,
  imageQuality: DEFAULT_IMAGE_QUALITY,
  toasts: [],
  unreadScenes: new Set<string>(),

  addToast: (toast) => {
    const id = `toast-${++toastIdCounter}-${Date.now()}`;
    set((s) => ({
      toasts: [...s.toasts, { ...toast, id }],
      unreadScenes: new Set([...s.unreadScenes, toast.sceneId]),
    }));
    // 3秒后自动消失
    setTimeout(() => get().dismissToast(id), 3000);
  },

  dismissToast: (id) =>
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),

  markSceneRead: (sceneId) =>
    set((s) => {
      if (!s.unreadScenes.has(sceneId)) return s;
      const next = new Set(s.unreadScenes);
      next.delete(sceneId);
      return { unreadScenes: next };
    }),

  loadProjects: async () => {
    set({ isLoading: true });
    const res = await projectApi.list();
    set({ projects: res.data.data || [], isLoading: false });
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

  checkDraft: async () => {
    const res = await projectApi.getDraft();
    const draft = res.data.data;
    if (draft && draft.status === 'draft') return draft;
    return null;
  },

  selectScene: (sceneId) => {
    set({ selectedSceneId: sceneId });
    get().markSceneRead(sceneId);
  },

  generateScript: async (projectId, scriptText, creationType, aspectRatio, model) => {
    set({
      isLoading: true,
      scriptGenerationStatus: 'generating',
      scriptGenerationMessage: '正在连接 AI...',
    });
    try {
      await aiApi.generateScript({ projectId, scriptText, creationType, aspectRatio, model });
      set({
        scriptGenerationStatus: 'generating',
        scriptGenerationMessage: 'AI 正在分析剧本...',
      });
      await get().loadProject(projectId);
      set({
        isLoading: false,
        scriptGenerationStatus: 'done',
        scriptGenerationMessage: '分镜生成完成',
      });
      get().markDirty();
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : '分镜生成失败，请重试';
      set({
        isLoading: false,
        scriptGenerationStatus: 'error',
        scriptGenerationMessage: message,
      });
    }
  },

  generateImage: async (sceneId, prompt, model, referenceImages, mode, generatedImageUrl) => {
    set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: true } }));
    try {
      const { imageSize, imageQuality } = get();
      const res = await aiApi.generateImage({
        sceneId, prompt, model, referenceImages, mode, generatedImageUrl,
        size: imageSize,
        quality: imageQuality,
      });
      if (get().currentProject) {
        await get().loadProject(get().currentProject!.id);
      }
      get().markDirty();
      // 生成成功 → toast + 未读
      const scene = get().scenes.find(s => s.id === sceneId);
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'success', kind: 'image' });
      return res.data.data.imageUrl;
    } catch (err: unknown) {
      // 生成失败 → toast + 未读
      const scene = get().scenes.find(s => s.id === sceneId);
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'error', kind: 'image' });
      throw err;
    } finally {
      set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: false } }));
    }
  },

  generateVideo: async (sceneId, prompt, model, referenceImages, generatedImageUrl) => {
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: true } }));
    try {
      const preset = VIDEO_PRESETS.find(p => p.value === get().videoPreset) || VIDEO_PRESETS.find(p => p.value === DEFAULT_VIDEO_PRESET)!;
      const res = await aiApi.generateVideo({
        sceneId, prompt, model, referenceImages, generatedImageUrl,
        resolution: preset.resolution,
        size: preset.size,
        aspectRatio: preset.aspectRatio,
        duration: parseInt(preset.duration),
      });
      const taskId = res.data.data.taskId;
      let videoFailed = false;
      let videoDone = false;
      // 轮询直到完成（上限 120 次 × 5s = 10 分钟：MiniMax 生成 4~15s 视频实测可达 5-10 分钟，
      // 原 60 次上限（5 分钟）会提前放弃——前端停止轮询后任务无人接管，UI 卡在生成中）
      let attempts = 0;
      const poll = async () => {
        attempts++;
        const statusRes = await aiApi.getTaskStatus(taskId);
        const status = statusRes.data.data;
        if (status.progress) {
          set((s) => ({ videoProgress: { ...s.videoProgress, [sceneId]: parseInt(status.progress!) } }));
        }
        if (status.status === 'completed') {
          videoDone = true;
          if (get().currentProject) await get().loadProject(get().currentProject!.id);
        } else if (status.status === 'failed') {
          videoFailed = true;
        } else if (attempts < 120) {
          await new Promise(r => setTimeout(r, 5000));
          await poll();
        }
      };
      await poll();
      get().markDirty();
      // 生成完成 → toast + 未读（仅在确认终态时发成功/失败；超时未完成不发成功，避免假成功）
      const scene = get().scenes.find(s => s.id === sceneId);
      if (videoFailed) {
        get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'error', kind: 'video' });
      } else if (videoDone) {
        get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'success', kind: 'video' });
      }
      return taskId;
    } catch (err: unknown) {
      const scene = get().scenes.find(s => s.id === sceneId);
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'error', kind: 'video' });
      throw err;
    } finally {
      set((s) => ({ 
        generatingVideo: { ...s.generatingVideo, [sceneId]: false },
        videoProgress: { ...s.videoProgress, [sceneId]: 0 },
      }));
    }
  },

  setGeneratingImage: (sceneId, v) =>
    set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: v } })),
  setGeneratingVideo: (sceneId, v) =>
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: v } })),

  setImageModel: (m) => set({ imageModel: m }),
  setVideoModel: (m) => set({ videoModel: m }),
  setVideoPreset: (p) => set({ videoPreset: p }),
  setImageSize: (s) => set({ imageSize: s }),
  setImageQuality: (q) => set({ imageQuality: q }),

  markDirty: () => {
    const { currentProject, updateProject } = get();
    if (currentProject && currentProject.status === 'active') {
      updateProject(currentProject.id, { status: 'draft' });
    }
  },

  // scene ref state — stored as JSON in soundDesign field
  getSceneRefs: (sceneId) => {
    const scene = get().scenes.find(s => s.id === sceneId);
    if (!scene || !scene.soundDesign) return { images: [], useForImage: true, useForVideo: true };
    try { return JSON.parse(scene.soundDesign); } catch { return { images: [], useForImage: true, useForVideo: true }; }
  },
  setSceneRefs: async (sceneId, refs) => {
    await sceneApi.update(sceneId, { soundDesign: JSON.stringify(refs) });
    set((s) => ({ scenes: s.scenes.map(sc => sc.id === sceneId ? { ...sc, soundDesign: JSON.stringify(refs) } : sc) }));
  },

  updateSceneInStore: (sceneId: string, data: Record<string, unknown>) =>
    set((s) => ({ scenes: s.scenes.map(sc => sc.id === sceneId ? { ...sc, ...data } : sc) })),

  addScene: async (projectId) => {
    await sceneApi.add(projectId, { scriptContent: '' });
    if (get().currentProject?.id === projectId) {
      await get().loadProject(projectId);
    }
    get().markDirty();
  },

  deleteScene: async (sceneId) => {
    await sceneApi.delete(sceneId);
    if (get().currentProject) {
      await get().loadProject(get().currentProject!.id);
    }
    get().markDirty();
  },
}));
