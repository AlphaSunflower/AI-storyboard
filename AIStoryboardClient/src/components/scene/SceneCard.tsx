import type { SceneResponse } from '../../api/projects';
import { useProjectStore } from '../../stores/projectStore';

function Tag({ children }: { children: string }) {
  return (
    <span
      style={{
        fontSize: 10,
        padding: '1px 6px',
        borderRadius: 'var(--rounded-sm)',
        background: 'var(--color-surface-card)',
        color: 'var(--color-muted)',
        lineHeight: 1.6,
      }}
    >
      {children}
    </span>
  );
}

function getImageLabel(status: string, generating: boolean): string {
  if (generating) return '⏳生成中';
  switch (status) {
    case 'pending':
      return '生成图片';
    case 'generating':
      return '⏳生成中';
    case 'completed':
      return '完善图片';
    case 'failed':
      return '重试';
    default:
      return '生成图片';
  }
}

function getVideoLabel(status: string, generating: boolean): string {
  if (generating) return '⏳生成中';
  switch (status) {
    case 'pending':
      return '生成视频';
    case 'generating':
      return '⏳生成中';
    case 'completed':
      return '完善视频';
    case 'failed':
      return '重试';
    default:
      return '生成视频';
  }
}

function actionBtnStyle(status: string, generating?: boolean): React.CSSProperties {
  const isDone = status === 'completed';
  return {
    padding: '4px 8px',
    fontSize: 10,
    borderRadius: 'var(--rounded-sm)',
    border: isDone ? '1px solid var(--color-primary)' : 'none',
    background: isDone ? 'transparent' : 'var(--color-primary)',
    color: isDone ? 'var(--color-primary)' : 'var(--color-on-primary)',
    cursor: generating ? 'not-allowed' : 'pointer',
    opacity: generating ? 0.7 : 1,
  };
}

export function SceneCard({
  scene,
  isSelected,
  onSelect,
}: {
  scene: SceneResponse;
  isSelected: boolean;
  onSelect: () => void;
}) {
  const generatingImage = useProjectStore((s) => s.generatingImage[scene.id]);
  const generatingVideo = useProjectStore((s) => s.generatingVideo[scene.id]);

  const imageLabel = getImageLabel(scene.imageStatus, !!generatingImage);
  const videoLabel = getVideoLabel(scene.videoStatus, !!generatingVideo);

  return (
    <div
      onClick={onSelect}
      style={{
        padding: 12,
        borderRadius: 'var(--rounded-md)',
        border: isSelected ? '2px solid var(--color-primary)' : '1px solid var(--color-hairline)',
        borderLeft: `3px solid ${isSelected ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
        background: isSelected ? 'var(--color-surface-card)' : 'white',
        cursor: 'pointer',
        marginBottom: 8,
        transition: 'border-color 0.15s',
      }}
    >
      {/* Scene number + summary */}
      <div
        style={{
          fontWeight: 600,
          fontSize: 13,
          color: 'var(--color-ink)',
          marginBottom: 4,
        }}
      >
        分镜 {scene.sceneNumber}
      </div>
      <div
        style={{
          fontSize: 12,
          color: 'var(--color-muted)',
          lineHeight: 1.4,
          marginBottom: 6,
        }}
      >
        {scene.scriptContent?.slice(0, 80) || '空分镜'}
      </div>

      {/* Tags */}
      <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 6 }}>
        {scene.cameraMovement && <Tag>{scene.cameraMovement}</Tag>}
        {scene.shotType && <Tag>{scene.shotType}</Tag>}
        {scene.soundDesign && <Tag>{scene.soundDesign}</Tag>}
      </div>

      {/* Image + Video action buttons */}
      <div style={{ display: 'flex', gap: 6 }}>
        <button
          disabled={!!generatingImage}
          style={actionBtnStyle(scene.imageStatus, generatingImage)}
        >
          {imageLabel}
        </button>
        <button
          disabled={!!generatingVideo}
          style={actionBtnStyle(scene.videoStatus, generatingVideo)}
        >
          {videoLabel}
        </button>
      </div>
    </div>
  );
}
