import { create } from 'zustand';
import { projectApi } from '../api/projects';
import type { ProjectResponse, SceneResponse } from '../api/projects';
import { sceneApi } from '../api/scenes';
import { aiApi } from '../api/ai';

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
  setImageModel: (m: string) => void;
  setVideoModel: (m: string) => void;

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
  generateImage: (sceneId: string, prompt: string, model?: string, referenceImages?: string[]) => Promise<string>;
  generateVideo: (sceneId: string, prompt: string, model?: string, referenceImages?: string[]) => Promise<string>;
  setGeneratingImage: (sceneId: string, v: boolean) => void;
  setGeneratingVideo: (sceneId: string, v: boolean) => void;
  addScene: (projectId: string) => Promise<void>;
  deleteScene: (sceneId: string) => Promise<void>;
  markDirty: () => void;
}

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
  imageModel: 'gpt-image-2',
  videoModel: 'veo-3.1-fast',

  loadProjects: async () => {
    set({ isLoading: true });
    const res = await projectApi.list();
    set({ projects: res.data.data || [], isLoading: false });
  },

  createProject: async (name, creationType, aspectRatio) => {
    const res = await projectApi.create({ name, creationType, aspectRatio });
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
    // Only treat as draft if status is 'draft'
    if (draft && draft.status === 'draft') return draft;
    return null;
  },

  selectScene: (sceneId) => set({ selectedSceneId: sceneId }),

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

  generateImage: async (sceneId, prompt, model, referenceImages) => {
    set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: true } }));
    try {
      const res = await aiApi.generateImage({
        sceneId, prompt, model, aspectRatio: '16:9', referenceImages,
      });
      if (get().currentProject) {
        await get().loadProject(get().currentProject!.id);
      }
      get().markDirty();
      return res.data.data.imageUrl;
    } finally {
      set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: false } }));
    }
  },

  generateVideo: async (sceneId, prompt, model, referenceImages) => {
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: true } }));
    try {
      const res = await aiApi.generateVideo({
        sceneId, prompt, model, referenceImages,
      });
      const taskId = res.data.data.taskId;
      // 轮询直到完成
      let attempts = 0;
      const poll = async () => {
        attempts++;
        const statusRes = await aiApi.getTaskStatus(taskId);
        const status = statusRes.data.data;
        if (status.progress) {
          set((s) => ({ videoProgress: { ...s.videoProgress, [sceneId]: parseInt(status.progress!) } }));
        }
        if (status.status === 'completed') {
          if (get().currentProject) await get().loadProject(get().currentProject!.id);
        } else if (status.status === 'failed') {
          // failed, do nothing special
        } else if (attempts < 60) {
          await new Promise(r => setTimeout(r, 5000));
          await poll();
        }
      };
      await poll();
      get().markDirty();
      return taskId;
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
