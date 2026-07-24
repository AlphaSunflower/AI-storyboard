import { useProjectStore } from '../../stores/projectStore';

const BACKEND = 'http://localhost:8082';

function assetUrl(path: string | null) {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  return BACKEND + path;
}

export function PreviewPanel() {
  const { scenes, selectedSceneId } = useProjectStore();
  const scene = scenes.find((s) => s.id === selectedSceneId);

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

      {/* Image preview */}
      <div style={{ marginBottom: 12 }}>
        <p
          style={{
            fontSize: 12,
            color: 'var(--color-muted)',
            marginBottom: 6,
            fontWeight: 500,
          }}
        >
          图片预览
        </p>
        {scene.imageUrl ? (
          <img
            src={assetUrl(scene.imageUrl)}
            alt={`分镜 ${scene.sceneNumber} 预览`}
            style={{
              width: '100%',
              maxHeight: 400,
              objectFit: 'contain',
              borderRadius: 'var(--rounded-md)',
              border: '1px solid var(--color-hairline)',
              background: 'var(--color-canvas)',
            }}
          />
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

      {/* Video preview */}
      {scene.videoUrl && (
        <div style={{ marginBottom: 12 }}>
          <p
            style={{
              fontSize: 12,
              color: 'var(--color-muted)',
              marginBottom: 6,
              fontWeight: 500,
            }}
          >
            视频预览
          </p>
          <video
            src={assetUrl(scene.videoUrl)}
            controls
            style={{
              width: '100%',
              maxHeight: 400,
              borderRadius: 'var(--rounded-md)',
              border: '1px solid var(--color-hairline)',
              background: '#000',
            }}
          />
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
