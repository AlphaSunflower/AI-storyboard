import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from '../agent/ImagePreviewModal';
import { ReferenceUploader } from './ReferenceUploader';
import { RefineImageModal } from './RefineImageModal';
import ElasticSlider from '../ElasticSlider';
import BounceCards from '../BounceCards';
import SpecularButton from '../SpecularButton';
import { resolveVideoPreset } from '../common/VideoPresetSelector';
import { IMAGE_SIZES, IMAGE_QUALITIES } from '../../config';
import { AssetLibraryPanel } from '../asset/AssetLibraryPanel';
import { assetApi, type Asset } from '../../api/assets';

function downloadAsset(url: string, filename: string) {
  fetch(url)
    .then(r => r.blob())
    .then(blob => {
      const u = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = u;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(u);
    })
    .catch(() => window.open(url, '_blank'));
}

/** 按图片数量生成对称扇形 transform（BounceCards 多图浏览用） */
function fanTransforms(count: number): string[] {
  const mid = (count - 1) / 2;
  return Array.from({ length: count }, (_, i) => {
    const offset = i - mid;
    const x = Math.round(offset * 85);
    const rot = -Math.round(offset * 5);
    return `rotate(${rot}deg) translate(${x}px)`;
  });
}

type PreviewTab = 'image' | 'video';

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

