import { useEffect, useMemo, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from '../agent/ImagePreviewModal';
import { ReferenceUploader } from './ReferenceUploader';
import { resolveVideoPreset } from '../common/VideoPresetSelector';
import { IMAGE_SIZES, IMAGE_QUALITIES } from '../../config';

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

const btnPrimary: React.CSSProperties = {
  padding: '7px 18px',
  fontSize: 12,
  borderRadius: 'var(--rounded-sm)',
  border: 'none',
  background: 'var(--color-primary)',
  color: 'var(--color-on-primary)',
  cursor: 'pointer',
  fontWeight: 600,
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
  const sceneRefs = useProjectStore((s) => s.sceneRefs[selectedSceneId ?? ''] ?? []);
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
  const [imgIndex, setImgIndex] = useState(0); // 多图轮播当前索引
  const [selectedDownloads, setSelectedDownloads] = useState<Set<string>>(new Set()); // 勾选下载
  const [useRefImage, setUseRefImage] = useState(false); // 参考图生图勾选
  const [useFirstFrame, setUseFirstFrame] = useState(false); // 以本分镜图片为首帧
  const panelRef = useRef<HTMLDivElement>(null);
  const mediaLoadedRef = useRef<((e: React.SyntheticEvent<Element>) => void) | null>(null);
  const animatedSrcRef = useRef<string | null>(null);

  // 切换分镜时拉取该分镜参考素材
  useEffect(() => {
    if (selectedSceneId) fetchSceneRefs(selectedSceneId);
  }, [selectedSceneId, fetchSceneRefs]);

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

  useEffect(() => { setImgIndex(0); setSelectedDownloads(new Set()); }, [scene?.id]);

  // 生成参数（分镜覆盖优先：空串/0 = 回退全局默认）
  const effImageModel = scene?.imageModel || globalImageModel;
  const effVideoModel = scene?.videoModel || globalVideoModel;
  const imageParams = parseParams(imageModelOptions.find((m) => m.value === effImageModel));
  const videoParams = parseParams(videoModelOptions.find((m) => m.value === effVideoModel));

  const sizeOptions = imageParams.sizes?.length ? imageParams.sizes : [...IMAGE_SIZES];
  const qualityOptions = imageParams.qualities?.length ? imageParams.qualities : [...IMAGE_QUALITIES];
  const effImageSize = scene?.imageSize || imageParams.sizeDefault || globalImageSize;
  const effImageQuality = scene?.imageQuality || imageParams.qualityDefault || globalImageQuality;
  const effImageN = scene?.imageN || imageParams.nRange?.default || globalImageN;
  const nOptions = imageParams.nRange
    ? Array.from({ length: Math.max(0, (imageParams.nRange.max ?? effImageN) - (imageParams.nRange.min ?? 1) + 1) }, (_, i) => (imageParams.nRange!.min ?? 1) + i)
    : [];

  const preset = resolveVideoPreset(globalVideoPreset);
  const effVideoDuration = scene?.duration || videoParams.durationDefault || parseInt(preset.duration);
  const effVideoResolution = scene?.videoResolution || videoParams.resolutionDefault || preset.resolution;
  const effVideoAspect = scene?.videoAspectRatio || videoParams.aspectRatioDefault || preset.aspectRatio;
  const durationOptions = videoParams.durations?.length ? videoParams.durations : [4, 6, 8];
  const resolutionOptions = videoParams.resolutions?.length ? videoParams.resolutions : ['768P', '2K'];
  const aspectOptions = videoParams.aspectRatios?.length ? videoParams.aspectRatios : ['16:9', '9:16', '4:3', '1:1', '21:9'];

  // 参考素材按类型分组
  const refImages = sceneRefs.filter((r) => r.type === 'image');
  const refVideos = sceneRefs.filter((r) => r.type === 'video');
  const refAudios = sceneRefs.filter((r) => r.type === 'audio');

  const handleGenerateImage = async (mode?: 'edit', source?: string) => {
    if (!scene) return;
    const prompt = scene.imagePrompt || '';
    if (!prompt.trim()) { alert('请先填写生图提示词'); return; }
    const useEdit = mode === 'edit';
    const refs: string[] | undefined = useEdit && source === 'ref'
      ? refImages.map((r) => r.url)
      : undefined;
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
    try {
      if (useFirstFrame && scene.imageUrl) {
        // 以本分镜图片为首帧（i2v）：参考素材与首帧互斥
        await generateVideo(scene.id, prompt, effVideoModel, undefined, scene.imageUrl);
      } else if (refImages.length || refVideos.length || refAudios.length) {
        // 多模态参考（r2va）
        await generateVideo(
          scene.id, prompt, effVideoModel,
          refImages.map((r) => r.url), undefined,
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
    padding: '5px 12px',
    fontSize: 11,
    borderRadius: 'var(--rounded-sm)',
    border: '1px solid var(--color-hairline)',
    background: 'var(--color-canvas)',
    color: 'var(--color-body-strong)',
    cursor: 'pointer',
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
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
                <div style={{ position: 'relative' }}>
                  <img
                    src={assetUrl(imageList[imgIndex])}
                    alt={`分镜 ${scene.sceneNumber} 生成图 ${imgIndex + 1}`}
                    onClick={() => setPreviewUrl(imageList[imgIndex])}
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
                  {imageList.length > 1 && (
                    <>
                      <button
                        onClick={() => setImgIndex((i) => (i - 1 + imageList.length) % imageList.length)}
                        style={{ position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)', ...btnDownload, background: 'rgba(255,255,255,0.85)' }}
                      >
                        ◀
                      </button>
                      <button
                        onClick={() => setImgIndex((i) => (i + 1) % imageList.length)}
                        style={{ position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)', ...btnDownload, background: 'rgba(255,255,255,0.85)' }}
                      >
                        ▶
                      </button>
                    </>
                  )}
                </div>
                {imageList.length > 1 && (
                  <div style={{ display: 'flex', gap: 4, justifyContent: 'center', marginBottom: 8 }}>
                    {imageList.map((_, i) => (
                      <span
                        key={i}
                        onClick={() => setImgIndex(i)}
                        style={{
                          width: 8, height: 8, borderRadius: '50%', cursor: 'pointer',
                          background: i === imgIndex ? 'var(--color-primary)' : 'var(--color-hairline)',
                        }}
                      />
                    ))}
                  </div>
                )}
                {/* 选择性下载 */}
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 4 }}>
                  {imageList.map((url, i) => (
                    <label key={url} style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer' }}>
                      <input type="checkbox" checked={selectedDownloads.has(url)} onChange={() => toggleDownload(url)} style={{ margin: 0, cursor: 'pointer' }} />
                      图 {i + 1}
                    </label>
                  ))}
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button onClick={() => downloadAsset(assetUrl(imageList[imgIndex]), `scene-${scene.sceneNumber}.png`)} style={btnDownload}>
                    ⬇ 下载当前
                  </button>
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
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>🖼️ 图片生成参数</span>
              <button
                onClick={() => clearSceneParams(scene.id)}
                style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}
                title="清空本分镜覆盖，跟随全局默认"
              >
                恢复全局默认
              </button>
            </div>

            <div style={fieldRow}>
              <span style={fieldLabel}>模型</span>
              <select
                value={effImageModel}
                onChange={(e) => setSceneParams(scene.id, { imageModel: e.target.value })}
                style={selectStyle}
              >
                {imageModelOptions.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
              </select>
              {!scene.imageModel && <span style={{ fontSize: 10, color: 'var(--color-muted-soft)' }}>跟随全局</span>}
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>尺寸</span>
              <select value={effImageSize} onChange={(e) => setSceneParams(scene.id, { imageSize: e.target.value })} style={selectStyle}>
                {sizeOptions.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>质量</span>
              <select value={effImageQuality} onChange={(e) => setSceneParams(scene.id, { imageQuality: e.target.value })} style={selectStyle}>
                {qualityOptions.map((q) => <option key={q} value={q}>{q}</option>)}
              </select>
            </div>
            {nOptions.length > 0 && (
              <div style={fieldRow}>
                <span style={fieldLabel}>生成个数</span>
                <select value={effImageN} onChange={(e) => setSceneParams(scene.id, { imageN: Number(e.target.value) })} style={selectStyle}>
                  {nOptions.map((v) => <option key={v} value={v}>{v}</option>)}
                </select>
              </div>
            )}

            {/* 参考图生图：勾选后展开模型指定 + 上传入口 */}
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer', marginBottom: useRefImage ? 8 : 0 }}>
              <input type="checkbox" checked={useRefImage} onChange={(e) => setUseRefImage(e.target.checked)} style={{ margin: 0, cursor: 'pointer' }} />
              参考图生图（以参考图为源图改图）
            </label>
            {useRefImage && (
              <div style={{ marginBottom: 8 }}>
                <ReferenceUploader
                  type="image"
                  items={refImages}
                  maxCount={imageParams.refImageMax}
                  maxSizeMB={imageParams.refImageSizeMB}
                  onUpload={(f) => uploadSceneRef(scene.id, 'image', f)}
                  onDelete={(id) => deleteSceneRef(scene.id, id)}
                />
              </div>
            )}

            <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
              <button
                disabled={generatingImage}
                onClick={() => handleGenerateImage(useRefImage && refImages.length ? 'edit' : undefined, useRefImage && refImages.length ? 'ref' : undefined)}
                style={{ ...btnPrimary, opacity: generatingImage ? 0.6 : 1, cursor: generatingImage ? 'not-allowed' : 'pointer' }}
              >
                {generatingImage ? '⏳ 生成中...' : '🖼️ 生成图片'}
              </button>
              {scene.imageUrl && (
                <button
                  disabled={generatingImage}
                  onClick={() => handleGenerateImage('edit', 'current')}
                  style={{ ...btnGhost, opacity: generatingImage ? 0.6 : 1, cursor: generatingImage ? 'not-allowed' : 'pointer' }}
                >
                  完善图片（以当前图为源）
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
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>🎬 视频生成参数</span>
              <button
                onClick={() => clearSceneParams(scene.id)}
                style={{ background: 'none', border: 'none', fontSize: 11, color: 'var(--color-primary)', cursor: 'pointer', padding: 0 }}
                title="清空本分镜覆盖，跟随全局默认"
              >
                恢复全局默认
              </button>
            </div>

            <div style={fieldRow}>
              <span style={fieldLabel}>模型</span>
              <select value={effVideoModel} onChange={(e) => setSceneParams(scene.id, { videoModel: e.target.value })} style={selectStyle}>
                {videoModelOptions.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
              </select>
              {!scene.videoModel && <span style={{ fontSize: 10, color: 'var(--color-muted-soft)' }}>跟随全局</span>}
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>时长(秒)</span>
              <select value={effVideoDuration} onChange={(e) => setSceneParams(scene.id, { duration: Number(e.target.value) })} style={selectStyle}>
                {durationOptions.map((d) => <option key={d} value={d}>{d}</option>)}
              </select>
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>分辨率</span>
              <select value={effVideoResolution} onChange={(e) => setSceneParams(scene.id, { videoResolution: e.target.value })} style={selectStyle}>
                {resolutionOptions.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div style={fieldRow}>
              <span style={fieldLabel}>画幅</span>
              <select value={effVideoAspect} onChange={(e) => setSceneParams(scene.id, { videoAspectRatio: e.target.value })} style={selectStyle}>
                {aspectOptions.map((a) => <option key={a} value={a}>{a}</option>)}
              </select>
            </div>

            {/* 参考素材区（r2va 多模态参考）：与首帧互斥 */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
              <ReferenceUploader
                type="image"
                items={refImages}
                maxCount={videoParams.refImageMax}
                maxSizeMB={videoParams.refImageSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'image', f)}
                onDelete={(id) => deleteSceneRef(scene.id, id)}
                disabled={useFirstFrame}
              />
              <ReferenceUploader
                type="video"
                items={refVideos}
                maxCount={videoParams.refVideoMax}
                maxSizeMB={videoParams.refVideoSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'video', f)}
                onDelete={(id) => deleteSceneRef(scene.id, id)}
                disabled={useFirstFrame}
              />
              <ReferenceUploader
                type="audio"
                items={refAudios}
                maxCount={videoParams.refAudioMax}
                maxSizeMB={videoParams.refAudioSizeMB}
                onUpload={(f) => uploadSceneRef(scene.id, 'audio', f)}
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
                  style={{ margin: 0, cursor: 'pointer' }}
                />
                以本分镜图片为首帧（参考素材与首帧互斥）
              </label>
            )}

            <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
              <button
                disabled={generatingVideo}
                onClick={handleGenerateVideo}
                style={{ ...btnPrimary, opacity: generatingVideo ? 0.6 : 1, cursor: generatingVideo ? 'not-allowed' : 'pointer' }}
              >
                {generatingVideo ? '⏳ 生成中...' : '🎬 生成视频'}
              </button>
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

      {/* 图片点击放大预览（灯箱，与智能体窗口行为一致） */}
      <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
    </div>
  );
}
