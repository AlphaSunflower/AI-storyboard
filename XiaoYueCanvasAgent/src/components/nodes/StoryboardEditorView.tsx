import { memo, useMemo, useState } from 'react';
import { CircleStop, Clapperboard, ImagePlus, Package, Settings2 } from 'lucide-react';
import type { CanvasNode, PersistedAsset, StoryboardFrame, StoryboardVideoConfig } from '../../types';
import { useCanvasStore } from '../../store/canvasStore';
import { useMediaStore } from '../../store/mediaStore';
import { useStoryboardVideoStore } from '../../store/storyboardVideoStore';
import { useObjectUrl } from '../../hooks/useObjectUrl';
import { cancelStoryboardVideo, enqueueStoryboardVideo } from '../../services/storyboardVideo';
import { VideoPlanGateModal } from '../VideoPlanGateModal';
import { useNodeTextDraft } from '../../hooks/useNodeTextDraft';
import { createPersistedCanvasAsset } from '../../services/canvasMedia';

const ASPECTS = ['16:9', '9:16', '1:1', '4:3'] as const;
const RESOLUTIONS = ['720p', '1080p'] as const;

const configSummary = (config?: StoryboardVideoConfig) => {
  const parts: string[] = [];
  if (config?.model) parts.push(config.model);
  if (config?.aspectRatio) parts.push(config.aspectRatio);
  if (config?.resolution) parts.push(config.resolution);
  if (config?.duration) parts.push(`${config.duration}s`);
  return parts.length ? parts.join(' · ') : '默认参数';
};

const hasFrameImage = (frame?: StoryboardFrame) => Boolean(frame?.img || frame?.imageUrl || frame?.imageAssetId);
const hasFrameVideo = (frame?: StoryboardFrame) => Boolean(frame?.videoAssetId || frame?.videoUrl || frame?.videoStatus === 'completed');

/** 行内缩略图：优先渲染生成视频（用媒体库资产解析 object URL），否则展示分镜图。 */
function StoryboardThumb({ frame, index }: { frame: StoryboardFrame; index: number }) {
  const asset = useMediaStore((state) => frame.videoAssetId ? state.assets[frame.videoAssetId] : undefined);
  const blob = asset?.originalBlob;
  const resolvedUrl = useObjectUrl(blob, Boolean(blob));
  const videoUrl = resolvedUrl ?? frame.videoUrl;
  if (videoUrl) return <video src={videoUrl} controls preload="metadata" />;
  if (frame.img || frame.imageUrl) return <img src={frame.img || frame.imageUrl} alt={`镜头${index + 1}`} />;
  return <span className="storyboard-row__ph">{String(index + 1).padStart(2, '0')}</span>;
}

function StoryboardPromptEditor({
  nodeId,
  index,
  value,
  onCommit,
}: {
  nodeId: string;
  index: number;
  value: string;
  onCommit: (value: string) => void;
}) {
  const draft = useNodeTextDraft({
    nodeId: `${nodeId}:storyboard-frame:${index}`,
    value,
    onChange: onCommit,
  });
  return (
    <textarea
      className="storyboard-row__prompt"
      value={draft.value}
      placeholder="分镜描述 / 视频提示词…"
      onChange={(event) => draft.setValue(event.target.value)}
      onFocus={draft.onFocus}
      onBlur={draft.onBlur}
      onCompositionStart={draft.onCompositionStart}
      onCompositionEnd={draft.onCompositionEnd}
      rows={2}
    />
  );
}

function StoryboardModelEditor({
  nodeId,
  index,
  value,
  onCommit,
}: {
  nodeId: string;
  index: number;
  value: string;
  onCommit: (value: string) => void;
}) {
  const draft = useNodeTextDraft({
    nodeId: `${nodeId}:storyboard-model:${index}`,
    value,
    onChange: onCommit,
  });
  return (
    <input
      value={draft.value}
      placeholder="默认"
      onChange={(event) => draft.setValue(event.target.value)}
      onFocus={draft.onFocus}
      onBlur={draft.onBlur}
      onCompositionStart={draft.onCompositionStart}
      onCompositionEnd={draft.onCompositionEnd}
    />
  );
}

