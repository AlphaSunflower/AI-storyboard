import { create } from 'zustand';
import { projectApi } from '../api/projects';
import type { ProjectResponse, SceneReferenceAsset, SceneResponse } from '../api/projects';
import { sceneApi } from '../api/scenes';
import { aiApi } from '../api/ai';
import { DEFAULT_IMAGE_MODEL, DEFAULT_VIDEO_MODEL, DEFAULT_VIDEO_PRESET, DEFAULT_IMAGE_SIZE, DEFAULT_IMAGE_QUALITY, DEFAULT_UNDERSTANDING_MODEL, IMAGE_MODELS, VIDEO_MODELS, UNDERSTANDING_MODELS, type ModelOption } from '../config';
import { resolveVideoPreset } from '../components/common/VideoPresetSelector';

// 生成完成后的 toast 通知
export interface ToastMessage {
  id: string;
  sceneId: string;
  sceneNumber: number;
  type: 'success' | 'error';
  kind: 'image' | 'video';
}

/** 解析网关下发的 params JSON 字符串为参数对象（解析失败/非对象返回 null，前端回退静态默认） */
function safeParseParams(json: string): ModelOption['params'] {
  try {
    const obj = JSON.parse(json);
    return obj && typeof obj === 'object' ? (obj as ModelOption['params']) : null;
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
  videoProgress: Record<string, number>; // sceneId -> progress (0-100)
  scriptGenerationStatus: 'idle' | 'generating' | 'done' | 'error';
  scriptGenerationMessage: string;
  imageModel: string;
  videoModel: string;
  videoPreset: string;
  imageSize: string;
  imageQuality: string;
  // 生图数量（默认 1；由生图面板 n 控件调节）
  imageN: number;
  // 模型下拉选项：初始为静态默认，fetchAiModels 拉取网关后替换（网关无返回则保持默认）
  imageModelOptions: ModelOption[];
  videoModelOptions: ModelOption[];
  // 理解模型（vision 类型）：剧本输入上传参考图时先看图生成描述
  understandingModel: string;
  understandingModelOptions: ModelOption[];

  // toast 通知
  toasts: ToastMessage[];
  addToast: (toast: Omit<ToastMessage, 'id'>) => void;
  dismissToast: (id: string) => void;

  // 未读提示（新生成完成的分镜）
  unreadScenes: Set<string>;
  markSceneRead: (sceneId: string) => void;

  setImageModel: (m: string) => void;
  setVideoModel: (m: string) => void;
  setUnderstandingModel: (m: string) => void;
  setVideoPreset: (p: string) => void;
  setImageSize: (s: string) => void;
  setImageQuality: (q: string) => void;
  setImageN: (n: number) => void;
  /** 从网关拉取生图/生视频模型列表（编辑器挂载时调用；失败静默保持默认） */
  fetchAiModels: () => Promise<void>;

  // ── 分镜参考素材（后端 scene_reference_images 表持久化，替代原 soundDesign JSON 方案）──
  sceneRefs: Record<string, SceneReferenceAsset[]>;
  fetchSceneRefs: (sceneId: string) => Promise<void>;
  uploadSceneRef: (sceneId: string, type: 'image' | 'video' | 'audio', file: File) => Promise<void>;
  deleteSceneRef: (sceneId: string, refId: string) => Promise<void>;

  // ── 分镜生成参数覆盖（全局默认 + 分镜覆盖：空串/0 = 清覆盖回退全局默认）──
  setSceneParams: (sceneId: string, params: Record<string, unknown>) => Promise<void>;
  clearSceneParams: (sceneId: string) => Promise<void>;

  loadProjects: () => Promise<void>;
  createProject: (name: string, creationType: string, aspectRatio: string) => Promise<ProjectResponse>;
  loadProject: (id: string) => Promise<void>;
  updateProject: (id: string, data: Record<string, unknown>) => Promise<void>;
  deleteProject: (id: string) => Promise<void>;
  checkDraft: () => Promise<ProjectResponse | null>;
  selectScene: (sceneId: string) => void;
  generateScript: (projectId: string, scriptText: string, creationType: string, aspectRatio: string, model?: string, understandingModel?: string, referenceImages?: string[]) => Promise<void>;
  generateImage: (sceneId: string, prompt: string, model?: string, referenceImages?: string[], mode?: string, generatedImageUrl?: string) => Promise<string>;
  generateVideo: (sceneId: string, prompt: string, model?: string, referenceImages?: string[], generatedImageUrl?: string, referenceVideos?: string[], referenceAudios?: string[]) => Promise<string>;
  /** 轮询单个视频任务直到终态（复用：新生成 + 刷新/重登后恢复）；不管理 generatingVideo 标志，由调用方负责 */
  pollVideoTask: (sceneId: string, taskId: string) => Promise<void>;
  /** 加载分镜后恢复仍在生成中的视频轮询（刷新/重登后不丢任务，避免重复生成新任务） */
  resumePendingVideos: () => void;
  /** 重载分镜列表但保留当前选中（图片生成恢复轮询用，不重置 selectedSceneId） */
  refreshScenes: () => Promise<void>;
  /** 加载分镜后恢复仍在生成中的图片轮询（同步生图在客户端刷新后后端仍会完成并落库，前端需周期重载拾取结果） */
  resumePendingImages: () => void;
  setGeneratingImage: (sceneId: string, v: boolean) => void;
  setGeneratingVideo: (sceneId: string, v: boolean) => void;
  addScene: (projectId: string) => Promise<void>;
  deleteScene: (sceneId: string) => Promise<void>;
  markDirty: () => void;
  updateSceneInStore: (sceneId: string, data: Record<string, unknown>) => void;
}

let toastIdCounter = 0;
// 图片生成恢复轮询定时器（模块级，保证全局仅一个，避免重复启动）
let imageResumeTimer: ReturnType<typeof setInterval> | null = null;

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
  imageN: 1,
  // 初始 = 静态默认（as const 需展开为可变 ModelOption[]，不带 params）；网关拉取成功且非空时替换
  imageModelOptions: IMAGE_MODELS.map((m) => ({ value: m.value, label: m.label })),
  videoModelOptions: VIDEO_MODELS.map((m) => ({ value: m.value, label: m.label })),
  understandingModel: DEFAULT_UNDERSTANDING_MODEL,
  understandingModelOptions: UNDERSTANDING_MODELS.map((m) => ({ value: m.value, label: m.label })),
  sceneRefs: {},
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
    // 恢复刷新/重登前仍在生成中的视频任务（续接轮询，不重复生成新任务）
    get().resumePendingVideos();
    // 同上：恢复仍在生成中的图片任务
    get().resumePendingImages();
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

  generateScript: async (projectId, scriptText, creationType, aspectRatio, model, understandingModel, referenceImages) => {
    set({
      isLoading: true,
      scriptGenerationStatus: 'generating',
      scriptGenerationMessage: '正在连接 AI...',
    });
    try {
      await aiApi.generateScript({ projectId, scriptText, creationType, aspectRatio, model, understandingModel, referenceImages });
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
      // 完成提示展示几秒后自动消失（否则顶部「✓ 分镜生成完成」常驻；复位前校验状态，
      // 避免覆盖用户 4 秒内发起的新一轮生成）
      setTimeout(() => {
        if (get().scriptGenerationStatus === 'done') {
          set({ scriptGenerationStatus: 'idle', scriptGenerationMessage: '' });
        }
      }, 4000);
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
      const { imageSize, imageQuality, imageN } = get();
      // 分镜覆盖参数优先（空串/0 = 未覆盖，回退全局默认）
      const p = get().scenes.find(s => s.id === sceneId) ?? ({} as SceneResponse);
      const res = await aiApi.generateImage({
        sceneId, prompt,
        model: p.imageModel || model,
        referenceImages, mode, generatedImageUrl,
        size: p.imageSize || imageSize,
        quality: p.imageQuality || imageQuality,
        n: p.imageN || imageN,
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

  generateVideo: async (sceneId, prompt, model, referenceImages, generatedImageUrl, referenceVideos, referenceAudios) => {
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: true } }));
    try {
      // 解析当前 preset（静态 VIDEO_PRESETS 或按模型 params 动态组合的 `${d}s-${a}`）
      const preset = resolveVideoPreset(get().videoPreset);
      // 分镜覆盖参数优先（videoModel/videoAspectRatio/videoResolution/duration 覆盖全局）
      const p = get().scenes.find(s => s.id === sceneId) ?? ({} as SceneResponse);
      const res = await aiApi.generateVideo({
        sceneId, prompt, model: p.videoModel || model, referenceImages, generatedImageUrl,
        referenceVideos, referenceAudios,
        resolution: p.videoResolution || undefined,
        aspectRatio: p.videoAspectRatio || preset.aspectRatio,
        duration: p.duration || parseInt(preset.duration),
      });
      const taskId = res.data.data.taskId;
      await get().pollVideoTask(sceneId, taskId);
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

  /** 轮询单个视频任务直到终态（进度/完成刷新/toast；不管理 generatingVideo 标志，由调用方负责） */
  pollVideoTask: async (sceneId, taskId) => {
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
    // 终态 → toast + 未读（超时未完成不发成功，避免假成功）
    const scene = get().scenes.find(s => s.id === sceneId);
    if (videoFailed) {
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'error', kind: 'video' });
    } else if (videoDone) {
      get().addToast({ sceneId, sceneNumber: scene?.sceneNumber ?? 0, type: 'success', kind: 'video' });
    }
  },

  /** 加载分镜后：恢复仍在生成中的视频轮询（刷新/重登后不丢任务，避免重复生成新任务） */
  resumePendingVideos: () => {
    const { scenes, generatingVideo } = get();
    scenes.forEach((scene) => {
      if (scene.videoStatus === 'generating' && scene.videoTaskId && !generatingVideo[scene.id]) {
        set((s) => ({ generatingVideo: { ...s.generatingVideo, [scene.id]: true } }));
        get().pollVideoTask(scene.id, scene.videoTaskId)
          .catch(() => {
            const sc = get().scenes.find(s => s.id === scene.id);
            get().addToast({ sceneId: scene.id, sceneNumber: sc?.sceneNumber ?? 0, type: 'error', kind: 'video' });
          })
          .finally(() => {
            set((s) => ({ generatingVideo: { ...s.generatingVideo, [scene.id]: false } }));
          });
      }
    });
  },

  /** 重载分镜列表但保留当前选中（图片生成恢复轮询用） */
  refreshScenes: async () => {
    const { currentProject } = get();
    if (!currentProject) return;
    const res = await projectApi.get(currentProject.id);
    const project = res.data.data;
    set(() => ({ currentProject: project, scenes: project.scenes || [] }));
  },

  /**
   * 加载分镜后：恢复仍在生成中的图片轮询。
   * 图片生成为同步 POST（后端在请求线程内完成并落库 imageStatus=completed/failed），
   * 客户端刷新/重登会中断请求但后端仍会完成——故只需周期重载拾取结果，无需重新生成。
   */
  resumePendingImages: () => {
    const pending = get().scenes.filter(s => s.imageStatus === 'generating').map(s => s.id);
    if (!pending.length) return;
    // 重建生成中标志（刷新后内存态丢失，需重新禁用「生成图片」按钮/显示进度）
    set((s) => {
      const flags = { ...s.generatingImage };
      pending.forEach(id => { flags[id] = true; });
      return { generatingImage: flags };
    });
    if (imageResumeTimer) return; // 全局仅一个轮询定时器
    imageResumeTimer = setInterval(async () => {
      const { currentProject, scenes } = get();
      const stillGenerating = scenes.some(s => s.imageStatus === 'generating');
      if (!currentProject || !stillGenerating) {
        if (imageResumeTimer) { clearInterval(imageResumeTimer); imageResumeTimer = null; }
        // 仅清本恢复轮次涉及的场景标志，不碰其他场景
        set((s) => {
          const flags = { ...s.generatingImage };
          pending.forEach(id => { flags[id] = false; });
          return { generatingImage: flags };
        });
        return;
      }
      await get().refreshScenes();
    }, 3000);
    // ponytail: 10 分钟硬上限，防后端异常导致 imageStatus 卡 generating 时无限轮询；到时停止（按钮恢复可手动重试）
    setTimeout(() => {
      if (imageResumeTimer) { clearInterval(imageResumeTimer); imageResumeTimer = null; }
    }, 600_000);
  },

  setGeneratingImage: (sceneId, v) =>
    set((s) => ({ generatingImage: { ...s.generatingImage, [sceneId]: v } })),
  setGeneratingVideo: (sceneId, v) =>
    set((s) => ({ generatingVideo: { ...s.generatingVideo, [sceneId]: v } })),

  setImageModel: (m) => set({ imageModel: m }),
  setVideoModel: (m) => set({ videoModel: m }),
  setUnderstandingModel: (m) => set({ understandingModel: m }),
  setVideoPreset: (p) => set({ videoPreset: p }),
  setImageSize: (s) => set({ imageSize: s }),
  setImageQuality: (q) => set({ imageQuality: q }),
  setImageN: (n) => set({ imageN: n }),
  // 从网关拉取生图/生视频模型列表：网关返回空数组/失败时保持静态默认（静默，不打扰用户）
  fetchAiModels: async () => {
    try {
      const res = await aiApi.aiModels();
      const imageModels = res.data.data?.imageModels ?? [];
      const videoModels = res.data.data?.videoModels ?? [];
      const understandingModels = res.data.data?.understandingModels ?? [];
      // 网关下发的 params 是 JSON 字符串 → 解析为参数对象（解析失败置 null，前端回退静态默认）
      const parsedImageModels: ModelOption[] = imageModels.map((m) => ({
        ...m,
        params: m.params ? safeParseParams(m.params) : null,
      }));
      const parsedVideoModels: ModelOption[] = videoModels.map((m) => ({
        ...m,
        params: m.params ? safeParseParams(m.params) : null,
      }));
      const parsedUnderstandingModels: ModelOption[] = understandingModels.map((m) => ({
        ...m,
        params: m.params ? safeParseParams(m.params) : null,
      }));
      set((s) => ({
        imageModelOptions: parsedImageModels.length ? parsedImageModels : s.imageModelOptions,
        videoModelOptions: parsedVideoModels.length ? parsedVideoModels : s.videoModelOptions,
        understandingModelOptions: parsedUnderstandingModels.length ? parsedUnderstandingModels : s.understandingModelOptions,
      }));
    } catch {
      // 网关不可达：保持静态默认，模型选择不中断
    }
  },

  markDirty: () => {
    const { currentProject, updateProject } = get();
    if (currentProject && currentProject.status === 'active') {
      updateProject(currentProject.id, { status: 'draft' });
    }
  },

  // ── 分镜参考素材（后端持久化；替代原 soundDesign JSON 方案）──
  fetchSceneRefs: async (sceneId) => {
    try {
      const res = await sceneApi.listReferences(sceneId);
      set((s) => ({ sceneRefs: { ...s.sceneRefs, [sceneId]: res.data.data || [] } }));
    } catch {
      // 素材列表拉取失败不阻断页面（预览面板显示空）
    }
  },
  uploadSceneRef: async (sceneId, type, file) => {
    const res = await sceneApi.uploadReference(sceneId, type, file);
    const created = res.data.data;
    set((s) => ({ sceneRefs: { ...s.sceneRefs, [sceneId]: [...(s.sceneRefs[sceneId] || []), created] } }));
  },
  deleteSceneRef: async (sceneId, refId) => {
    await sceneApi.deleteReference(refId);
    set((s) => ({ sceneRefs: { ...s.sceneRefs, [sceneId]: (s.sceneRefs[sceneId] || []).filter(r => r.id !== refId) } }));
  },

  // ── 分镜生成参数覆盖（空串/0 = 清覆盖回退全局默认；后端 null=不修改，故清覆盖传空串/0）──
  setSceneParams: async (sceneId, params) => {
    const body: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined || v === null || v === '') {
        // 清覆盖：字符串字段置空串，数值字段置 0
        body[k] = k === 'imageN' || k === 'duration' ? 0 : '';
      } else {
        body[k] = v;
      }
    }
    const res = await sceneApi.update(sceneId, body);
    set((s) => ({ scenes: s.scenes.map(sc => sc.id === sceneId ? res.data.data : sc) }));
  },
  clearSceneParams: async (sceneId) => {
    const res = await sceneApi.update(sceneId, {
      imageModel: '', imageSize: '', imageQuality: '', imageN: 0,
      videoModel: '', videoAspectRatio: '', videoResolution: '', duration: 0,
    });
    set((s) => ({ scenes: s.scenes.map(sc => sc.id === sceneId ? res.data.data : sc) }));
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