export function PreviewPanel() {
  const { scenes, selectedSceneId } = useProjectStore();
  const scene = scenes.find((s) => s.id === selectedSceneId);
  // 注意：selector 必须返回稳定引用（undefined/store 内已存数组），不能 `?? []`——每次新建数组
  // 会让 useSyncExternalStore 的 getSnapshot 不稳定 → React 无限重渲染（Maximum update depth exceeded）
  const rawSceneRefs = useProjectStore((s) => s.sceneRefs[selectedSceneId ?? '']);
  const sceneRefs = rawSceneRefs ?? [];
  const fetchSceneRefs = useProjectStore((s) => s.fetchSceneRefs);
  const uploadSceneRef = useProjectStore((s) => s.uploadSceneRef);
  const deleteSceneRef = useProjectStore((s) => s.deleteSceneRef);
  const setSceneParams = useProjectStore((s) => s.setSceneParams);
  const clearSceneParams = useProjectStore((s) => s.clearSceneParams);
  const generateImage = useProjectStore((s) => s.generateImage);
  const generateVideo = useProjectStore((s) => s.generateVideo);
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

  const [activeTab, setActiveTab] = useState<PreviewTab>('image');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null); // 图片点击放大预览（灯箱）
  const [selectedDownloads, setSelectedDownloads] = useState<Set<string>>(new Set()); // 勾选下载
  const [useRefImage, setUseRefImage] = useState(false); // 参考图生图勾选
  const [useFirstFrame, setUseFirstFrame] = useState(false); // 以本分镜图片为首帧
  const [refineTarget, setRefineTarget] = useState<string | null>(null); // 完善图片弹窗的源图 URL（null=关闭）
  const [sceneAssets, setSceneAssets] = useState<Asset[]>([]); // 本分镜关联资产
  const [assetPickerOpen, setAssetPickerOpen] = useState(false); // 资产选择弹窗
  const panelRef = useRef<HTMLDivElement>(null);
  const mediaLoadedRef = useRef<((e: React.SyntheticEvent<Element>) => void) | null>(null);
  const animatedSrcRef = useRef<string | null>(null);

  // ── 参数本地草稿（图片/视频分开）：控件只写草稿不发请求，点「保存参数」才一次性 PATCH，避免拖动 slider 实时打爆后端 ──
  const [imageDraft, setImageDraft] = useState<Record<string, unknown>>({});
  const [videoDraft, setVideoDraft] = useState<Record<string, unknown>>({});
  const [savingParams, setSavingParams] = useState(false);

  // 切换分镜时拉取该分镜参考素材
  useEffect(() => {
    if (selectedSceneId) fetchSceneRefs(selectedSceneId);
  }, [selectedSceneId, fetchSceneRefs]);

  // 切换分镜时拉取该分镜关联资产
  const loadSceneAssets = useCallback(async () => {
    if (!selectedSceneId) { setSceneAssets([]); return; }
    try {
      const res = await assetApi.listSceneAssets(selectedSceneId);
      setSceneAssets(res.data.data || []);
    } catch {
      setSceneAssets([]);
    }
  }, [selectedSceneId]);
  useEffect(() => { void loadSceneAssets(); }, [loadSceneAssets]);

  const removeSceneAsset = async (assetId: string) => {
    if (!selectedSceneId) return;
    const remaining = sceneAssets.filter((a) => a.id !== assetId);
    setSceneAssets(remaining);
    try {
      await assetApi.setSceneAssets(selectedSceneId, remaining.map((a) => a.id));
    } catch {
      void loadSceneAssets();
    }
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
    if (!scene) return [] as string[];
    const urls = scene.imageUrls ? scene.imageUrls.split(',').filter(Boolean) : [];
    return urls.length ? urls : (scene.imageUrl ? [scene.imageUrl] : []);
  }, [scene]);

  useEffect(() => {
    setSelectedDownloads(new Set());
    setImageDraft({});
    setVideoDraft({});
  }, [scene?.id]);

  // 生成参数（草稿 > 分镜覆盖 > 全局默认；空串/0 = 回退）
  const effImageModel = (imageDraft.imageModel as string) || scene?.imageModel || globalImageModel;
  const effVideoModel = (videoDraft.videoModel as string) || scene?.videoModel || globalVideoModel;
  const imageParams = parseParams(imageModelOptions.find((m) => m.value === effImageModel));
  const videoParams = parseParams(videoModelOptions.find((m) => m.value === effVideoModel));

  const sizeOptions = imageParams.sizes?.length ? imageParams.sizes : [...IMAGE_SIZES];
  const qualityOptions = imageParams.qualities?.length ? imageParams.qualities : [...IMAGE_QUALITIES];
  const effImageSize = (imageDraft.imageSize as string) || scene?.imageSize || imageParams.sizeDefault || globalImageSize;
  const effImageQuality = (imageDraft.imageQuality as string) || scene?.imageQuality || imageParams.qualityDefault || globalImageQuality;
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

  // 恢复全局默认：清空本分镜全部参数覆盖（图片+视频），并清空草稿
  const handleClearParams = async () => {
    if (!scene) return;
    setImageDraft({});
    setVideoDraft({});
    await clearSceneParams(scene.id);
  };

  const handleGenerateImage = async (mode?: 'edit', source?: string) => {
    if (!scene) return;
    const prompt = scene.imagePrompt || '';
    if (!prompt.trim()) { alert('请先填写生图提示词'); return; }
    const useEdit = mode === 'edit';
    const refs: string[] | undefined = useEdit && source === 'ref'
      ? refImages.map((r) => r.url)
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
    const prompt = scene.videoPrompt || '';
    if (!prompt.trim()) { alert('请先填写生视频提示词'); return; }
    // 先保存未保存的参数草稿再生成（保存失败则中止）
    if (!(await commitParams(videoDraft, () => setVideoDraft({})))) return;
    try {
      if (useFirstFrame && scene.imageUrl) {
        // 以本分镜图片为首帧（i2v）：参考素材与首帧互斥
        await generateVideo(scene.id, prompt, effVideoModel, undefined, scene.imageUrl);
      } else if (refVImages.length || refVideos.length || refAudios.length) {
        // 多模态参考（r2va）
        await generateVideo(
          scene.id, prompt, effVideoModel,
          refVImages.map((r) => r.url), undefined,
          refVideos.map((r) => r.url), refAudios.map((r) => r.url)
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

  // tab button style
  const tabStyle = (tab: PreviewTab): React.CSSProperties => ({
    padding: '6px 14px',
    fontSize: 12,
    fontWeight: 500,
    border: 'none',
    borderRadius: 'var(--rounded-sm)',
    cursor: 'pointer',
    background: activeTab === tab ? 'var(--color-surface-card)' : 'transparent',
    color: activeTab === tab ? 'var(--color-ink)' : 'var(--color-muted)',
  });

  const btnDownload: React.CSSProperties = {
    padding: '6px 14px',
    fontSize: 11,
    fontWeight: 500,
    borderRadius: 8,
    border: '1px solid var(--color-primary)',
    background: 'transparent',
    color: 'var(--color-primary)',
    cursor: 'pointer',
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
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
    Array.from(selectedDownloads).forEach((url, i) => {
      setTimeout(() => downloadAsset(assetUrl(url), `scene-${scene?.sceneNumber}-${i + 1}.png`), i * 150);
    });
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
        <span style={{ fontSize: 32, opacity: 0.3 }}>🎞️</span>
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
        background: 'white',
      }}
    >
      {/* Title */}
      <h2 style={{ font: 'var(--text-title-sm)', color: 'var(--color-ink)', marginBottom: 12 }}>
        预览 — 分镜 {scene.sceneNumber}
      </h2>

      {/* Script content */}
      {scene.scriptContent && (
        <div
          style={{
            padding: 10,
            borderRadius: 'var(--rounded-md)',
            background: 'var(--color-canvas)',
            fontSize: 13,
            color: 'var(--color-body)',
            lineHeight: 1.6,
            marginBottom: 12,
          }}
        >
          {scene.scriptContent}
        </div>
      )}

      {/* Tab bar */}
      <div style={{ display: 'flex', gap: 4, marginBottom: 12 }}>
        <button onClick={() => setActiveTab('image')} style={tabStyle('image')}>
          🖼️ 图片
        </button>
        <button onClick={() => setActiveTab('video')} style={tabStyle('video')}>
          🎬 视频
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
                  <BounceCards
                    images={imageList.map((u) => assetUrl(u))}
                    containerWidth={480}
                    containerHeight={260}
                    enableHover
                    transformStyles={fanTransforms(imageList.length)}
                    onImageClick={(url) => setPreviewUrl(url)}
                  />
                ) : (
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
                      ⬇ 下载当前
                    </button>
                  )}
                  {selectedDownloads.size > 0 && (
                    <button onClick={downloadSelected} style={btnDownload}>
                      ⬇ 下载选中 ({selectedDownloads.size})
                    </button>
                  )}
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
                {scene.imageStatus === 'generating' ? '⏳ 正在生成图片...' : '未生成图片'}
              </div>
            )}
          </div>

          {/* 图片提示词信息 */}
          {scene.imagePrompt && (
            <div style={{ marginBottom: 12, padding: 10, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-card)', fontSize: 12, lineHeight: 1.6 }}>
              <strong style={{ color: 'var(--color-muted)' }}>图片提示词：</strong>
              <p style={{ color: 'var(--color-body)', marginTop: 4, marginBottom: 0 }}>{scene.imagePrompt}</p>
            </div>
          )}

          {/* 生成参数区（只含图片生成相关信息） */}
          <div style={{ marginBottom: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>🖼️ 图片生成参数</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                {Object.keys(imageDraft).length > 0 && (
                  <span style={{ fontSize: 11, color: 'var(--color-warning)' }}>● 有未保存改动</span>
                )}
                <button
                  onClick={() => commitParams(imageDraft, () => setImageDraft({}))}
                  disabled={Object.keys(imageDraft).length === 0 || savingParams}
                  style={{
                    padding: '4px 14px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: 'none',
                    background: Object.keys(imageDraft).length === 0 ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
                    color: Object.keys(imageDraft).length === 0 ? 'var(--color-muted)' : 'white',
                    cursor: Object.keys(imageDraft).length === 0 ? 'not-allowed' : 'pointer',
                  }}
                >
                  💾 保存参数
                </button>
                <button
                  onClick={handleClearParams}
                  style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}
                  title="清空本分镜覆盖，跟随全局默认"
                >
                  恢复全局默认
                </button>
              </div>
            </div>

            <div style={fieldRow}>
              <span style={fieldLabel}>模型</span>
              <select
                value={effImageModel}
                onChange={(e) => setImageDraft((d) => ({ ...d, imageModel: e.target.value }))}
                style={selectStyle}
              >
                {imageModelOptions.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
              </select>
              {!scene.imageModel && imageDraft.imageModel === undefined && <span style={{ fontSize: 10, color: 'var(--color-muted-soft)' }}>跟随全局</span>}
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>尺寸</span>
              <select value={effImageSize} onChange={(e) => setImageDraft((d) => ({ ...d, imageSize: e.target.value }))} style={selectStyle}>
                {sizeOptions.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>质量</span>
              <select value={effImageQuality} onChange={(e) => setImageDraft((d) => ({ ...d, imageQuality: e.target.value }))} style={selectStyle}>
                {qualityOptions.map((q) => <option key={q} value={q}>{q}</option>)}
              </select>
            </div>
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
                {generatingImage ? '⏳ 生成中...' : '🖼️ 生成图片'}
              </SpecularButton>
              {scene.imageUrl && (
                <button
                  onClick={() => setRefineTarget(scene.imageUrl)}
                  style={{ ...btnGhost, cursor: 'pointer' }}
                >
                  ✨ 完善图片
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
                <button onClick={() => downloadAsset(assetUrl(scene.videoUrl), `scene-${scene.sceneNumber}.mp4`)} style={btnDownload}>
                  ⬇ 下载视频
                </button>
              </div>
            ) : (
              <div
                style={{
                  width: '100%', height: 200, borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-surface-soft)', color: 'var(--color-muted-soft)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13,
                }}
              >
                {scene.videoStatus === 'generating' ? '⏳ 正在生成视频...' : '未生成视频'}
              </div>
            )}
          </div>

          {/* 视频提示词信息 */}
          {scene.videoPrompt && (
            <div style={{ marginBottom: 12, padding: 10, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-card)', fontSize: 12, lineHeight: 1.6 }}>
              <strong style={{ color: 'var(--color-muted)' }}>视频提示词：</strong>
              <p style={{ color: 'var(--color-body)', marginTop: 4, marginBottom: 0 }}>{scene.videoPrompt}</p>
            </div>
          )}

          {/* 生成参数区（只含视频生成相关信息） */}
          <div style={{ marginBottom: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>🎬 视频生成参数</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                {Object.keys(videoDraft).length > 0 && (
                  <span style={{ fontSize: 11, color: 'var(--color-warning)' }}>● 有未保存改动</span>
                )}
                <button
                  onClick={() => commitParams(videoDraft, () => setVideoDraft({}))}
                  disabled={Object.keys(videoDraft).length === 0 || savingParams}
                  style={{
                    padding: '4px 14px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: 'none',
                    background: Object.keys(videoDraft).length === 0 ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
                    color: Object.keys(videoDraft).length === 0 ? 'var(--color-muted)' : 'white',
                    cursor: Object.keys(videoDraft).length === 0 ? 'not-allowed' : 'pointer',
                  }}
                >
                  💾 保存参数
                </button>
                <button
                  onClick={handleClearParams}
                  style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}
                  title="清空本分镜覆盖，跟随全局默认"
                >
                  恢复全局默认
                </button>
              </div>
            </div>

            <div style={fieldRow}>
              <span style={fieldLabel}>模型</span>
              <select value={effVideoModel} onChange={(e) => setVideoDraft((d) => ({ ...d, videoModel: e.target.value }))} style={selectStyle}>
                {videoModelOptions.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
              </select>
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
              <select value={effVideoResolution} onChange={(e) => setVideoDraft((d) => ({ ...d, videoResolution: e.target.value }))} style={selectStyle}>
                {resolutionOptions.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>画幅</span>
              <select value={effVideoAspect} onChange={(e) => setVideoDraft((d) => ({ ...d, videoAspectRatio: e.target.value }))} style={selectStyle}>
                {aspectOptions.map((a) => <option key={a} value={a}>{a}</option>)}
              </select>
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
                disabled={useFirstFrame}
              />
              <ReferenceUploader
                type="video"
                items={refVideos}
                maxCount={videoParams.refVideoMax}
                maxSizeMB={videoParams.refVideoSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'video', 'video', f)}
                onDelete={(id) => deleteSceneRef(scene.id, id)}
                disabled={useFirstFrame}
              />
              <ReferenceUploader
                type="audio"
                items={refAudios}
                maxCount={videoParams.refAudioMax}
                maxSizeMB={videoParams.refAudioSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'audio', 'video', f)}
                onDelete={(id) => deleteSceneRef(scene.id, id)}
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
                {generatingVideo ? '⏳ 生成中...' : '🎬 生成视频'}
              </SpecularButton>
            </div>
          </div>
        </div>
      )}

      {/* 镜头信息 */}
      {(scene.cameraMovement || scene.shotType) && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 4 }}>
          {scene.cameraMovement && (
            <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 'var(--rounded-sm)', background: 'var(--color-surface-soft)', color: 'var(--color-muted)' }}>
              🎥 {scene.cameraMovement}
            </span>
          )}
          {scene.shotType && (
            <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 'var(--rounded-sm)', background: 'var(--color-surface-soft)', color: 'var(--color-muted)' }}>
              📐 {scene.shotType}
            </span>
          )}
        </div>
      )}

      {/* 关联资产（本分镜引用的人物/道具/场景设定，生成时注入） */}
      <div style={{ marginTop: 12, padding: 12, borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>🧩 关联资产（生成时注入设定与参考图）</span>
        {sceneAssets.length === 0 ? (
          <div style={{ fontSize: 11, color: 'var(--color-muted-soft)', lineHeight: 1.6, marginTop: 8 }}>
            未关联资产——关联后生成分镜/图片/视频时，会自动注入资产设定文字与参考图，保持人物/道具/场景跨分镜一致。
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
            {sceneAssets.map((a) => (
              <span key={a.id} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 6px 3px 4px', borderRadius: 999, border: '1px solid var(--color-hairline)', background: 'white', fontSize: 12, color: 'var(--color-ink)' }}>
                {a.images[0] ? (
                  <img src={assetUrl(a.images[0].url)} alt="" style={{ width: 20, height: 20, objectFit: 'cover', borderRadius: 4 }} />
                ) : (
                  <span style={{ width: 20, height: 20, borderRadius: 4, background: 'var(--color-surface-card)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 10 }}>🧩</span>
                )}
                {a.name}
                <button
                  onClick={() => void removeSceneAsset(a.id)}
                  style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--color-muted-soft)', fontSize: 12, padding: 0, lineHeight: 1 }}
                  title="取消关联"
                >
                  ✕
                </button>
              </span>
            ))}
          </div>
        )}
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 10 }}>
          <button
            onClick={() => setAssetPickerOpen(true)}
            style={{ padding: '6px 18px', fontSize: 12, borderRadius: 'var(--rounded-sm)', border: '1px solid var(--color-primary)', background: 'transparent', color: 'var(--color-primary)', cursor: 'pointer', fontWeight: 600 }}
          >
            ＋ 添加资产
          </button>
        </div>
      </div>
      {assetPickerOpen && (
        <AssetLibraryPanel mode="pick" onClose={() => { setAssetPickerOpen(false); void loadSceneAssets(); }} />
      )}

      {/* 图片点击放大预览（灯箱，与智能体窗口行为一致） */}
      <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
      {/* 完善图片弹窗：以当前图为源图做图改图（诉求输入后生成） */}
      {refineTarget && scene && (
        <RefineImageModal sceneId={scene.id} imageUrl={refineTarget} onClose={() => setRefineTarget(null)} />
      )}
    </div>
  );
}
