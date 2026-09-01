import { memo, useState } from 'react';
import { ReferenceRoleSelector, type ReferenceRoleSelection } from '../video/ReferenceRoleSelector';
import { ImageIcon } from 'lucide-react';
import { RichPromptInput } from '../RichPromptInput';
import type { CanvasNode } from '../../types';
import { buildReferenceImagePool } from '../../canvas/referenceImagePool';
import { createImageAsset, createVideoAsset } from '../../utils/mediaPipeline';
import { saveAsset } from '../../services/projectStorage';
import { useMediaStore } from '../../store/mediaStore';
import { submitVideoGeneration } from '../../services/jobRunner';
import { GenFooter } from './ImageGenNode';

// 生视频模式（对齐参考 ALL_VIDEO_MODES）
export const VIDEO_MODES = [
  { id: 'text2video', label: '文生视频' },
  { id: 'fullReference', label: '全能参考' },
  { id: 'image2video', label: '图生视频' },
  { id: 'firstLastFrame', label: '首尾帧' },
  { id: 'videoReference', label: '视频参考' },
] as const;

/** 生视频模式面板：模式 tabs + 条件参考区(首帧/尾帧/参考图/视频上传)。 */
function VideoGenBody({ nodeId, data, updateNode, pool, textDraft, setTextDraft, onDeleteRef }: {
  nodeId: string;
  data: Record<string, unknown>;
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  pool: ReturnType<typeof buildReferenceImagePool>;
  textDraft: string | undefined;
  setTextDraft: (v: string) => void;
  onDeleteRef: (entry: { number: number; sourceNodeId: string }) => void;
}) {
  const videoMode = typeof data.videoMode === 'string' ? data.videoMode : 'text2video';
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
    const [feedback, setFeedback] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [showRoleSelector, setShowRoleSelector] = useState(false);
  const [pendingReferenceUrls, setPendingReferenceUrls] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);

  const attachRef = async (file: File, field: 'firstFrameAssetId' | 'lastFrameAssetId' | 'videoRefAssetId') => {
    setUploading(true);
    try {
      const asset = file.type.startsWith('video/') ? await createVideoAsset(file) : await createImageAsset(file);
      await saveAsset(asset);
      upsertAsset(asset);
      updateNode(nodeId, { [field]: asset.id });
    } catch (e) {
      setFeedback(e instanceof Error ? e.message : '参考上传失败');
      window.setTimeout(() => setFeedback(null), 2200);
    } finally {
      setUploading(false);
    }
  };

  const handlePick = (field: 'firstFrameAssetId' | 'lastFrameAssetId' | 'videoRefAssetId') => {
    const accept = field === 'videoRefAssetId' ? 'video/*' : 'image/*';
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.onchange = () => { const f = input.files?.[0]; if (f) void attachRef(f, field); };

  // 处理参考图 role 选择
  const handleRoleSelect = (selection: ReferenceRoleSelection) => {
    setShowRoleSelector(false);
    // 将选择结果存储到节点数据中
    updateNode(nodeId, {
      referenceRole: selection.role || (selection.firstLastFrame ? 'FIRST_LAST_FRAME' : 'REFERENCE'),
      referenceRoleSelection: selection,
    });
    setFeedback(null);
  };

  const handleRoleCancel = () => {
    setShowRoleSelector(false);
    setPendingReferenceUrls([]);
  };

    input.click();
  };

  return (
    <>
      <div className="gen-tabs gen-tabs--mode nodrag" role="group" aria-label="生成模式">
        {VIDEO_MODES.map((m) => <button key={m.id} className={videoMode === m.id ? 'is-active' : ''} onClick={(e) => { e.stopPropagation(); updateNode(nodeId, { videoMode: m.id }); }}>{m.label}</button>)}
      </div>
      {(videoMode === 'image2video' || videoMode === 'firstLastFrame') && (
        <div className="video-mode-refs nodrag">
          {videoMode === 'image2video' && (
            <button className="video-ref-slot" onClick={(e) => { e.stopPropagation(); handlePick('firstFrameAssetId'); }} title="拖入或点击上传首帧图">
              {data.firstFrameAssetId ? <span className="video-ref-done">✓ 首帧已选</span> : <span>+ 首帧图</span>}
            </button>
          )}
          {videoMode === 'firstLastFrame' && (
            <>
              <button className="video-ref-slot" onClick={(e) => { e.stopPropagation(); handlePick('firstFrameAssetId'); }} title="首帧图">
                {data.firstFrameAssetId ? <span className="video-ref-done">✓ 首帧</span> : <span>+ 首帧</span>}
              </button>
              <span className="video-ref-arrow">→</span>
              <button className="video-ref-slot" onClick={(e) => { e.stopPropagation(); handlePick('lastFrameAssetId'); }} title="尾帧图">
                {data.lastFrameAssetId ? <span className="video-ref-done">✓ 尾帧</span> : <span>+ 尾帧</span>}
              </button>
            </>
          )}
        </div>
      )}
      {(videoMode === 'fullReference' || videoMode === 'videoReference') && (
        <div className="video-mode-refs nodrag">
          {videoMode === 'videoReference' ? (
            <button className="video-ref-slot video-ref-slot--video" onClick={(e) => { e.stopPropagation(); handlePick('videoRefAssetId'); }} title="点击或拖拽上传视频 MP4/MOV">
              {data.videoRefAssetId ? <span className="video-ref-done">✓ {uploading ? '上传中…' : '参考视频已选'}</span> : <span>{uploading ? '上传中…' : '+ 参考视频'}</span>}
            </button>
          ) : (
            <div className="video-fullref-hint nodrag">{pool.count > 0 ? '已连接上游素材，可 @引用图片作参考' : '从画布上 @引用 图片作为参考，或连接上游节点导入'}</div>
          )}
        </div>
      )}
      {feedback && <small className="node-upload-error nodrag">{feedback}</small>}
      <RichPromptInput
        className="rt-input--gen"
        value={String(data.prompt ?? textDraft ?? '')}
        onChange={(v) => { setTextDraft(v); updateNode(nodeId, { prompt: v }); }}
        placeholder="描述要生成的视频…"
        minHeight={80}
        maxHeight={0}
        pool={pool}
        showStrip={Boolean(pool.count > 0)}
        onDeleteRefEntry={onDeleteRef}
      />
      {!pool.count && <div className="image-gen-empty"><ImageIcon size={26} /><strong>生视频节点</strong><span>连接上方输入节点作为参考图</span></div>}
    </>
  );
}

