import { useState } from 'react';
import type { SceneResponse } from '../../api/projects';
import { sceneApi } from '../../api/scenes';
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
  const generateImage = useProjectStore((s) => s.generateImage);
  const generateVideo = useProjectStore((s) => s.generateVideo);
  const deleteScene = useProjectStore((s) => s.deleteScene);

  const [expanded, setExpanded] = useState(false);
  const [imagePrompt, setImagePrompt] = useState(scene.imagePrompt || '');
  const [videoPrompt, setVideoPrompt] = useState(scene.videoPrompt || '');
  const [isRenaming, setIsRenaming] = useState(false);
  const [sceneName, setSceneName] = useState(scene.scriptContent || '');

  const imageLabel = getImageLabel(scene.imageStatus, !!generatingImage);
  const videoLabel = getVideoLabel(scene.videoStatus, !!generatingVideo);

  const handleGenerateImage = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!imagePrompt.trim()) return;
    await sceneApi.update(scene.id, { imagePrompt });
    await generateImage(scene.id, imagePrompt);
  };

  const handleGenerateVideo = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!videoPrompt.trim()) return;
    await sceneApi.update(scene.id, { videoPrompt });
    await generateVideo(scene.id, videoPrompt);
  };

  const handleDelete = async (e: React.MouseEvent) => {
    e.stopPropagation();
    await deleteScene(scene.id);
  };

  const handleToggleExpand = (e: React.MouseEvent) => {
    e.stopPropagation();
    setExpanded(!expanded);
  };

  const handleSaveRename = async () => {
    const trimmed = sceneName.trim();
    if (trimmed) {
      await sceneApi.update(scene.id, { scriptContent: trimmed });
    }
    setIsRenaming(false);
  };

  const handleStartRename = (e: React.MouseEvent) => {
    e.stopPropagation();
    setSceneName(scene.scriptContent || '');
    setIsRenaming(true);
  };

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
      {/* Header row: scene number + delete button */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 4,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {isRenaming ? (
            <input
              value={sceneName}
              onChange={(e) => setSceneName(e.target.value)}
              onBlur={handleSaveRename}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleSaveRename();
                } else if (e.key === 'Escape') {
                  setIsRenaming(false);
                }
              }}
              onClick={(e) => e.stopPropagation()}
              autoFocus
              style={{
                fontSize: 13,
                fontWeight: 600,
                padding: '2px 6px',
                borderRadius: 'var(--rounded-sm)',
                border: '1px solid var(--color-primary)',
                outline: 'none',
                fontFamily: 'inherit',
                width: 160,
              }}
            />
          ) : (
            <>
              <div
                style={{
                  fontWeight: 600,
                  fontSize: 13,
                  color: 'var(--color-ink)',
                }}
              >
                分镜 {scene.sceneNumber}
              </div>
              <button
                onClick={handleStartRename}
                title="重命名"
                style={{
                  background: 'none',
                  border: 'none',
                  cursor: 'pointer',
                  fontSize: 12,
                  padding: '0 2px',
                  color: 'var(--color-muted)',
                  lineHeight: 1,
                  opacity: 0.6,
                }}
              >
                ✏️
              </button>
            </>
          )}
        </div>
        <button
          onClick={handleDelete}
          title="删除分镜"
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            fontSize: 14,
            padding: '1px 4px',
            borderRadius: 'var(--rounded-sm)',
            color: 'var(--color-muted)',
            lineHeight: 1,
          }}
        >
          🗑️
        </button>
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

      {/* Expand/collapse toggle */}
      <button
        onClick={handleToggleExpand}
        style={{
          background: 'none',
          border: 'none',
          cursor: 'pointer',
          fontSize: 11,
          color: 'var(--color-primary)',
          padding: 0,
          marginBottom: expanded ? 8 : 6,
        }}
      >
        {expanded ? '▲ 收起提示词' : '▼ 编辑提示词'}
      </button>

      {/* Prompt editor (collapsible) */}
      {expanded && (
        <div style={{ marginBottom: 8 }}>
          <label
            style={{ fontSize: 10, color: 'var(--color-muted)', display: 'block', marginBottom: 2 }}
          >
            生图提示词
          </label>
          <textarea
            value={imagePrompt}
            onChange={(e) => setImagePrompt(e.target.value)}
            onClick={(e) => e.stopPropagation()}
            placeholder="输入生图提示词..."
            rows={3}
            style={{
              width: '100%',
              fontSize: 11,
              padding: '6px 8px',
              borderRadius: 'var(--rounded-sm)',
              border: '1px solid var(--color-hairline)',
              resize: 'vertical',
              marginBottom: 8,
              boxSizing: 'border-box',
              fontFamily: 'inherit',
            }}
          />
          <label
            style={{ fontSize: 10, color: 'var(--color-muted)', display: 'block', marginBottom: 2 }}
          >
            生视频提示词
          </label>
          <textarea
            value={videoPrompt}
            onChange={(e) => setVideoPrompt(e.target.value)}
            onClick={(e) => e.stopPropagation()}
            placeholder="输入生视频提示词..."
            rows={3}
            style={{
              width: '100%',
              fontSize: 11,
              padding: '6px 8px',
              borderRadius: 'var(--rounded-sm)',
              border: '1px solid var(--color-hairline)',
              resize: 'vertical',
              marginBottom: 8,
              boxSizing: 'border-box',
              fontFamily: 'inherit',
            }}
          />
        </div>
      )}

      {/* Image + Video action buttons */}
      <div style={{ display: 'flex', gap: 6 }}>
        <button
          disabled={!!generatingImage || !imagePrompt.trim()}
          onClick={handleGenerateImage}
          style={{
            ...actionBtnStyle(scene.imageStatus, generatingImage),
            ...(imagePrompt.trim() ? {} : { opacity: 0.5, cursor: 'not-allowed' }),
          }}
        >
          {imageLabel}
        </button>
        <button
          disabled={!!generatingVideo || !videoPrompt.trim()}
          onClick={handleGenerateVideo}
          style={{
            ...actionBtnStyle(scene.videoStatus, generatingVideo),
            ...(videoPrompt.trim() ? {} : { opacity: 0.5, cursor: 'not-allowed' }),
          }}
        >
          {videoLabel}
        </button>
      </div>
    </div>
  );
}
