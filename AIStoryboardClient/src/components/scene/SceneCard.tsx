import { useState, useRef } from 'react';
import type { SceneResponse } from '../../api/projects';
import { sceneApi } from '../../api/scenes';
import { useProjectStore } from '../../stores/projectStore';
import { ImageRefineModal } from '../ai/ImageRefineModal';
import { VideoRefineModal } from '../ai/VideoRefineModal';

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

function getImageLabel(scene: SceneResponse, generating: boolean): string {
  if (generating) return '⏳生成中';
  if (scene.imageStatus === 'completed' && !scene.imageUrl) return '重试';
  if (scene.imageStatus === 'generating' && !generating) return '重试';
  switch (scene.imageStatus) {
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

function getVideoLabel(scene: SceneResponse, generating: boolean): string {
  if (generating) return '⏳生成中';
  if (scene.videoStatus === 'completed' && !scene.videoUrl) return '重试';
  if (scene.videoStatus === 'generating' && !generating) return '重试';
  switch (scene.videoStatus) {
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

function actionBtnStyle(status: string, url?: string, generating?: boolean): React.CSSProperties {
  const isDone = status === 'completed' && !!url;
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
  const imageModel = useProjectStore((s) => s.imageModel);
  const videoModel = useProjectStore((s) => s.videoModel);
  const videoProgress = useProjectStore((s) => s.videoProgress[scene.id]) || 0;
  const getSceneRefs = useProjectStore((s) => s.getSceneRefs);
  const setSceneRefs = useProjectStore((s) => s.setSceneRefs);

  const refs = getSceneRefs(scene.id);

  const [expanded, setExpanded] = useState(false);
  const [imagePrompt, setImagePrompt] = useState(scene.imagePrompt || '');
  const [videoPrompt, setVideoPrompt] = useState(scene.videoPrompt || '');
  const [isRenaming, setIsRenaming] = useState(false);
  const [sceneLabel, setSceneLabel] = useState(`分镜 ${scene.sceneNumber}`);
  const refInputRef = useRef<HTMLInputElement>(null);
  const [showImageModal, setShowImageModal] = useState(false);
  const [showVideoModal, setShowVideoModal] = useState(false);

  const imageLabel = getImageLabel(scene, !!generatingImage);
  const videoLabel = getVideoLabel(scene, !!generatingVideo);

  const handleGenerateImage = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!imagePrompt.trim()) return;

    // When already completed AND has URL, open refine modal instead of direct re-generation
    if (scene.imageStatus === 'completed' && scene.imageUrl) {
      setShowImageModal(true);
      return;
    }

    // Retry / first generation
    try {
      await sceneApi.update(scene.id, { imagePrompt });
      await generateImage(scene.id, imagePrompt, imageModel, refs.useForImage && refs.images.length > 0 ? refs.images : undefined);
    } catch (err) {
      alert('生成图片失败，请检查网络连接');
    }
  };

  const handleImageRefineConfirm = async (params: { prompt: string; model: string; referenceImages: string[] }) => {
    try {
      await sceneApi.update(scene.id, { imagePrompt: params.prompt });
      await generateImage(scene.id, params.prompt, params.model, params.referenceImages);
    } catch (err) {
      alert('完善图片失败，请检查网络连接');
    }
  };

  const handleGenerateVideo = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!videoPrompt.trim()) return;

    // When already completed AND has URL, open refine modal instead of direct re-generation
    if (scene.videoStatus === 'completed' && scene.videoUrl) {
      setShowVideoModal(true);
      return;
    }

    // Retry / first generation
    try {
      await sceneApi.update(scene.id, { videoPrompt });
      await generateVideo(scene.id, videoPrompt, videoModel, refs.useForVideo && refs.images.length > 0 ? refs.images : undefined);
    } catch (err) {
      alert('生成视频失败，请检查网络连接');
    }
  };

  const handleVideoRefineConfirm = async (params: { prompt: string; model: string; referenceImages: string[] }) => {
    try {
      await sceneApi.update(scene.id, { videoPrompt: params.prompt });
      await generateVideo(scene.id, params.prompt, params.model, params.referenceImages);
    } catch (err) {
      alert('完善视频失败，请检查网络连接');
    }
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
    const trimmed = sceneLabel.trim();
    if (trimmed) {
      await sceneApi.update(scene.id, { soundDesign: trimmed });
    }
    setIsRenaming(false);
  };

  const handleStartRename = (e: React.MouseEvent) => {
    e.stopPropagation();
    const customName = scene.soundDesign && !scene.soundDesign.startsWith('{') && !scene.soundDesign.startsWith('分镜') ? scene.soundDesign : `分镜 ${scene.sceneNumber}`;
    setSceneLabel(customName);
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
              value={sceneLabel}
              onChange={(e) => setSceneLabel(e.target.value)}
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
                {scene.soundDesign && !scene.soundDesign.startsWith('{') && !scene.soundDesign.startsWith('分镜') ? scene.soundDesign : `分镜 ${scene.sceneNumber}`}
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
        {scene.soundDesign && !scene.soundDesign.startsWith('{') && <Tag>{scene.soundDesign}</Tag>}
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

          {/* Reference image upload */}
          <div style={{ marginTop: 8 }}>
            <div
              onClick={() => refInputRef.current?.click()}
              style={{
                display: 'flex', alignItems: 'center', gap: 6,
                padding: '6px 10px', borderRadius: 'var(--rounded-md)',
                border: '1px dashed var(--color-hairline)', cursor: 'pointer',
                background: 'var(--color-canvas)', fontSize: 11,
                color: 'var(--color-muted)',
              }}
            >
              <span style={{ fontSize: 14 }}>🖼️</span>
              <span>{refs.images.length > 0 ? `${refs.images.length}/3 张参考图` : '添加参考图（可选，最多3张）'}</span>
            </div>
            <input ref={refInputRef} type="file" accept="image/*" multiple hidden
              onChange={(e) => {
                const files = Array.from(e.target.files || []);
                if (refs.images.length + files.length > 3) { alert('最多3张参考图'); return; }
                files.forEach(f => {
                  const reader = new FileReader();
                  reader.onload = () => setSceneRefs(scene.id, { ...refs, images: [...refs.images, reader.result as string] });
                  reader.readAsDataURL(f);
                });
              }} />
            {refs.images.length > 0 && (
              <div style={{ display:'flex',gap:4,marginTop:6,flexWrap:'wrap' }}>
                {refs.images.map((url,i) => (
                  <div key={i} style={{position:'relative'}}>
                    <img src={url} style={{width:48,height:48,borderRadius:4,objectFit:'cover'}} />
                    <span onClick={() => setSceneRefs(scene.id, { ...refs, images: refs.images.filter((_,j) => j!==i) })}
                      style={{position:'absolute',top:-4,right:-4,background:'var(--color-error)',color:'white',borderRadius:'50%',width:16,height:16,fontSize:10,display:'flex',alignItems:'center',justifyContent:'center',cursor:'pointer'}}>×</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Toggle ref-image usage */}
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

      {/* Video progress bar */}
      {generatingVideo && (
        <div style={{ marginBottom: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
            <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>视频生成中</span>
            <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>{videoProgress}%</span>
          </div>
          <div style={{ height: 4, borderRadius: 2, background: 'var(--color-surface-soft)', overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${videoProgress}%`, borderRadius: 2, background: 'var(--color-primary)', transition: 'width 0.3s ease' }} />
          </div>
        </div>
      )}

      {/* Image + Video action buttons */}
      <div style={{ display: 'flex', gap: 6 }}>
        <button
          disabled={!!generatingImage || !imagePrompt.trim()}
          onClick={handleGenerateImage}
          style={{
            ...actionBtnStyle(scene.imageStatus, scene.imageUrl, generatingImage),
            ...(imagePrompt.trim() ? {} : { opacity: 0.5, cursor: 'not-allowed' }),
          }}
        >
          {imageLabel}
        </button>
        <button
          disabled={!!generatingVideo || !videoPrompt.trim()}
          onClick={handleGenerateVideo}
          style={{
            ...actionBtnStyle(scene.videoStatus, scene.videoUrl, generatingVideo),
            ...(videoPrompt.trim() ? {} : { opacity: 0.5, cursor: 'not-allowed' }),
          }}
        >
          {videoLabel}
        </button>
      </div>

      {/* Refine modals */}
      {showImageModal && (
        <ImageRefineModal
          scene={scene}
          onClose={() => setShowImageModal(false)}
          onGenerate={handleImageRefineConfirm}
        />
      )}
      {showVideoModal && (
        <VideoRefineModal
          scene={scene}
          onClose={() => setShowVideoModal(false)}
          onGenerate={handleVideoRefineConfirm}
        />
      )}
    </div>
  );
}
