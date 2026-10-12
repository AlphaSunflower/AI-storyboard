import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl, REFERENCE_LIMITS } from '../../config';
import { getSceneImageUrls } from '../../lib/sceneImages';
import { ImagePreviewModal } from '../agent/ImagePreviewModal';
import { ReferenceUploader } from './ReferenceUploader';
import { RefineImageModal } from './RefineImageModal';
import { ContextMenu } from '../common/ContextMenu';
import ElasticSlider from '../ElasticSlider';
import DepthCarousel from '../DepthCarousel';
import SpecularButton from '../SpecularButton';
import { resolveVideoPreset } from '../common/VideoPresetSelector';
import { IMAGE_SIZES, IMAGE_QUALITIES, isSeedreamImageModel } from '../../config';
import { sceneApi, type SceneAssetGroupBinding } from '../../api/scenes';
import { AssetGroupPickerModal } from '../library/AssetGroupPickerModal';
import { ProjectLibraryPickerModal } from '../library/ProjectLibraryPickerModal';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';
import { Eye, Download, Sparkles, Library, LoaderCircle } from 'lucide-react';
import { importMediaToProjectLibrary } from '../../lib/projectLibraryImport';
import { notify } from '../../stores/toastStore';
import { Tooltip } from '../common/Tooltip';

function downloadAsset(url: string, filename: string, options?: { silent?: boolean }) {
  fetch(url)
    .then(r => r.blob())
    .then(blob => {
      const u = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = u;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(u);
      if (!options?.silent) notify({ title: '已开始下载', description: filename, variant: 'info' });
    })
    .catch(() => window.open(url, '_blank'));
}

type PreviewTab = 'image' | 'video';
type EditableSceneField = 'scriptContent' | 'imagePrompt' | 'videoPrompt';
type SceneTextDraft = Record<EditableSceneField, string>;
type FieldSaveState = 'idle' | 'saving' | 'saved' | 'error';

const EMPTY_SCENE_TEXT: SceneTextDraft = {
  scriptContent: '',
  imagePrompt: '',
  videoPrompt: '',
};

const selectStyle: React.CSSProperties = {
  padding: '4px 8px',
  borderRadius: 6,
  border: '1px solid var(--color-hairline)',
  fontSize: 12,
  background: 'white',
  color: 'var(--color-ink)',
  outline: 'none',
  cursor: 'pointer',
};

const fieldRow: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  marginBottom: 6,
};

const fieldLabel: React.CSSProperties = {
  color: 'var(--color-muted)',
  width: 56,
  flexShrink: 0,
  fontSize: 11,
};

const btnGhost: React.CSSProperties = {
  padding: '7px 18px',
  fontSize: 12,
  borderRadius: 'var(--rounded-sm)',
  border: '1px solid var(--color-primary)',
  background: 'transparent',
  color: 'var(--color-primary)',
  cursor: 'pointer',
  fontWeight: 600,
};

const sceneTextareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 96,
  padding: '9px 10px',
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)',
  outline: 'none',
  background: 'var(--color-surface)',
  color: 'var(--color-body)',
  fontFamily: 'inherit',
  fontSize: 13,
  lineHeight: 1.6,
  resize: 'vertical',
  boxSizing: 'border-box',
};

/** 透明主色描边按钮的悬停态：填充主色 */
const ghostHover = {
  onMouseEnter: (e: React.MouseEvent) => { const el = e.currentTarget as HTMLElement; el.style.background = 'var(--color-primary)'; el.style.color = 'white'; el.style.borderColor = 'var(--color-primary)'; },
  onMouseLeave: (e: React.MouseEvent) => { const el = e.currentTarget as HTMLElement; el.style.background = 'transparent'; el.style.color = 'var(--color-primary)'; el.style.borderColor = 'var(--color-primary)'; },
};

/** 参数能力对象（网关 params 或静态兜底） */
interface ParamLists {
  sizes?: string[];
  sizeDefault?: string;
  qualities?: string[];
  qualityDefault?: string;
  nRange?: { min?: number; max?: number; default?: number };
  durations?: number[];
  durationDefault?: number;
  resolutions?: string[];
  resolutionDefault?: string;
  aspectRatios?: string[];
  aspectRatioDefault?: string;
  refImageMax?: number;
  refImageSizeMB?: number;
  refVideoMax?: number;
  refVideoSizeMB?: number;
  refAudioMax?: number;
  refAudioSizeMB?: number;
}

/** 解析模型 params（JSON 字符串或对象）→ 参数能力（含网关输入约束，范围一律经网关获取） */
function parseParams(model: { params?: unknown } | undefined): ParamLists {
  const out: ParamLists = {};
  if (!model?.params) return out;
  let p: Record<string, unknown>;
  try {
    p = typeof model.params === 'string' ? (JSON.parse(model.params) as Record<string, unknown>) : (model.params as Record<string, unknown>);
  } catch {
    return out;
  }
  const arr = (k: string) => (Array.isArray(p[k]) ? (p[k] as unknown[]).map(String) : undefined);
  out.sizes = arr('sizes');
  out.sizeDefault = p.sizeDefault != null ? String(p.sizeDefault) : undefined;
  out.qualities = arr('qualities');
  out.qualityDefault = p.qualityDefault != null ? String(p.qualityDefault) : undefined;
  if (p.n && typeof p.n === 'object') {
    const n = p.n as Record<string, number>;
    if (n.min != null || n.max != null) {
      out.nRange = { min: n.min, max: n.max, default: n.default };
    }
  }
  const dur = p.durations;
  if (Array.isArray(dur) && dur.length) out.durations = dur.map(Number);
  out.durationDefault = p.durationDefault != null ? Number(p.durationDefault) : undefined;
  out.resolutions = arr('resolutions');
  out.resolutionDefault = p.resolutionDefault != null ? String(p.resolutionDefault) : undefined;
  out.aspectRatios = arr('aspectRatios');
  out.aspectRatioDefault = p.aspectRatioDefault != null ? String(p.aspectRatioDefault) : undefined;
  const rng = (k: string) => (p[k] && typeof p[k] === 'object' ? ((p[k] as Record<string, number>).max ?? undefined) : undefined);
  out.refImageMax = rng('refImages');
  out.refVideoMax = rng('refVideos');
  out.refAudioMax = rng('audioCount');
  out.refImageSizeMB = p.maxImageSizeMB != null ? Number(p.maxImageSizeMB) : undefined;
  out.refVideoSizeMB = p.maxVideoSizeMB != null ? Number(p.maxVideoSizeMB) : undefined;
  out.refAudioSizeMB = p.maxAudioSizeMB != null ? Number(p.maxAudioSizeMB) : undefined;
  return out;
}

function sceneTextFromScene(scene?: { scriptContent?: string; imagePrompt?: string; videoPrompt?: string }): SceneTextDraft {
  if (!scene) return EMPTY_SCENE_TEXT;
  return {
    scriptContent: scene.scriptContent || '',
    imagePrompt: scene.imagePrompt || '',
    videoPrompt: scene.videoPrompt || '',
  };
}