export const StoryboardEditorView = memo(function StoryboardEditorView({ node, updateNode }: {
  node: CanvasNode;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
}) {
  const { id } = node;
  const frames = (node.data.frames as StoryboardFrame[] | undefined) ?? [];
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const tasks = useStoryboardVideoStore((state) => state.tasks);
  const [gateOpen, setGateOpen] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [expandedConfig, setExpandedConfig] = useState<number | null>(null);

  const selectedCount = useMemo(() => frames.filter((frame) => frame.checked).length, [frames]);
  const allChecked = frames.length > 0 && selectedCount === frames.length;
  const review = useMemo(() => {
    let image = 0;
    let video = 0;
    for (const frame of frames) {
      if (hasFrameImage(frame)) image += 1;
      if (hasFrameVideo(frame)) video += 1;
    }
    return { image, video, total: frames.length };
  }, [frames]);

  const updateFrame = (index: number, patch: Partial<StoryboardFrame>) => {
    const next = [...frames];
    next[index] = { ...(next[index] ?? {}), ...patch };
    updateNode(id, { frames: next });
  };

  const toggleChecked = (index: number) => updateFrame(index, { checked: !frames[index]?.checked });
  const toggleAll = () => updateNode(id, { frames: frames.map((frame) => ({ ...frame, checked: !allChecked })) });

  const attachFile = async (index: number, file: File, kind: 'image' | 'asset') => {
    const asset: PersistedAsset = await createPersistedCanvasAsset(file, id);
    const { projectId, canvasId } = useCanvasStore.getState();
    if (asset.projectId === undefined) asset.projectId = projectId ?? undefined;
    if (asset.canvasId === undefined) asset.canvasId = canvasId ?? undefined;
    upsertAsset(asset);
    if (kind === 'image') {
      const preview = URL.createObjectURL(file);
      // 保留生成的分镜缩略图；仅当无缩略图时才用上传图作为缩略图
      updateFrame(index, { imageAssetId: asset.id, ...(!frames[index]?.img && !frames[index]?.imageUrl ? { img: preview } : {}) });
    } else {
      updateFrame(index, { assetIds: [...(frames[index]?.assetIds ?? []), asset.id] });
    }
  };

  const pickFile = (index: number, kind: 'image' | 'asset') => {
    const accept = kind === 'image' ? 'image/*' : 'image/*,video/*';
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.onchange = () => {
      const file = input.files?.[0];
      if (file) void attachFile(index, file, kind).catch((error) => { setFeedback(error instanceof Error ? error.message : '上传失败'); window.setTimeout(() => setFeedback(null), 2600); });
    };
    input.click();
  };

  const confirmGenerate = () => {
    setGateOpen(false);
    void enqueueStoryboardVideo(id, frames)
      .catch((error) => { setFeedback(error instanceof Error ? error.message : '视频生成提交失败'); window.setTimeout(() => setFeedback(null), 2600); });
  };

  const cancelFrame = (index: number) => {
    const task = Object.values(tasks).find((item) =>
      item.nodeId === id && item.frameIndex === index && (item.status === 'queued' || item.status === 'running'));
    if (task) void cancelStoryboardVideo(task.queueId).catch(() => undefined);
  };

  return (
    <div className="storyboard-editor nodrag nowheel">
      <div className="storyboard-editor__toolbar">
        <label className="storyboard-editor__select-all" title="全选/取消全选">
          <input type="checkbox" checked={allChecked} onChange={toggleAll} />
          <span>{allChecked ? '已全选' : '全选'}</span>
        </label>
        <span className="storyboard-editor__review" title="分镜审查结果">
          审查 · 图 {review.image}/{review.total} · 视频 {review.video}/{review.total}
        </span>
        <button className="storyboard-editor__generate" disabled={selectedCount === 0}
          title={selectedCount ? `为已勾选的 ${selectedCount} 个分镜生成视频` : '请先勾选分镜'}
          onClick={() => { if (!selectedCount) return; setGateOpen(true); }}>
          <Clapperboard size={14} /> 生成视频{selectedCount ? ` (${selectedCount})` : ''}
        </button>
      </div>
      {frames.length === 0 ? (
        <div className="empty-image-node"><Clapperboard size={26} /><strong>分镜节点</strong><span>连接剧本节点，生成后展示分镜帧</span></div>
      ) : (
        <div className="storyboard-editor__rows">
          {frames.map((frame, index) => {
            const active = frame.videoStatus === 'queued' || frame.videoStatus === 'running' || frame.videoStatus === 'cancelling';
            const configOpen = expandedConfig === index;
            const config = frame.config ?? {};
            const promptValue = frame.prompt ?? frame.description ?? '';
            return (
              <article key={index} className={`storyboard-row ${frame.checked ? 'is-checked' : ''}`} data-video-status={frame.videoStatus ?? 'idle'}>
                <label className="storyboard-row__check" title="勾选用于生成视频">
                  <input type="checkbox" checked={Boolean(frame.checked)} onChange={() => toggleChecked(index)} />
                </label>
                <div className="storyboard-row__thumb">
                  <StoryboardThumb frame={frame} index={index} />
                </div>
                <div className="storyboard-row__body">
                  <div className="storyboard-row__head">
                    <span className="storyboard-row__no">#{String(index + 1).padStart(2, '0')}</span>
                    <span className="storyboard-row__label">{frame.label ?? `镜头${index + 1}`}</span>
                    <span className={`storyboard-badge ${hasFrameImage(frame) ? 'is-ok' : 'is-miss'}`}>{hasFrameImage(frame) ? '有图' : '缺图'}</span>
                    <span className={`storyboard-badge ${hasFrameVideo(frame) ? 'is-ok' : 'is-miss'}`}>{hasFrameVideo(frame) ? '有视频' : '缺视频'}</span>
                    {frame.videoStatus === 'completed' && <span className="storyboard-badge is-video">视频已生成</span>}
                    {frame.videoStatus === 'failed' && <span className="storyboard-badge is-failed">生成失败</span>}
                    {active && <span className="storyboard-badge is-active">{frame.videoStatus === 'running' ? '生成中' : frame.videoStatus === 'cancelling' ? '取消中' : '排队中'}</span>}
                  </div>
                  <StoryboardPromptEditor
                    nodeId={id}
                    index={index}
                    value={promptValue}
                    onCommit={(value) => updateFrame(index, { prompt: value })}
                  />
                  <div className="storyboard-row__meta">
                    <button className="storyboard-row__cfg" title="修改配置" onClick={() => setExpandedConfig(configOpen ? null : index)}>
                      <Settings2 size={13} /> <span>{configSummary(config)}</span>
                    </button>
                    <span className="storyboard-row__refs">
                      {frame.imageAssetId && <span className="storyboard-ref-pill" title="已上传参考图片">图</span>}
                      {(frame.assetIds?.length ?? 0) > 0 && <span className="storyboard-ref-pill" title={`已上传 ${frame.assetIds!.length} 项资产`}>资产 {frame.assetIds!.length}</span>}
                    </span>
                  </div>
                  <div className="storyboard-row__actions">
                    <button title="上传参考图片" onClick={() => pickFile(index, 'image')}><ImagePlus size={14} /> 上传图片</button>
                    <button title="上传资产（图片/视频）" onClick={() => pickFile(index, 'asset')}><Package size={14} /> 上传资产</button>
                    {active && <button className="is-cancel" title="取消生成" onClick={() => cancelFrame(index)}><CircleStop size={14} /> 取消</button>}
                  </div>
                  {configOpen && (
                    <div className="storyboard-row__config">
                      <label><span>模型</span><StoryboardModelEditor nodeId={id} index={index} value={config.model ?? ''} onCommit={(value) => updateFrame(index, { config: { ...config, model: value } })} /></label>
                      <label><span>比例</span>
                        <select value={config.aspectRatio ?? ''} onChange={(e) => updateFrame(index, { config: { ...config, aspectRatio: e.target.value } })}>
                          <option value="">默认</option>{ASPECTS.map((a) => <option key={a} value={a}>{a}</option>)}
                        </select>
                      </label>
                      <label><span>时长(s)</span><input type="number" min={2} value={config.duration ?? ''} onChange={(e) => updateFrame(index, { config: { ...config, duration: Number(e.target.value) || undefined } })} /></label>
                      <label><span>清晰度</span>
                        <select value={config.resolution ?? ''} onChange={(e) => updateFrame(index, { config: { ...config, resolution: e.target.value } })}>
                          <option value="">默认</option>{RESOLUTIONS.map((r) => <option key={r} value={r}>{r}</option>)}
                        </select>
                      </label>
                    </div>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
      {feedback && <small className="node-upload-error storyboard-editor__feedback">{feedback}</small>}
      <VideoPlanGateModal open={gateOpen} count={selectedCount} onClose={() => setGateOpen(false)} onConfirm={confirmGenerate} />
    </div>
  );
});