/** videoGen 节点 body：模式 tabs + 条件参考区 + 富提示词 + 生成 footer。 */
export const VideoGenNode = memo(function VideoGenNode({ id, data, updateNode, textDraft, setTextDraft, referencePool, deleteReference, draftFeedback, setDraftFeedback }: {
  id: string;
  data: CanvasNode['data'];
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  textDraft: string | undefined;
  setTextDraft: (v: string) => void;
  referencePool: ReturnType<typeof buildReferenceImagePool>;
  deleteReference: (entry: { number: number; sourceNodeId: string }) => void;
  draftFeedback: string | null;
  setDraftFeedback: (msg: string | null) => void;
}) {
  return (
    <div className="image-gen-view video-gen-view">
      <div className="gen-body">
        <VideoGenBody nodeId={id} data={data} updateNode={updateNode} pool={referencePool} textDraft={textDraft} setTextDraft={setTextDraft} onDeleteRef={deleteReference} />
      </div>
      <GenFooter nodeId={id} kind="videoGen" data={data} updateNode={updateNode} genText="生成视频" useCallbacks={{ generating: Boolean(data.isGenerating), onGenerate: () => { const text = String(data.prompt ?? textDraft ?? ''); const refIds = [...(referencePool.assetIds ?? [])]; for (const f of ['firstFrameAssetId', 'lastFrameAssetId', 'videoRefAssetId']) { if (typeof data[f] === 'string' && data[f]) refIds.push(data[f] as string); } const opts = { sourceAssetIds: refIds, params: { videoMode: data.videoMode, model: data.model, aspectRatio: data.aspectRatio, resolution: data.resolution ?? data.size, size: data.size, duration: data.duration } }; void submitVideoGeneration(text, id, (msg) => setDraftFeedback(msg), opts).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '生视频提交失败')); } }} />
      {draftFeedback && <small className="node-upload-error nodrag">{draftFeedback}</small>}
    </div>
  );
});