export function PreviewPanel() {
  const { scenes, selectedSceneId } = useProjectStore();
  const scene = scenes.find((s) => s.id === selectedSceneId);
  // 注意：selector 必须返回稳定引用（undefined/store 内已存数组），不能 `?? []`——每次新建数组
  // 会让 useSyncExternalStore 的 getSnapshot 不稳定 → React 无限重渲染（Maximum update depth exceeded）
  const rawSceneRefs = useProjectStore((s) => s.sceneRefs[selectedSceneId ?? '']);
  const sceneRefs = rawSceneRefs ?? [];
  const fetchSceneRefs = useProjectStore((s) => s.fetchSceneRefs);
  const uploadSceneRef = useProjectStore((s) => s.uploadSceneRef);
  const linkSceneRefs = useProjectStore((s) => s.linkSceneRefs);
  const deleteSceneRef = useProjectStore((s) => s.deleteSceneRef);
  const setSceneParams = useProjectStore((s) => s.setSceneParams);
  const clearSceneParams = useProjectStore((s) => s.clearSceneParams);
  const generateImage = useProjectStore((s) => s.generateImage);
  const generateVideo = useProjectStore((s) => s.generateVideo);
  const pollVideoTask = useProjectStore((s) => s.pollVideoTask);
  const refreshScenes = useProjectStore((s) => s.refreshScenes);
  const updateSceneInStore = useProjectStore((s) => s.updateSceneInStore);
  const generatingImage = useProjectStore((s) => (selectedSceneId ? !!s.generatingImage[selectedSceneId] : false));
  const generatingVideo = useProjectStore((s) => (selectedSceneId ? !!s.generatingVideo[selectedSceneId] : false));
  const imageModelOptions = useProjectStore((s) => s.imageModelOptions);
  const videoModelOptions = useProjectStore((s) => s.videoModelOptions);
  const globalImageModel = useProjectStore((s) => s.imageModel);
  const globalVideoModel = useProjectStore((s) => s.videoModel);
  const globalImageSize = useProjectStore((s) => s.imageSize);
  const globalImageQuality = useProjectStore((s) => s.imageQuality);
  const globalImageN = useProjectStore((s) => s.imageN);
  const globalVideoPreset = useProjectStore((s) => s.videoPreset);
  const currentProject = useProjectStore((s) => s.currentProject);

  const [activeTab, setActiveTab] = useState<PreviewTab>('image');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null); // 图片点击放大预览（灯箱）
  const [selectedDownloads, setSelectedDownloads] = useState<Set<string>>(new Set()); // 勾选下载
  const [useRefImage, setUseRefImage] = useState(false); // 参考图生图勾选
  const [useFirstFrame, setUseFirstFrame] = useState(false); // 以本分镜图片为首帧
  const [refineTarget, setRefineTarget] = useState<string | null>(null); // 完善图片弹窗的源图 URL（null=关闭）
  const [sceneGroupBindings, setSceneGroupBindings] = useState<SceneAssetGroupBinding[]>([]);
  const [assetPickerPurpose, setAssetPickerPurpose] = useState<'image' | 'video' | null>(null); // 资产选择弹窗用途
  const [projectLibraryPicker, setProjectLibraryPicker] = useState<{ type: 'image' | 'video' | 'audio'; purpose: 'image' | 'video'; remaining: number } | null>(null);
  const [libraryImportBusy, setLibraryImportBusy] = useState(false);
  const [libraryImportDone, setLibraryImportDone] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const mediaLoadedRef = useRef<((e: React.SyntheticEvent<Element>) => void) | null>(null);
  const animatedSrcRef = useRef<string | null>(null);

  // ── 参数本地草稿（图片/视频分开）：控件只写草稿不发请求，点「保存参数」才一次性 PATCH，避免拖动 slider 实时打爆后端 ──
  const [imageDraft, setImageDraft] = useState<Record<string, unknown>>({});
  const [videoDraft, setVideoDraft] = useState<Record<string, unknown>>({});
  const [savingParams, setSavingParams] = useState(false);
  const [sceneTextDraft, setSceneTextDraft] = useState<SceneTextDraft>(() => sceneTextFromScene(scene));
  const [fieldSaveState, setFieldSaveState] = useState<Record<EditableSceneField, FieldSaveState>>({
    scriptContent: 'idle',
    imagePrompt: 'idle',
    videoPrompt: 'idle',
  });
  const promptSceneIdRef = useRef<string | null>(scene?.id ?? null);

  // 切换分镜时拉取该分镜参考素材
  useEffect(() => {
    if (selectedSceneId) fetchSceneRefs(selectedSceneId);
  }, [selectedSceneId, fetchSceneRefs]);

  // 切换分镜时拉取资产组绑定（图片/视频用途分开）
  const loadSceneAssets = useCallback(async () => {
    if (!selectedSceneId) { setSceneGroupBindings([]); return; }
    try {
      const res = await sceneApi.listAssetGroups(selectedSceneId);
      setSceneGroupBindings(res.data.data ?? []);
    } catch {
      setSceneGroupBindings([]);
    }
  }, [selectedSceneId]);
  useEffect(() => { void loadSceneAssets(); }, [loadSceneAssets]);

  const removeSceneAsset = async (groupId: string, purpose: 'image' | 'video') => {
    if (!selectedSceneId) return;
    setSceneGroupBindings((prev) => prev.filter((binding) => !(binding.groupId === groupId && binding.purpose === purpose)));
    try {
      await sceneApi.removeAssetGroup(selectedSceneId, groupId, purpose);
    } catch {
      void loadSceneAssets();
    }
  };

  const bindSceneAssetGroups = async (groupIds: string[], purpose: 'image' | 'video') => {
    if (!selectedSceneId) return;
    const existing = new Set(
      sceneGroupBindings.filter((binding) => binding.purpose === purpose).map((binding) => binding.groupId),
    );
    const next = new Set(groupIds);
    for (const groupId of next) {
      if (!existing.has(groupId)) await sceneApi.bindAssetGroup(selectedSceneId, { groupId, purpose });
    }
    for (const groupId of existing) {
      if (!next.has(groupId)) await sceneApi.removeAssetGroup(selectedSceneId, groupId, purpose);
    }
    await loadSceneAssets();
  };

  // 稳定的 handler 引用：首帧渲染即挂上，内部转发到 contextSafe 包装的动画（layout effect 后生效）
  const handleMediaLoaded = (e: React.SyntheticEvent<Element>) => {
    mediaLoadedRef.current?.(e);
  };

  // 切换分镜时内容淡入 + 媒体加载完成"冲印"效果
  useGSAP((_context, contextSafe) => {
    if (!panelRef.current || !scene) return;
    gsap.fromTo(
      panelRef.current,
      { opacity: 0, y: 12 },
      {
        opacity: 1, y: 0, duration: 0.32, ease: 'power2.out',
        onComplete: () => {
          gsap.set(panelRef.current, { clearProps: 'transform' });
        },
      }
    );
    mediaLoadedRef.current = contextSafe?.((e: React.SyntheticEvent<Element>) => {
      const el = e.currentTarget as HTMLElement;
      const src = el.getAttribute('src') ?? '';
      if (animatedSrcRef.current === src) return;
      animatedSrcRef.current = src;
      gsap.fromTo(
        el,
        { filter: 'blur(10px)', opacity: 0.3, scale: 1.06 },
        { filter: 'blur(0px)', opacity: 1, scale: 1, duration: 0.7, ease: 'power2.out' }
      );
    }) ?? null;
  }, { dependencies: [scene?.id, activeTab], scope: panelRef });

  // 多图列表（image_urls 逗号分隔；无则回退单图 imageUrl）
  const imageList = useMemo(() => {
    return scene ? getSceneImageUrls(scene) : [];
  }, [scene]);
  useEffect(() => {
    setSelectedDownloads(new Set());
    setImageDraft({});
    setVideoDraft({});
    setLibraryImportDone(false);
  }, [scene?.id]);

  useEffect(() => {
    if (!scene || promptSceneIdRef.current === scene.id) return;
    promptSceneIdRef.current = scene.id;
    setSceneTextDraft(sceneTextFromScene(scene));
    setFieldSaveState({
      scriptContent: 'idle',
      imagePrompt: 'idle',
      videoPrompt: 'idle',
    });
  }, [scene]);

  const handleImportSceneMedia = async (items: Array<{ url: string; type: 'IMAGE' | 'VIDEO' }>) => {
    if (!scene || !currentProject?.id || items.length === 0) return;
    setLibraryImportBusy(true);
    setLibraryImportDone(false);
    const results = await Promise.allSettled(items.map((item, index) => importMediaToProjectLibrary({
      projectId: currentProject.id,
      producer: 'storyboard-editor',
      url: item.url,
      mediaType: item.type,
      label: `分镜 ${scene.sceneNumber} ${item.type === 'VIDEO' ? '视频' : `图片 ${index + 1}`}`,
    })));
    setLibraryImportDone(results.every((result) => result.status === 'fulfilled'));
    setLibraryImportBusy(false);
  };

  // 生成参数（草稿 > 分镜覆盖 > 全局默认；空串/0 = 回退）
  const effImageModel = (imageDraft.imageModel as string) || scene?.imageModel || globalImageModel;
  const effVideoModel = (videoDraft.videoModel as string) || scene?.videoModel || globalVideoModel;
  const imageParams = parseParams(imageModelOptions.find((m) => m.value === effImageModel));
  const videoParams = parseParams(videoModelOptions.find((m) => m.value === effVideoModel));
  const seedreamImage = isSeedreamImageModel(effImageModel);

  const sizeOptions = imageParams.sizes?.length ? imageParams.sizes : [...IMAGE_SIZES];
  const qualityOptions = seedreamImage ? [] : (imageParams.qualities?.length ? imageParams.qualities : [...IMAGE_QUALITIES]);
  const effImageSize = (imageDraft.imageSize as string) || scene?.imageSize || imageParams.sizeDefault || globalImageSize;
  const effImageQuality = seedreamImage
    ? undefined
    : ((imageDraft.imageQuality as string) || scene?.imageQuality || imageParams.qualityDefault || globalImageQuality);
  const effImageN = (imageDraft.imageN as number) || scene?.imageN || imageParams.nRange?.default || globalImageN;

  const preset = resolveVideoPreset(globalVideoPreset);
  const effVideoDuration = (videoDraft.duration as number) || scene?.duration || videoParams.durationDefault || parseInt(preset.duration);
  const effVideoResolution = (videoDraft.videoResolution as string) || scene?.videoResolution || videoParams.resolutionDefault || preset.resolution;
  const effVideoAspect = (videoDraft.videoAspectRatio as string) || scene?.videoAspectRatio || videoParams.aspectRatioDefault || preset.aspectRatio;
  const durationOptions = videoParams.durations?.length ? videoParams.durations : [4, 6, 8];
  const resolutionOptions = videoParams.resolutions?.length ? videoParams.resolutions : ['768P', '2K'];
  const aspectOptions = videoParams.aspectRatios?.length ? videoParams.aspectRatios : ['16:9', '9:16', '4:3', '1:1', '21:9'];

  // 参考素材按类型分组
  const refImages = sceneRefs.filter((r) => r.type === 'image' && r.purpose !== 'video'); // 图片生成参考图
  const refVImages = sceneRefs.filter((r) => r.type === 'image' && r.purpose === 'video'); // 视频生成参考图
  const refVideos = sceneRefs.filter((r) => r.type === 'video');
  const refAudios = sceneRefs.filter((r) => r.type === 'audio');
  const sceneImageGroups = sceneGroupBindings.filter((binding) => binding.purpose === 'image');
  const sceneVideoGroups = sceneGroupBindings.filter((binding) => binding.purpose === 'video');
  const refImageRemaining = Math.max(
    0,
    (imageParams.refImageMax ?? REFERENCE_LIMITS.image.maxCount) - refImages.length,
  );

  // 提交参数草稿：仅当有未保存改动时发一次 PATCH；成功清空对应草稿，失败弹错并返回 false（调用方据此决定是否继续）
  const commitParams = async (params: Record<string, unknown>, clear: () => void): Promise<boolean> => {
    if (!scene || !Object.keys(params).length) return true;
    setSavingParams(true);
    try {
      await setSceneParams(scene.id, params);
      clear();
      return true;
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '保存参数失败');
      alert(msg);
      return false;
    } finally {
      setSavingParams(false);
    }
  };

  const persistSceneText = async (field: EditableSceneField): Promise<boolean> => {
    if (!scene) return false;
    const value = sceneTextDraft[field].trim();
    const original = (scene[field] || '').trim();
    if (value === original) {
      setFieldSaveState((current) => ({ ...current, [field]: 'saved' }));
      return true;
    }

    setFieldSaveState((current) => ({ ...current, [field]: 'saving' }));
    try {
      await sceneApi.update(scene.id, { [field]: value });
      updateSceneInStore(scene.id, { [field]: value });
      setSceneTextDraft((current) => ({ ...current, [field]: value }));
      setFieldSaveState((current) => ({ ...current, [field]: 'saved' }));
      return true;
    } catch {
      setFieldSaveState((current) => ({ ...current, [field]: 'error' }));
      return false;
    }
  };

  const updateSceneTextDraft = (field: EditableSceneField, value: string) => {
    setSceneTextDraft((current) => ({ ...current, [field]: value }));
    setFieldSaveState((current) => ({ ...current, [field]: 'idle' }));
  };

  // 恢复全局默认：清空本分镜全部参数覆盖（图片+视频），并清空草稿
  const handleClearParams = async () => {
    if (!scene) return;
    setImageDraft({});
    setVideoDraft({});
    await clearSceneParams(scene.id);
  };

  const handleGenerateImage = async (mode?: 'edit', source?: string) => {
    if (!scene) return;
    const prompt = sceneTextDraft.imagePrompt.trim();
    if (!prompt.trim()) { alert('请先填写生图提示词'); return; }
    if (!(await persistSceneText('imagePrompt'))) {
      alert('提示词保存失败，请重试');
      return;
    }
    const useEdit = mode === 'edit';
    const refs: string[] | undefined = useEdit && source === 'ref'
      ? [
          ...refImages.map((r) => r.url),
          ...sceneImageGroups.flatMap((binding) =>
            binding.media.filter((media) => media.type === 'IMAGE').map((media) => `/api/files/${media.objectKey}`)),
        ].filter(Boolean)
      : undefined;
    // 先保存未保存的参数草稿再生成，避免生成用到旧参数（保存失败则中止）
    if (!(await commitParams(imageDraft, () => setImageDraft({})))) return;
    try {
      await generateImage(scene.id, prompt, effImageModel, refs, useEdit ? 'edit' : undefined, source === 'current' ? scene.imageUrl || undefined : undefined);
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '生成图片失败');
      alert(msg);
    }
  };

  const handleGenerateVideo = async () => {
    if (!scene) return;
    const prompt = sceneTextDraft.videoPrompt.trim();
    if (!prompt.trim()) { alert('请先填写生视频提示词'); return; }
    if (!(await persistSceneText('videoPrompt'))) {
      alert('提示词保存失败，请重试');
      return;
    }
    // 先保存未保存的参数草稿再生成（保存失败则中止）
    if (!(await commitParams(videoDraft, () => setVideoDraft({})))) return;
    try {
      if (useFirstFrame && scene.imageUrl) {
        // 以本分镜图片为首帧（i2v）：参考素材与首帧互斥
        await generateVideo(scene.id, prompt, effVideoModel, undefined, scene.imageUrl);
      } else if (refVImages.length || refVideos.length || refAudios.length || sceneVideoGroups.length) {
        // 多模态参考（r2va）
        const groupImages = sceneVideoGroups.flatMap((binding) =>
          binding.media.filter((media) => media.type === 'IMAGE').map((media) => `/api/files/${media.objectKey}`));
        const groupVideos = sceneVideoGroups.flatMap((binding) =>
          binding.media.filter((media) => media.type === 'VIDEO').map((media) => `/api/files/${media.objectKey}`));
        const groupAudios = sceneVideoGroups.flatMap((binding) =>
          binding.media.filter((media) => media.type === 'AUDIO').map((media) => `/api/files/${media.objectKey}`));
        await generateVideo(
          scene.id, prompt, effVideoModel,
          [...refVImages.map((r) => r.url), ...groupImages].filter(Boolean), undefined,
          [...refVideos.map((r) => r.url), ...groupVideos].filter(Boolean),
          [...refAudios.map((r) => r.url), ...groupAudios].filter(Boolean)
        );
      } else {
        await generateVideo(scene.id, prompt, effVideoModel);
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '生成视频失败');
      alert(msg);
    }
  };

  // 手动拉取视频：视频创建成功但轮询失败/放弃时，用已落库的 videoTaskId 重新走完整闭环（查上游→下载→落库 videoUrl/status）。
  // 仅当 scene.videoTaskId 有值时才可用——创建本身抛错时该字段为空，只能重试生成。
  const handleManualFetchVideo = async () => {
    if (!scene || !scene.videoTaskId) return;
    try {
      await pollVideoTask(scene.id, scene.videoTaskId);
      // pollVideoTask 终态时内部 loadProject 刷新 scene（completed/failed 都会更新 UI）
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '手动拉取视频失败');
      alert(msg);
    }
  };

  // 刷新图片状态：图片为同步生成（后端在请求线程内完成并落库），客户端刷新/重登会中断请求但后端仍会完成；
  // 点此重载 scenes 拾取后端已落库的结果（若确实失败则保持 failed，可走重试）。
  const handleRefreshImage = async () => {
    try {
      await refreshScenes();
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '刷新图片状态失败');
      alert(msg);
    }
  };
  const tabStyle = (tab: PreviewTab): React.CSSProperties => ({
    padding: '6px 14px',
    fontSize: 12,
    fontWeight: 500,
    border: 'none',
    borderRadius: 'var(--rounded-sm)',
    cursor: 'pointer',
    background: activeTab === tab ? 'var(--color-surface-card)' : 'transparent',
    color: activeTab === tab ? 'var(--color-ink)' : 'var(--color-muted)',
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
  });

  const btnDownload: React.CSSProperties = {
    padding: '6px 14px',
    fontSize: 11,
    fontWeight: 500,
    borderRadius: 8,
    border: '1px solid var(--color-primary)',
    background: 'transparent',
    color: 'var(--color-primary)',
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    justifyContent: 'center',
    cursor: 'pointer',
    transition: 'all 0.18s ease',
  };

  const toggleDownload = (url: string) => {
    setSelectedDownloads((prev) => {
      const next = new Set(prev);
      if (next.has(url)) next.delete(url); else next.add(url);
      return next;
    });
  };

  const downloadSelected = () => {
    if (!selectedDownloads.size) return;
    const urls = Array.from(selectedDownloads);
    urls.forEach((url, i) => {
      setTimeout(
        () => downloadAsset(assetUrl(url), `scene-${scene?.sceneNumber}-${i + 1}.png`, { silent: true }),
        i * 150,
      );
    });
    notify({ title: `已下载 ${urls.length} 张图片`, variant: 'info' });
  };

  const fieldStatusLabel = (field: EditableSceneField): string => {
    const state = fieldSaveState[field];
    return state === 'saving' ? '保存中'
      : state === 'saved' ? '已保存'
        : state === 'error' ? '保存失败'
          : '';
  };

  if (!scene) {
    return (
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--color-muted-soft)',
          fontSize: 13,
          gap: 8,
        }}
      >
        <span style={{ fontSize: 32, opacity: 0.3 }}><svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg></span>
        <span>选择左侧分镜查看预览</span>
      </div>
    );
  }

  return (
    <div
      ref={panelRef}
      style={{
        flex: 1,
        padding: 'var(--space-md)',
        overflowY: 'auto',
        background: 'var(--color-canvas)',
      }}
    >
      {/* Title */}
      <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', marginBottom: 12 }}>
        预览 — 分镜 {scene.sceneNumber}
      </h2>

      {/* Script content */}
      <div style={{ marginBottom: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 6 }}>
          <label htmlFor="preview-scene-script" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>分镜描述</label>
          <span style={{ fontSize: 10, color: fieldSaveState.scriptContent === 'error' ? 'var(--color-error)' : 'var(--color-muted)' }}>{fieldStatusLabel('scriptContent')}</span>
        </div>
        <textarea
          id="preview-scene-script"
          value={sceneTextDraft.scriptContent}
          onChange={(event) => updateSceneTextDraft('scriptContent', event.target.value)}
          onBlur={(event) => {
            event.currentTarget.style.borderColor = 'var(--color-hairline)';
            void persistSceneText('scriptContent');
          }}
          onFocus={(event) => { event.currentTarget.style.borderColor = 'var(--color-primary)'; }}
          placeholder="输入分镜描述..."
          rows={3}
          style={sceneTextareaStyle}
        />
      </div>

      {/* Tab bar */}
      <div style={{ display: 'flex', gap: 4, marginBottom: 12 }}>
        <button onClick={() => setActiveTab('image')} onMouseEnter={(e) => { if (activeTab !== 'image') { e.currentTarget.style.background = 'var(--color-surface-card)'; e.currentTarget.style.color = 'var(--color-primary)'; } }} onMouseLeave={(e) => { if (activeTab !== 'image') { e.currentTarget.style.background = 'var(--color-canvas)'; e.currentTarget.style.color = 'var(--color-muted)'; } }} style={tabStyle('image')}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg> 图片
        </button>
        <button onClick={() => setActiveTab('video')} onMouseEnter={(e) => { if (activeTab !== 'video') { e.currentTarget.style.background = 'var(--color-surface-card)'; e.currentTarget.style.color = 'var(--color-primary)'; } }} onMouseLeave={(e) => { if (activeTab !== 'video') { e.currentTarget.style.background = 'var(--color-canvas)'; e.currentTarget.style.color = 'var(--color-muted)'; } }} style={tabStyle('video')}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg> 视频
        </button>
      </div>

      {/* ═══════════ 图片 tab：只显示图片生成相关信息 ═══════════ */}
      {activeTab === 'image' && (
        <div>
          {/* 结果区：多图轮播 + 选择性下载 */}
          <div style={{ marginBottom: 12 }}>
            {imageList.length > 0 ? (
              <div>
                {imageList.length > 1 ? (
                  <ContextMenu items={[{
                    label: '导入项目库',
                    icon: <Library size={13} strokeWidth={1.8} />,
                    disabled: !currentProject?.id || libraryImportBusy,
                    onClick: () => void handleImportSceneMedia(
                      imageList.map((url) => ({ url, type: 'IMAGE' as const })),
                    ),
                  }]}>
                    <div style={{ width: 'min(100%, 620px)', height: 360, margin: '8px auto 12px' }}>
                      <DepthCarousel
                        items={imageList.map((url, index) => ({
                          image: assetUrl(url),
                          alt: `分镜 ${scene.sceneNumber ?? ''} 图 ${index + 1}`,
                        }))}
                        cardWidth={400}
                        cardHeight={267}
                        radius={12}
                        tint="#714f46"
                        depth={170}
                        spread={72}
                        tilt={14}
                        tiltDirection="right"
                        perspective={1500}
                        visibleCards={4}
                        falloff={0.16}
                        blur={4}
                        autoplay={false}
                        loop
                        showControls
                        showIndicators
                        showIndexLabels
                        onCardActivate={(_, item) => setPreviewUrl(item.image)}
                      />
                    </div>
                  </ContextMenu>
                ) : (
                  <ContextMenu items={[
                  { label: '预览', icon: <Eye size={13} strokeWidth={1.8} />, onClick: () => setPreviewUrl(imageList[0]) },
                  { label: '下载图片', icon: <Download size={13} strokeWidth={1.8} />, onClick: () => downloadAsset(assetUrl(imageList[0]), `scene-${scene.sceneNumber}.png`) },
                  {
                    label: '导入项目库',
                    icon: <Library size={13} strokeWidth={1.8} />,
                    disabled: !currentProject?.id || libraryImportBusy,
                    onClick: () => void handleImportSceneMedia([{ url: imageList[0], type: 'IMAGE' }]),
                  },
                  { label: '完善图片', icon: <Sparkles size={13} strokeWidth={1.8} />, onClick: () => setRefineTarget(imageList[0]) },
                ]}>
                  <img
                    src={assetUrl(imageList[0])}
                    alt={`分镜 ${scene.sceneNumber} 生成图`}
                    onClick={() => setPreviewUrl(imageList[0])}
                    onLoad={handleMediaLoaded}
                    style={{
                      width: '100%',
                      maxHeight: 360,
                      objectFit: 'contain',
                      borderRadius: 'var(--rounded-md)',
                      border: '1px solid var(--color-hairline)',
                      background: 'var(--color-canvas)',
                      marginBottom: 8,
                      cursor: 'zoom-in',
                    }}
                  />
                </ContextMenu>
                )}
                {/* 选择性下载（勾选框用主题色 accent，点击整行可勾选） */}
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}>
                  {imageList.map((url, i) => (
                    <label
                      key={url}
                      onClick={() => toggleDownload(url)}
                      style={{
                        display: 'inline-flex', alignItems: 'center', gap: 5,
                        fontSize: 11, color: selectedDownloads.has(url) ? 'var(--color-primary)' : 'var(--color-muted)',
                        cursor: 'pointer', padding: '3px 9px', borderRadius: 999,
                        border: selectedDownloads.has(url) ? '1px solid var(--color-primary)' : '1px solid var(--color-hairline)',
                        background: selectedDownloads.has(url) ? 'rgba(204,120,92,0.10)' : 'transparent',
                        transition: 'all 0.18s ease', userSelect: 'none',
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={selectedDownloads.has(url)}
                        onChange={() => {}}
                        style={{ margin: 0, cursor: 'pointer', accentColor: 'var(--color-primary)' }}
                      />
                      图 {i + 1}
                    </label>
                  ))}
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  {imageList.length === 1 && (
                    <button
                      onClick={() => downloadAsset(assetUrl(imageList[0]), `scene-${scene.sceneNumber}.png`)}
                      style={{ ...btnDownload, background: 'var(--color-primary)', color: '#fff' }}
                      onMouseEnter={(e) => { e.currentTarget.style.background = '#b9654b'; }}
                      onMouseLeave={(e) => { e.currentTarget.style.background = 'var(--color-primary)'; }}
                    >
                      <Download size={12} strokeWidth={2} /> 下载当前
                    </button>
                  )}
                  {selectedDownloads.size > 0 && (
                    <button onClick={downloadSelected} {...ghostHover} style={btnDownload}>
                      <Download size={12} strokeWidth={2} /> 下载选中 ({selectedDownloads.size})
                    </button>
                  )}
                  <Tooltip label={currentProject?.id ? '导入项目库' : '请先选择项目'}>
                  <button
                    onClick={() => void handleImportSceneMedia(
                      (selectedDownloads.size > 0
                        ? imageList.filter((url) => selectedDownloads.has(url))
                        : imageList
                      ).map((url) => ({ url, type: 'IMAGE' as const })),
                    )}
                    disabled={!currentProject?.id || libraryImportBusy}
                    style={{ ...btnDownload, opacity: currentProject?.id ? 1 : 0.5 }}
                  >
                    {libraryImportBusy
                      ? <LoaderCircle size={12} className="is-spinning" />
                      : <Library size={12} strokeWidth={2} />}
                    {libraryImportDone ? '已导入' : libraryImportBusy ? '导入中...' : '导入项目库'}
                  </button>
                  </Tooltip>
                </div>
              </div>
            ) : (
              <div
                style={{
                  width: '100%', height: 200, borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-surface-soft)', color: 'var(--color-muted-soft)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13,
                }}
              >
                {scene.imageStatus === 'generating' ? '正在生成图片...'
                  : scene.imageStatus === 'failed' ? (
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
                      <span>生成失败，点击下方重试</span>
                      <button
                        onClick={() => void handleRefreshImage()}
                        {...ghostHover}
                        style={{ ...btnGhost, cursor: 'pointer' }}
                      >
                        刷新状态
                      </button>
                    </div>
                  )
                  : '未生成图片'}
              </div>
            )}
          </div>

          {/* 图片提示词编辑 */}
          <div style={{ marginBottom: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 6 }}>
              <label htmlFor="preview-image-prompt" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>图片提示词</label>
              <span style={{ fontSize: 10, color: fieldSaveState.imagePrompt === 'error' ? 'var(--color-error)' : 'var(--color-muted)' }}>{fieldStatusLabel('imagePrompt')}</span>
            </div>
            <textarea
              id="preview-image-prompt"
              value={sceneTextDraft.imagePrompt}
              onChange={(event) => updateSceneTextDraft('imagePrompt', event.target.value)}
              onBlur={(event) => {
                event.currentTarget.style.borderColor = 'var(--color-hairline)';
                void persistSceneText('imagePrompt');
              }}
              onFocus={(event) => { event.currentTarget.style.borderColor = 'var(--color-primary)'; }}
              placeholder="输入图片生成提示词..."
              rows={4}
              style={sceneTextareaStyle}
            />
          </div>

          {/* 生成参数区（只含图片生成相关信息） */}
          <div style={{ marginBottom: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>图片生成参数</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                {Object.keys(imageDraft).length > 0 && (
                  <span style={{ fontSize: 11, color: 'var(--color-warning)' }}>● 有未保存改动</span>
                )}
                <button
                  onClick={() => commitParams(imageDraft, () => setImageDraft({}))}
                  disabled={Object.keys(imageDraft).length === 0 || savingParams}
                  onMouseEnter={(e) => { if (Object.keys(imageDraft).length > 0) (e.currentTarget as HTMLElement).style.background = 'var(--color-primary-active)'; }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = Object.keys(imageDraft).length === 0 ? 'var(--color-primary-disabled)' : 'var(--color-primary)'; }}
                  style={{
                    padding: '4px 14px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: 'none',
                    background: Object.keys(imageDraft).length === 0 ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
                    color: Object.keys(imageDraft).length === 0 ? 'var(--color-muted)' : 'white',
                    cursor: Object.keys(imageDraft).length === 0 ? 'not-allowed' : 'pointer',
                    display: 'inline-flex', alignItems: 'center', gap: 4,
                  }}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> 保存参数
                </button>
                <button
                  onClick={handleClearParams}
                  style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}
                >
                  恢复全局默认
                </button>
              </div>
            </div>

            <div style={fieldRow}>
              <span style={fieldLabel}>模型</span>
              <Select value={effImageModel} onValueChange={(v) => { if (v) setImageDraft((d) => ({ ...d, imageModel: v })); }}>
                <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {imageModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                </SelectContent>
              </Select>
              {!scene.imageModel && imageDraft.imageModel === undefined && <span style={{ fontSize: 10, color: 'var(--color-muted-soft)' }}>跟随全局</span>}
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>尺寸</span>
              <Select value={effImageSize} onValueChange={(v) => { if (v) setImageDraft((d) => ({ ...d, imageSize: v })); }}>
                <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {sizeOptions.map((s) => <SelectItem key={s} value={s}>{s}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            {qualityOptions.length > 0 && (
              <div style={fieldRow}>
                <span style={fieldLabel}>质量</span>
                <Select value={effImageQuality} onValueChange={(v) => { if (v) setImageDraft((d) => ({ ...d, imageQuality: v })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {qualityOptions.map((q) => <SelectItem key={q} value={q}>{q}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            )}
            {imageParams.nRange && (
              <div style={fieldRow}>
                <span style={fieldLabel}>生成个数</span>
                <ElasticSlider
                  defaultValue={effImageN}
                  startingValue={imageParams.nRange.min ?? 1}
                  maxValue={imageParams.nRange.max ?? imageParams.nRange.min ?? 1}
                  isStepped
                  stepSize={1}
                  onChange={(v) => setImageDraft((d) => ({ ...d, imageN: v }))}
                />
              </div>
            )}

            {/* 参考图生图：勾选后展开模型指定 + 上传入口 */}
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer', marginBottom: useRefImage ? 8 : 0 }}>
              <input type="checkbox" checked={useRefImage} onChange={(e) => setUseRefImage(e.target.checked)} style={{ margin: 0, cursor: 'pointer', accentColor: 'var(--color-primary)' }} />
              参考图生图（以参考图为源图改图）
            </label>
            {useRefImage && (
              <div style={{ marginBottom: 8 }}>
                <ReferenceUploader
                  type="image"
                  items={refImages}
                  maxCount={imageParams.refImageMax}
                  maxSizeMB={imageParams.refImageSizeMB}
                  onUpload={(f) => uploadSceneRef(scene.id, 'image', 'image', f)}
                  onDelete={(id) => deleteSceneRef(scene.id, id)}
                  onPickProjectLibrary={() => setProjectLibraryPicker({
                    type: 'image', purpose: 'image', remaining: refImageRemaining,
                  })}
                />
              </div>
            )}

            <div style={{ display: 'flex', gap: 8, marginTop: 4, justifyContent: 'center' }}>
              <SpecularButton
                size="sm"
                radius={8}
                tint="#cc785c"
                tintOpacity={1}
                textColor="#ffffff"
                lineColor="#ffffff"
                baseColor="#ffffff"
                intensity={1}
                thickness={1.2}
                disabled={generatingImage}
                onClick={() => handleGenerateImage(useRefImage && refImages.length ? 'edit' : undefined, useRefImage && refImages.length ? 'ref' : undefined)}
              >
                {generatingImage ? '生成中...' : scene.imageStatus === 'failed' ? '重试生成' : '生成图片'}
              </SpecularButton>
              {scene.imageUrl && (
                <button
                  onClick={() => setRefineTarget(scene.imageUrl)}
                  {...ghostHover}
                  style={{ ...btnGhost, cursor: 'pointer' }}
                >
                  完善图片
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ═══════════ 视频 tab：只显示视频生成相关信息 ═══════════ */}
      {activeTab === 'video' && (
        <div>
          {/* 结果区 */}
          <div style={{ marginBottom: 12 }}>
            {scene.videoUrl ? (
              <ContextMenu items={[
                  { label: '预览', icon: <Eye size={13} strokeWidth={1.8} />, onClick: () => { /* video 已有 controls，点击即播放 */ } },
                  { label: '下载视频', icon: <Download size={13} strokeWidth={1.8} />, onClick: () => downloadAsset(assetUrl(scene.videoUrl), `scene-${scene.sceneNumber}.mp4`) },
                  {
                    label: '导入项目库',
                    icon: <Library size={13} strokeWidth={1.8} />,
                    disabled: !currentProject?.id || libraryImportBusy,
                    onClick: () => void handleImportSceneMedia([{ url: scene.videoUrl!, type: 'VIDEO' }]),
                  },
                ]}>
                <div>
                <video
                  src={assetUrl(scene.videoUrl)}
                  controls
                  onLoadedData={handleMediaLoaded}
                  style={{
                    width: '100%', maxHeight: 360, borderRadius: 'var(--rounded-md)',
                    border: '1px solid var(--color-hairline)', background: '#000', marginBottom: 8,
                  }}
                />
                <button onClick={() => downloadAsset(assetUrl(scene.videoUrl), `scene-${scene.sceneNumber}.mp4`)} {...ghostHover} style={btnDownload}>
                  <Download size={12} strokeWidth={2} /> 下载视频
                </button>
                <Tooltip label={currentProject?.id ? '导入项目库' : '请先选择项目'}>
                <button
                  onClick={() => void handleImportSceneMedia([{ url: scene.videoUrl!, type: 'VIDEO' }])}
                  disabled={!currentProject?.id || libraryImportBusy}
                  style={{ ...btnDownload, opacity: currentProject?.id ? 1 : 0.5 }}
                >
                  {libraryImportBusy
                    ? <LoaderCircle size={12} className="is-spinning" />
                    : <Library size={12} strokeWidth={2} />}
                  {libraryImportDone ? '已导入' : libraryImportBusy ? '导入中...' : '导入项目库'}
                </button>
                </Tooltip>
                </div>
                </ContextMenu>
            ) : (
              <div
                style={{
                  width: '100%', height: 200, borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-surface-soft)', color: 'var(--color-muted-soft)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13,
                }}
              >
                {scene.videoStatus === 'generating' ? '正在生成视频...'
                  : scene.videoStatus === 'failed' ? (
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
                      <span>生成失败，点击下方重试</span>
                      {scene.videoTaskId && (
                        <Tooltip label="重新拉取视频结果">
                          <button
                            onClick={() => void handleManualFetchVideo()}
                            {...ghostHover}
                            style={{ ...btnGhost, cursor: 'pointer' }}
                          >
                            手动拉取视频
                          </button>
                        </Tooltip>
                      )}
                    </div>
                  )
                  : '未生成视频'}
              </div>
            )}
          </div>

          {/* 视频提示词编辑 */}
          <div style={{ marginBottom: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 6 }}>
              <label htmlFor="preview-video-prompt" style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>视频提示词</label>
              <span style={{ fontSize: 10, color: fieldSaveState.videoPrompt === 'error' ? 'var(--color-error)' : 'var(--color-muted)' }}>{fieldStatusLabel('videoPrompt')}</span>
            </div>
            <textarea
              id="preview-video-prompt"
              value={sceneTextDraft.videoPrompt}
              onChange={(event) => updateSceneTextDraft('videoPrompt', event.target.value)}
              onBlur={(event) => {
                event.currentTarget.style.borderColor = 'var(--color-hairline)';
                void persistSceneText('videoPrompt');
              }}
              onFocus={(event) => { event.currentTarget.style.borderColor = 'var(--color-primary)'; }}
              placeholder="输入视频生成提示词..."
              rows={4}
              style={sceneTextareaStyle}
            />
          </div>

          {/* 生成参数区（只含视频生成相关信息） */}
          <div style={{ marginBottom: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>视频生成参数</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                {Object.keys(videoDraft).length > 0 && (
                  <span style={{ fontSize: 11, color: 'var(--color-warning)' }}>● 有未保存改动</span>
                )}
                <button
                  onClick={() => commitParams(videoDraft, () => setVideoDraft({}))}
                  disabled={Object.keys(videoDraft).length === 0 || savingParams}
                  onMouseEnter={(e) => { if (Object.keys(videoDraft).length > 0) (e.currentTarget as HTMLElement).style.background = 'var(--color-primary-active)'; }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = Object.keys(videoDraft).length === 0 ? 'var(--color-primary-disabled)' : 'var(--color-primary)'; }}
                  style={{
                    padding: '4px 14px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: 'none',
                    background: Object.keys(videoDraft).length === 0 ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
                    color: Object.keys(videoDraft).length === 0 ? 'var(--color-muted)' : 'white',
                    cursor: Object.keys(videoDraft).length === 0 ? 'not-allowed' : 'pointer',
                    display: 'inline-flex', alignItems: 'center', gap: 4,
                  }}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> 保存参数
                </button>
                <button
                  onClick={handleClearParams}
                  style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}
                >
                  恢复全局默认
                </button>
              </div>
            </div>

            <div style={fieldRow}>
              <span style={fieldLabel}>模型</span>
              <Select value={effVideoModel} onValueChange={(v) => { if (v) setVideoDraft((d) => ({ ...d, videoModel: v })); }}>
                <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {videoModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                </SelectContent>
              </Select>
              {!scene.videoModel && videoDraft.videoModel === undefined && <span style={{ fontSize: 10, color: 'var(--color-muted-soft)' }}>跟随全局</span>}
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>时长(秒)</span>
              <ElasticSlider
                defaultValue={effVideoDuration}
                startingValue={Math.min(...durationOptions)}
                maxValue={Math.max(...durationOptions)}
                isStepped
                stepSize={1}
                onChange={(v) => setVideoDraft((d) => ({ ...d, duration: v }))}
              />
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>分辨率</span>
              <Select value={effVideoResolution} onValueChange={(v) => { if (v) setVideoDraft((d) => ({ ...d, videoResolution: v })); }}>
                <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {resolutionOptions.map((r) => <SelectItem key={r} value={r}>{r}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>画幅</span>
              <Select value={effVideoAspect} onValueChange={(v) => { if (v) setVideoDraft((d) => ({ ...d, videoAspectRatio: v })); }}>
                <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {aspectOptions.map((a) => <SelectItem key={a} value={a}>{a}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>

            {/* 参考素材区（r2va 多模态参考）：与首帧互斥 */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
              <ReferenceUploader
                type="image"
                items={refVImages}
                maxCount={videoParams.refImageMax}
                maxSizeMB={videoParams.refImageSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'image', 'video', f)}
                onDelete={(id) => deleteSceneRef(scene.id, id)}
                onPickProjectLibrary={() => setProjectLibraryPicker({
                  type: 'image',
                  purpose: 'video',
                  remaining: Math.max(0, (videoParams.refImageMax ?? REFERENCE_LIMITS.image.maxCount)
                    - refVImages.length),
                })}
                disabled={useFirstFrame}
              />
              <ReferenceUploader
                type="video"
                items={refVideos}
                maxCount={videoParams.refVideoMax}
                maxSizeMB={videoParams.refVideoSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'video', 'video', f)}
                onDelete={(id) => deleteSceneRef(scene.id, id)}
                onPickProjectLibrary={() => setProjectLibraryPicker({
                  type: 'video',
                  purpose: 'video',
                  remaining: Math.max(0, (videoParams.refVideoMax ?? REFERENCE_LIMITS.video.maxCount)
                    - refVideos.length),
                })}
                disabled={useFirstFrame}
              />
              <ReferenceUploader
                type="audio"
                items={refAudios}
                maxCount={videoParams.refAudioMax}
                maxSizeMB={videoParams.refAudioSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'audio', 'video', f)}
                onDelete={(id) => deleteSceneRef(scene.id, id)}
                onPickProjectLibrary={() => setProjectLibraryPicker({
                  type: 'audio',
                  purpose: 'video',
                  remaining: Math.max(0, (videoParams.refAudioMax ?? REFERENCE_LIMITS.audio.maxCount)
                    - refAudios.length),
                })}
                disabled={useFirstFrame}
              />
            </div>

            {/* 以本分镜图片为首帧（i2v，保留原图生视频能力；与参考素材互斥） */}
            {scene.imageUrl && (
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer', marginTop: 10 }}>
                <input
                  type="checkbox"
                  checked={useFirstFrame}
                  onChange={(e) => {
                    setUseFirstFrame(e.target.checked);
                    if (e.target.checked) setUseRefImage(false);
                  }}
                  style={{ margin: 0, cursor: 'pointer', accentColor: 'var(--color-primary)' }}
                />
                以本分镜图片为首帧（参考素材与首帧互斥）
              </label>
            )}

            <div style={{ display: 'flex', gap: 8, marginTop: 10, justifyContent: 'center' }}>
              <SpecularButton
                size="sm"
                radius={8}
                tint="#cc785c"
                tintOpacity={1}
                textColor="#ffffff"
                lineColor="#ffffff"
                baseColor="#ffffff"
                intensity={1}
                thickness={1.2}
                disabled={generatingVideo}
                onClick={handleGenerateVideo}
              >
                {generatingVideo ? '生成中...' : scene.videoStatus === 'failed' ? '重试生成' : '生成视频'}
              </SpecularButton>
            </div>
          </div>
        </div>
      )}

      {/* 镜头信息 */}
      {(scene.cameraMovement || scene.shotType) && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 4 }}>
          {scene.cameraMovement && (
            <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 'var(--rounded-sm)', background: 'var(--color-surface-soft)', color: 'var(--color-muted)', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg> {scene.cameraMovement}
            </span>
          )}
          {scene.shotType && (
            <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 'var(--rounded-sm)', background: 'var(--color-surface-soft)', color: 'var(--color-muted)', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21.71 3.29a1 1 0 00-1.42 0l-18 18a1 1 0 001.42 1.42l18-18a1 1 0 000-1.42z"/><path d="M3 12h4M12 3v4"/></svg> {scene.shotType}
            </span>
          )}
        </div>
      )}

      {/* 关联资产（本分镜引用的人物/道具/场景设定，图片/视频生成时分别注入） */}
      <div style={{ marginTop: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)', display: 'inline-flex', alignItems: 'center', gap: 4 }}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"/><path d="M10 7h4M7 10v4M17 10v4M10 17h4"/></svg> 关联资产（{activeTab === 'image' ? '图片' : '视频'}生成时注入设定与参考图）</span>

        {activeTab === 'image' && (
          <div style={{ marginTop: 10 }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink)', display: 'inline-flex', alignItems: 'center', gap: 4 }}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg> 图片关联资产</span>
            {sceneImageGroups.length === 0 ? (
              <div style={{ fontSize: 11, color: 'var(--color-muted-soft)', lineHeight: 1.6, marginTop: 6 }}>未关联图片资产组</div>
            ) : (
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
                {sceneImageGroups.map((binding) => (
                  <span key={`${binding.groupId}-${binding.snapshotId}`} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 6px 3px 8px', borderRadius: 999, border: '1px solid var(--color-hairline)', background: 'white', fontSize: 12, color: 'var(--color-ink)' }}>
                    资产组 · {binding.media.length} 项
                    {binding.stale && (
                      <button
                        onClick={() => void sceneApi.bindAssetGroup(selectedSceneId!, { groupId: binding.groupId, purpose: 'image' })
                          .then(loadSceneAssets)}
                        style={{ border: 'none', background: 'transparent', color: 'var(--color-error)', cursor: 'pointer', fontSize: 10 }}
                      >已更新·刷新</button>
                    )}
                    <Tooltip label="取消关联">
                      <button onClick={() => void removeSceneAsset(binding.groupId, 'image')} aria-label="取消关联" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--color-muted-soft)', fontSize: 12, padding: 0, lineHeight: 1 }}>×</button>
                    </Tooltip>
                  </span>
                ))}
              </div>
            )}
            <div style={{ display: 'flex', justifyContent: 'center', marginTop: 8 }}>
              <button onClick={() => setAssetPickerPurpose('image')} {...ghostHover} style={{ padding: '6px 18px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: '1px solid var(--color-primary)', background: 'transparent', color: 'var(--color-primary)', cursor: 'pointer', fontWeight: 600 }}>＋ 添加资产</button>
            </div>
          </div>
        )}

        {activeTab === 'video' && (
          <div style={{ marginTop: 10 }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-ink)', display: 'inline-flex', alignItems: 'center', gap: 4 }}><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg> 视频关联资产</span>
            {sceneVideoGroups.length === 0 ? (
              <div style={{ fontSize: 11, color: 'var(--color-muted-soft)', lineHeight: 1.6, marginTop: 6 }}>未关联视频资产组</div>
            ) : (
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
                {sceneVideoGroups.map((binding) => (
                  <span key={`${binding.groupId}-${binding.snapshotId}`} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 6px 3px 8px', borderRadius: 999, border: '1px solid var(--color-hairline)', background: 'white', fontSize: 12, color: 'var(--color-ink)' }}>
                    资产组 · {binding.media.length} 项
                    {binding.stale && (
                      <button
                        onClick={() => void sceneApi.bindAssetGroup(selectedSceneId!, { groupId: binding.groupId, purpose: 'video' })
                          .then(loadSceneAssets)}
                        style={{ border: 'none', background: 'transparent', color: 'var(--color-error)', cursor: 'pointer', fontSize: 10 }}
                      >已更新·刷新</button>
                    )}
                    <Tooltip label="取消关联">
                      <button onClick={() => void removeSceneAsset(binding.groupId, 'video')} aria-label="取消关联" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--color-muted-soft)', fontSize: 12, padding: 0, lineHeight: 1 }}>×</button>
                    </Tooltip>
                  </span>
                ))}
              </div>
            )}
            <div style={{ display: 'flex', justifyContent: 'center', marginTop: 8 }}>
              <button onClick={() => setAssetPickerPurpose('video')} {...ghostHover} style={{ padding: '6px 18px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: '1px solid var(--color-primary)', background: 'transparent', color: 'var(--color-primary)', cursor: 'pointer', fontWeight: 600 }}>＋ 添加资产</button>
            </div>
          </div>
        )}
      </div>
      {assetPickerPurpose && (
        <AssetGroupPickerModal
          open
          projectId={currentProject?.id}
          purpose={assetPickerPurpose}
          selectedIds={sceneGroupBindings
            .filter((binding) => binding.purpose === assetPickerPurpose)
            .map((binding) => binding.groupId)}
          onClose={() => setAssetPickerPurpose(null)}
          onConfirm={(groupIds) => bindSceneAssetGroups(groupIds, assetPickerPurpose)}
        />
      )}
      <ProjectLibraryPickerModal
        open={!!projectLibraryPicker}
        projectId={currentProject?.id}
        type={projectLibraryPicker?.type ?? 'image'}
        purpose={projectLibraryPicker?.purpose ?? 'image'}
        maxSelectable={projectLibraryPicker?.remaining}
        onClose={() => setProjectLibraryPicker(null)}
        onConfirm={(references) => linkSceneRefs(scene.id, references)}
      />

      {/* 图片点击放大预览（灯箱，与智能体窗口行为一致） */}
      <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
      {/* 完善图片弹窗：以当前图为源图做图改图（诉求输入后生成） */}
      {refineTarget && scene && (
        <RefineImageModal sceneId={scene.id} imageUrl={refineTarget} onClose={() => setRefineTarget(null)} />
      )}
    </div>
  );
}
