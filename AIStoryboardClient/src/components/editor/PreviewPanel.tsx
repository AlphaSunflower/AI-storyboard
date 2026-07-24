import { useState } from 'react';
import { useProjectStore } from '../../stores/projectStore';

const BACKEND = 'http://localhost:8082';

function assetUrl(path: string | null) {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  return BACKEND + path;
}

type PreviewTab = 'image' | 'video';

export function PreviewPanel() {
  const { scenes, selectedSceneId } = useProjectStore();
  const scene = scenes.find((s) => s.id === selectedSceneId);
  const [activeTab, setActiveTab] = useState<PreviewTab>('image');

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
                style={{
                  width: '100%',
                  maxHeight: 360,
                  objectFit: 'contain',
                  borderRadius: 'var(--rounded-md)',
                  border: '1px solid var(--color-hairline)',
                  background: 'var(--color-canvas)',
                  marginBottom: 8,
                }}
              />
              <a href={assetUrl(scene.imageUrl)} download style={{ ...btnDownload, textDecoration: 'none' }}>
                ⬇ 下载图片
              </a>
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
                style={{
                  width: '100%',
                  maxHeight: 360,
                  borderRadius: 'var(--rounded-md)',
                  border: '1px solid var(--color-hairline)',
                  background: '#000',
                  marginBottom: 8,
                }}
              />
              <a href={assetUrl(scene.videoUrl)} download style={{ ...btnDownload, textDecoration: 'none' }}>
                ⬇ 下载视频
              </a>
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
      {(scene.cameraMovement || scene.shotType || scene.soundDesign) && (
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
          {scene.soundDesign && (
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
    </div>
  );
}
