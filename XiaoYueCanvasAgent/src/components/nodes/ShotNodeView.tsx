import { memo, useState } from 'react';
import { Clapperboard, Clock3, Image as ImageIcon, LoaderCircle, Move, PanelRightOpen, Sparkles, Video } from 'lucide-react';
import type { CanvasNode } from '../../types';
import type { ReferenceImagePoolResult } from '../../canvas/referenceImagePool';
import { submitImageGeneration, submitVideoGeneration } from '../../services/jobRunner';
import { useCanvasUi } from '../../canvas/CanvasUiContext';
import { useCanvasStore } from '../../store/canvasStore';
import { useAssetPreviewUrl } from '../../hooks/useAssetPreviewUrl';
import { NodeFloatPanel, NodeSurface } from './nodePanel';

function ShotReferenceThumb({ assetId, index }: { assetId: string; index: number }) {
  const url = useAssetPreviewUrl(assetId);
  return (
    <span className="shot-reference-thumb" title={`参考图 ${index + 1}`}>
      {url ? <img src={url} alt={`参考图 ${index + 1}`} /> : <ImageIcon size={13} />}
      <i>@{index + 1}</i>
    </span>
  );
}

export const ShotNodeView = memo(function ShotNodeView({
  node,
  updateNode,
  referencePool,
}: {
  node: CanvasNode;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
  referencePool: ReferenceImagePoolResult;
}) {
  const { id, data } = node;
  const { openInspector } = useCanvasUi();
  const nodes = useCanvasStore((state) => state.nodes);
  const [busy, setBusy] = useState<'image' | 'video' | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const imagePrompt = String(data.imagePrompt ?? data.prompt ?? '');
  const videoPrompt = String(data.videoPrompt ?? data.prompt ?? '');
  const sceneDescription = String(data.sceneDescription ?? data.prompt ?? '').trim();
  const ordinal = Number(data.shotOrdinal ?? 0);
  const duration = Number(data.durationSeconds ?? 0);
  const hasImagePrompt = imagePrompt.trim().length > 0;
  const hasVideoPrompt = videoPrompt.trim().length > 0;
  const carriedAssetIds = Array.isArray(data.referenceAssetIds) ? data.referenceAssetIds : [];
  const referenceAssetIds = [...new Set([...referencePool.assetIds, ...carriedAssetIds])];
  const useReferenceImages = data.useReferenceImages !== false && referenceAssetIds.length > 0;
  const sourceNodeIds = referenceAssetIds.map((assetId) =>
    referencePool.getByAssetId(assetId)?.sourceNodeId
    ?? nodes.find((candidate) => candidate.data.assetId === assetId)?.id
    ?? '');

  const run = (kind: 'image' | 'video') => {
    setBusy(kind);
    setFeedback(null);
    const request = kind === 'image'
      ? submitImageGeneration(imagePrompt, id, setFeedback, {
        sourceAssetIds: useReferenceImages ? referenceAssetIds : [],
        sourceNodeIds: useReferenceImages ? sourceNodeIds : [],
        params: { actionId: 'generateImage' },
      })
      : submitVideoGeneration(videoPrompt, id, setFeedback, {
        sourceAssetIds: useReferenceImages ? referenceAssetIds : [],
        sourceNodeIds: useReferenceImages ? sourceNodeIds : [],
        params: {
          actionId: 'generateVideo',
          videoMode: useReferenceImages ? 'image2video' : 'text2video',
          referenceRoles: useReferenceImages
            ? referenceAssetIds.map((assetId) => ({ assetId, role: 'REFERENCE' }))
            : [],
        },
      });
    void request
      .then(() => setFeedback(kind === 'image' ? '生图任务已提交' : '生视频任务已提交'))
      .catch((error) => setFeedback(error instanceof Error ? error.message : '任务提交失败'))
      .finally(() => setBusy(null));
  };

  return (
    <div className="shot-node-view">
      <NodeSurface state={hasImagePrompt || hasVideoPrompt ? 'ready' : 'empty'} className="shot-node-surface">
        <div className="shot-node-overview">
          <div className="shot-node-preview" aria-hidden="true">
            <Clapperboard size={24} />
            <span>#{String(ordinal || 1).padStart(2, '0')}</span>
          </div>
          <div className="shot-node-copy">
            <strong title={sceneDescription}>{sceneDescription || '等待 Agent 补全镜头描述'}</strong>
            <div className="shot-node-tags">
              <span>{String(data.shotType || '未设景别')}</span>
              <span><Move size={11} />{String(data.cameraMovement || '未设运镜')}</span>
              <span><Clock3 size={11} />{duration ? `${duration}s` : '未设时长'}</span>
              {referenceAssetIds.length > 0 && <span>参考 {referenceAssetIds.length}</span>}
            </div>
          </div>
        </div>
      </NodeSurface>

      <NodeFloatPanel nodeId={id} className="shot-node-panel">
        <div className="shot-node-readiness" aria-label="提示词状态">
          <span className={hasImagePrompt ? 'is-ready' : 'is-missing'}><ImageIcon size={12} />图片提示词</span>
          <span className={hasVideoPrompt ? 'is-ready' : 'is-missing'}><Video size={12} />视频提示词</span>
        </div>

        {referenceAssetIds.length > 0 && (
          <div className="shot-reference-strip" aria-label="镜头参考图">
            {referenceAssetIds.map((assetId, index) => <ShotReferenceThumb key={assetId} assetId={assetId} index={index} />)}
          </div>
        )}

        {referenceAssetIds.length > 0 && (
          <label className="shot-reference-toggle nodrag">
            <input
              type="checkbox"
              checked={useReferenceImages}
              onChange={(event) => updateNode(id, { useReferenceImages: event.target.checked })}
            />
            <span>生成时携带 {referenceAssetIds.length} 张参考图</span>
          </label>
        )}

        <div className="shot-node-actions">
          <button className="nodrag" disabled={Boolean(busy) || !hasImagePrompt} title={hasImagePrompt ? '生成图片' : '请先补全图片提示词'} onClick={(event) => { event.stopPropagation(); run('image'); }}>
            {busy === 'image' ? <LoaderCircle className="is-spinning" size={14} /> : <Sparkles size={14} />}
            生成图片
          </button>
          <button className="nodrag" disabled={Boolean(busy) || !hasVideoPrompt} title={hasVideoPrompt ? '生成视频' : '请先补全视频提示词'} onClick={(event) => { event.stopPropagation(); run('video'); }}>
            {busy === 'video' ? <LoaderCircle className="is-spinning" size={14} /> : <Video size={14} />}
            生成视频
          </button>
          <button className="shot-node-details nodrag" aria-label="编辑详情" onClick={(event) => { event.stopPropagation(); openInspector(id); }}>
            <PanelRightOpen size={14} />
            编辑详情
          </button>
        </div>
        {feedback && <small className="node-upload-error nodrag">{feedback}</small>}
      </NodeFloatPanel>
    </div>
  );
});
