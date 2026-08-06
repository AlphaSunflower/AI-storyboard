import { useState, useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from '../agent/ImagePreviewModal';

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

export function PreviewPanel() {
  const { scenes, selectedSceneId } = useProjectStore();
  const scene = scenes.find((s) => s.id === selectedSceneId);
  const getSceneRefs = useProjectStore((s) => s.getSceneRefs);
  const setSceneRefs = useProjectStore((s) => s.setSceneRefs);
  const refs = scene ? getSceneRefs(scene.id) : { images: [] as string[], useForImage: true, useForVideo: true };
  const refInputRef = useRef<HTMLInputElement>(null);
  const [activeTab, setActiveTab] = useState<PreviewTab>('image');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null); // 图片点击放大预览（灯箱）
  const panelRef = useRef<HTMLDivElement>(null);
  const mediaLoadedRef = useRef<((e: React.SyntheticEvent<Element>) => void) | null>(null);
  // A2 防重放：记录已冲印过的媒体 src，同一张图切 tab/切回不重复播放
  const animatedSrcRef = useRef<string | null>(null);

  // 稳定的 handler 引用：首帧渲染即挂上，内部转发到 contextSafe 包装的动画（layout effect 后生效）
  const handleMediaLoaded = (e: React.SyntheticEvent<Element>) => {
    mediaLoadedRef.current?.(e);
  };

  // 切换分镜时内容淡入 + 媒体加载完成"冲印"效果（模糊显影 → 清晰）
  useGSAP((_context, contextSafe) => {
    if (!panelRef.current || !scene) return;
    // 整个预览区从下往上淡入
    gsap.fromTo(
      panelRef.current,
      { opacity: 0, y: 12 },
      {
        opacity: 1, y: 0, duration: 0.32, ease: 'power2.out',
        onComplete: () => {
          // 清除残留 transform：panelRef 作为 containing block 会让内部 fixed 灯箱（ImagePreviewModal）错位
          gsap.set(panelRef.current, { clearProps: 'transform' });
        },
      }
    );
    // 媒体加载完成：A2 冲印动画（blur 10px → 0 + 轻微放大归位），contextSafe 保证卸载后不再执行
    mediaLoadedRef.current = contextSafe?.((e: React.SyntheticEvent<Element>) => {
      const el = e.currentTarget as HTMLElement;
      const src = el.getAttribute('src') ?? '';
      if (animatedSrcRef.current === src) return; // 同一张图不重放
      animatedSrcRef.current = src;
      gsap.fromTo(
        el,
        { filter: 'blur(10px)', opacity: 0.3, scale: 1.06 },
        { filter: 'blur(0px)', opacity: 1, scale: 1, duration: 0.7, ease: 'power2.out' }
      );
    }) ?? null;
  }, { dependencies: [scene?.id, activeTab], scope: panelRef });

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

  // download button style
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

  // Empty selection state
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
      <h2
        style={{
          font: 'var(--text-title-sm)',
          color: 'var(--color-ink)',
          marginBottom: 12,
        }}
      >
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
      <div style={{ display: 'flex', gap: 4, marginBottom: 8 }}>
        <button onClick={() => setActiveTab('image')} style={tabStyle('image')}>
          🖼️ 图片预览
        </button>
        <button onClick={() => setActiveTab('video')} style={tabStyle('video')}>
          🎬 视频预览
        </button>
      </div>

      {/* Image tab */}
      {activeTab === 'image' && (
        <div style={{ marginBottom: 12 }}>
          {scene.imageUrl ? (
            <div>
              <img
                src={assetUrl(scene.imageUrl)}
                alt={`分镜 ${scene.sceneNumber} 预览`}
                onClick={() => setPreviewUrl(scene.imageUrl)}
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
              <button onClick={() => downloadAsset(assetUrl(scene.imageUrl), `scene-${scene.sceneNumber}.png`)} style={btnDownload}>
                ⬇ 下载图片
              </button>
            </div>
          ) : (
            <div
              style={{
                width: '100%',
                height: 200,
                borderRadius: 'var(--rounded-md)',
                background: 'var(--color-surface-soft)',
                color: 'var(--color-muted-soft)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 13,
                gap: 6,
              }}
            >
              {scene.imageStatus === 'generating' ? '⏳ 正在生成图片...' : '未生成图片'}
            </div>
          )}
        </div>
      )}

      {/* Video tab */}
      {activeTab === 'video' && (
        <div style={{ marginBottom: 12 }}>
          {scene.videoUrl ? (
            <div>
              <video
                src={assetUrl(scene.videoUrl)}
                controls
                onLoadedData={handleMediaLoaded}
                style={{
                  width: '100%',
                  maxHeight: 360,
                  borderRadius: 'var(--rounded-md)',
                  border: '1px solid var(--color-hairline)',
                  background: '#000',
                  marginBottom: 8,
                }}
              />
              <button onClick={() => downloadAsset(assetUrl(scene.videoUrl), `scene-${scene.sceneNumber}.mp4`)} style={btnDownload}>
                ⬇ 下载视频
              </button>
            </div>
          ) : (
            <div
              style={{
                width: '100%',
                height: 200,
                borderRadius: 'var(--rounded-md)',
                background: 'var(--color-surface-soft)',
                color: 'var(--color-muted-soft)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 13,
                gap: 6,
              }}
            >
              {scene.videoStatus === 'generating' ? '⏳ 正在生成视频...' : '未生成视频'}
            </div>
          )}
        </div>
      )}

      {/* Reference image controls */}
      {scene && (
        <div style={{ marginBottom: 12, padding: '10px', borderRadius: 'var(--rounded-md)', border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)' }}>
          <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)', marginBottom: 8 }}>🖼️ 参考图</div>
          <div
            onClick={() => refInputRef.current?.click()}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '6px 10px', borderRadius: 'var(--rounded-md)',
              border: '1px dashed var(--color-hairline)', cursor: 'pointer',
              background: 'var(--color-surface-card)', fontSize: 11,
              color: 'var(--color-muted)',
            }}
          >
            <span style={{ fontSize: 14 }}>🖼️</span>
            <span>{refs.images.length > 0 ? `${refs.images.length}/1 张参考图` : '添加参考图（可选，仅1张）'}</span>
          </div>
          <input ref={refInputRef} type="file" accept="image/*" multiple hidden
            onChange={(e) => {
              const files = Array.from(e.target.files || []);
              if (refs.images.length + files.length > 1) { alert('最多1张参考图'); return; }
              files.forEach(f => {
                const reader = new FileReader();
                reader.onload = () => setSceneRefs(scene.id, { ...refs, images: [...refs.images, reader.result as string] });
                reader.readAsDataURL(f);
              });
            }} />
          {refs.images.length > 0 && (
            <div style={{ display: 'flex', gap: 4, marginTop: 6, flexWrap: 'wrap' }}>
              {refs.images.map((url, i) => (
                <div key={i} style={{ position: 'relative' }}>
                  <img src={url} style={{ width: 48, height: 48, borderRadius: 4, objectFit: 'cover' }} />
                  <span onClick={() => setSceneRefs(scene.id, { ...refs, images: refs.images.filter((_, j) => j !== i) })}
                    style={{ position: 'absolute', top: -4, right: -4, background: 'var(--color-error)', color: 'white', borderRadius: '50%', width: 16, height: 16, fontSize: 10, display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer' }}>×</span>
                </div>
              ))}
            </div>
          )}
          <div style={{ marginTop: 8, display: 'flex', gap: 16 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer' }}>
              <input type="checkbox" checked={refs.useForImage} onChange={e => setSceneRefs(scene.id, { ...refs, useForImage: e.target.checked })}
                style={{ margin: 0, cursor: 'pointer' }} />
              参考图生图
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--color-muted)', cursor: 'pointer' }}>
              <input type="checkbox" checked={refs.useForVideo} onChange={e => setSceneRefs(scene.id, { ...refs, useForVideo: e.target.checked })}
                style={{ margin: 0, cursor: 'pointer' }} />
              参考图生视频
            </label>
          </div>
        </div>
      )}

      {/* Image prompt */}
      {scene.imagePrompt && (
        <div
          style={{
            marginBottom: 8,
            padding: 10,
            borderRadius: 'var(--rounded-md)',
            background: 'var(--color-surface-card)',
            fontSize: 12,
            lineHeight: 1.6,
          }}
        >
          <strong style={{ color: 'var(--color-muted)' }}>图片提示词：</strong>
          <p style={{ color: 'var(--color-body)', marginTop: 4 }}>{scene.imagePrompt}</p>
        </div>
      )}

      {/* Video prompt */}
      {scene.videoPrompt && (
        <div
          style={{
            marginBottom: 8,
            padding: 10,
            borderRadius: 'var(--rounded-md)',
            background: 'var(--color-surface-card)',
            fontSize: 12,
            lineHeight: 1.6,
          }}
        >
          <strong style={{ color: 'var(--color-muted)' }}>视频提示词：</strong>
          <p style={{ color: 'var(--color-body)', marginTop: 4 }}>{scene.videoPrompt}</p>
        </div>
      )}

      {/* Camera / shot metadata */}
      {(scene.cameraMovement || scene.shotType || (scene.soundDesign && !scene.soundDesign.startsWith('{'))) && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 4 }}>
          {scene.cameraMovement && (
            <span
              style={{
                fontSize: 11,
                padding: '2px 8px',
                borderRadius: 'var(--rounded-sm)',
                background: 'var(--color-surface-soft)',
                color: 'var(--color-muted)',
              }}
            >
              🎥 {scene.cameraMovement}
            </span>
          )}
          {scene.shotType && (
            <span
              style={{
                fontSize: 11,
                padding: '2px 8px',
                borderRadius: 'var(--rounded-sm)',
                background: 'var(--color-surface-soft)',
                color: 'var(--color-muted)',
              }}
            >
              📐 {scene.shotType}
            </span>
          )}
          {scene.soundDesign && !scene.soundDesign.startsWith('{') && (
            <span
              style={{
                fontSize: 11,
                padding: '2px 8px',
                borderRadius: 'var(--rounded-sm)',
                background: 'var(--color-surface-soft)',
                color: 'var(--color-muted)',
              }}
            >
              🔊 {scene.soundDesign}
            </span>
          )}
        </div>
      )}

      {/* 图片点击放大预览（灯箱，与智能体窗口行为一致） */}
      <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
    </div>
  );
}
