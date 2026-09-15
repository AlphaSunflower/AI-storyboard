import { memo, useEffect, useState } from 'react';
import { ReferenceRoleSelector, type ReferenceRoleSelection } from '../video/ReferenceRoleSelector';
import { ImageIcon, X } from 'lucide-react';
import { RichPromptInput } from '../RichPromptInput';
import type { CanvasNode } from '../../types';
import { EMPTY_REFERENCE_IMAGE_POOL, buildReferenceImagePool } from '../../canvas/referenceImagePool';
import { useMediaStore } from '../../store/mediaStore';
import { useCanvasStore } from '../../store/canvasStore';
import { submitVideoGeneration } from '../../services/jobRunner';
import { GenFooter } from './ImageGenNode';
import { createPersistedCanvasAsset } from '../../services/canvasMedia';
import { useObjectUrl } from '../../hooks/useObjectUrl';
import { getAsset } from '../../services/projectStorage';

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
  pool?: ReturnType<typeof buildReferenceImagePool> | null;
  textDraft: string | undefined;
  setTextDraft: (v: string) => void;
  onDeleteRef: (entry: { number: number; sourceNodeId: string }) => void;
}) {
  const safePool = pool ?? EMPTY_REFERENCE_IMAGE_POOL;
  const videoMode = typeof data.videoMode === 'string' ? data.videoMode : 'text2video';
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const canvasNodes = useCanvasStore((state) => state.nodes);
  const canvasEdges = useCanvasStore((state) => state.edges);
  const currentNode = useCanvasStore((state) => state.nodes.find((node) => node.id === nodeId));
  const updateNodeGeometry = useCanvasStore((state) => state.updateNodeGeometry);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [showRoleSelector, setShowRoleSelector] = useState(false);
  const [pendingReferenceUrls, setPendingReferenceUrls] = useState<string[]>([]);
  const firstFrameAssetId = typeof data.firstFrameAssetId === 'string' ? data.firstFrameAssetId : undefined;
  const lastFrameAssetId = typeof data.lastFrameAssetId === 'string' ? data.lastFrameAssetId : undefined;
  const firstFrameAsset = useMediaStore((state) => firstFrameAssetId ? state.assets[firstFrameAssetId] : undefined);
  const lastFrameAsset = useMediaStore((state) => lastFrameAssetId ? state.assets[lastFrameAssetId] : undefined);
  const firstFrameUrl = useObjectUrl(firstFrameAsset?.previewBlob ?? firstFrameAsset?.thumbnailBlob, Boolean(firstFrameAsset));
  const lastFrameUrl = useObjectUrl(lastFrameAsset?.previewBlob ?? lastFrameAsset?.thumbnailBlob, Boolean(lastFrameAsset));
  const connectedImages = [...safePool.entries]
    .filter((entry) =>
      entry.mediaType === 'image'
      && canvasEdges.some((edge) => edge.target === nodeId && edge.source === entry.sourceNodeId))
    .sort((left, right) => {
      const leftNode = canvasNodes.find((node) => node.id === left.sourceNodeId);
      const rightNode = canvasNodes.find((node) => node.id === right.sourceNodeId);
      return (leftNode?.position.y ?? 0) - (rightNode?.position.y ?? 0)
        || (leftNode?.position.x ?? 0) - (rightNode?.position.x ?? 0);
    });

  useEffect(() => {
    if (videoMode !== 'firstLastFrame' && videoMode !== 'image2video') return;
    const byHandle = (handle: string) => connectedImages.find((entry) =>
      canvasEdges.some((edge) =>
        edge.target === nodeId
        && edge.source === entry.sourceNodeId
        && edge.targetHandle === handle));
    const hasFirstInput = canvasEdges.some((edge) =>
      edge.target === nodeId && edge.targetHandle === 'first');
    const hasLastInput = canvasEdges.some((edge) =>
      edge.target === nodeId && edge.targetHandle === 'last');
    const usesFrameHandles = hasFirstInput || hasLastInput;
    const first = usesFrameHandles
      ? byHandle('first')?.assetId
      : connectedImages[0]?.assetId;
    const last = videoMode === 'firstLastFrame'
      ? usesFrameHandles
        ? byHandle('last')?.assetId
        : connectedImages.find((entry) => entry.assetId !== first)?.assetId
      : undefined;
    const patch: Record<string, string | undefined> = {};
    if (data.firstFrameAssetId !== first) patch.firstFrameAssetId = first;
    if (data.lastFrameAssetId !== last) patch.lastFrameAssetId = last;
    if (Object.keys(patch).length) updateNode(nodeId, patch);
  }, [
    canvasEdges,
    connectedImages,
    data.firstFrameAssetId,
    data.lastFrameAssetId,
    nodeId,
    updateNode,
    videoMode,
  ]);

  useEffect(() => {
    for (const assetId of [firstFrameAssetId, lastFrameAssetId]) {
      if (!assetId || useMediaStore.getState().assets[assetId]) continue;
      void getAsset(assetId).then((asset) => {
        if (asset) upsertAsset(asset);
      }).catch(() => undefined);
    }
  }, [firstFrameAssetId, lastFrameAssetId, upsertAsset]);

  useEffect(() => {
    if (videoMode !== 'firstLastFrame' || !currentNode || (currentNode.height ?? 0) >= 560) return;
    updateNodeGeometry(nodeId, {
      width: currentNode.width ?? 560,
      height: 560,
    });
  }, [currentNode, nodeId, updateNodeGeometry, videoMode]);

  const assignedFrames = new Set(
    [data.firstFrameAssetId, data.lastFrameAssetId]
      .filter((value): value is string => typeof value === 'string' && Boolean(value)),
  );
  const promptEntries = safePool.entries.filter((entry) => !assignedFrames.has(entry.assetId));
  const promptPool = {
    ...safePool,
    entries: promptEntries,
    count: promptEntries.length,
    getByNumber: (number: number) => promptEntries.find((entry) => entry.number === number),
    getByAssetId: (assetId: string) => promptEntries.find((entry) => entry.assetId === assetId),
    assetIds: promptEntries.map((entry) => entry.assetId),
    isValidNumber: (number: number) => promptEntries.some((entry) => entry.number === number),
  };

  const attachRef = async (file: File, field: 'firstFrameAssetId' | 'lastFrameAssetId' | 'videoRefAssetId') => {
    setUploading(true);
    try {
      const asset = await createPersistedCanvasAsset(file, nodeId);
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

  const removeFrame = (field: 'firstFrameAssetId' | 'lastFrameAssetId') => {
    const assetId = typeof data[field] === 'string' ? data[field] : undefined;
    if (!assetId) return;
    const entry = safePool.getByAssetId(assetId);
    if (entry) onDeleteRef(entry);
    updateNode(nodeId, { [field]: undefined });
  };

  return (
    <>
      <div className="gen-tabs gen-tabs--mode nodrag" role="group" aria-label="生成模式">
        {VIDEO_MODES.map((m) => <button key={m.id} className={videoMode === m.id ? 'is-active' : ''} onClick={(e) => { e.stopPropagation(); updateNode(nodeId, { videoMode: m.id }); }}>{m.label}</button>)}
      </div>
      {(videoMode === 'image2video' || videoMode === 'firstLastFrame') && (
        <div className={`video-mode-refs nodrag ${videoMode === 'firstLastFrame' ? 'is-first-last' : 'is-single'}`}>
          {videoMode === 'image2video' && (
            <div className={`video-ref-slot ${firstFrameUrl ? 'has-media' : ''}`} data-asset-id={firstFrameAssetId} title="连接图片节点作为首帧">
              {firstFrameUrl ? <img className="video-ref-image" src={firstFrameUrl} alt="首帧" /> : <span>连接图片节点作为首帧</span>}
              {firstFrameAssetId && <button className="video-ref-remove" aria-label="移除首帧" onClick={(event) => { event.stopPropagation(); removeFrame('firstFrameAssetId'); }}><X size={12} /></button>}
            </div>
          )}
          {videoMode === 'firstLastFrame' && (
            <>
              <div className={`video-ref-slot ${firstFrameUrl ? 'has-media' : ''}`} data-asset-id={firstFrameAssetId} title="连接图片节点作为首帧">
                {firstFrameUrl ? <img className="video-ref-image" src={firstFrameUrl} alt="首帧" /> : <span>连接图片节点作为首帧</span>}
                {firstFrameAssetId && <button className="video-ref-remove" aria-label="移除首帧" onClick={(event) => { event.stopPropagation(); removeFrame('firstFrameAssetId'); }}><X size={12} /></button>}
              </div>
              <span className="video-ref-arrow">→</span>
              <div className={`video-ref-slot ${lastFrameUrl ? 'has-media' : ''}`} data-asset-id={lastFrameAssetId} title="连接图片节点作为尾帧">
                {lastFrameUrl ? <img className="video-ref-image" src={lastFrameUrl} alt="尾帧" /> : <span>连接图片节点作为尾帧</span>}
                {lastFrameAssetId && <button className="video-ref-remove" aria-label="移除尾帧" onClick={(event) => { event.stopPropagation(); removeFrame('lastFrameAssetId'); }}><X size={12} /></button>}
              </div>
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
            <div className="video-fullref-hint nodrag">{safePool.count > 0 ? '已连接上游素材，可 @引用图片作参考' : '从画布上 @引用 图片作为参考，或连接上游节点导入'}</div>
          )}
        </div>
      )}
      {feedback && <small className="node-upload-error nodrag">{feedback}</small>}
      <RichPromptInput
        className="rt-input--gen"
        value={String(textDraft ?? data.prompt ?? '')}
        onChange={setTextDraft}
        placeholder="描述要生成的视频…"
        minHeight={80}
        maxHeight={0}
        pool={promptPool}
        showStrip={Boolean(promptPool.count > 0)}
        onDeleteRefEntry={onDeleteRef}
      />
      {!safePool.count && <div className="image-gen-empty"><ImageIcon size={26} /><strong>生视频节点</strong><span>连接上方输入节点作为参考图</span></div>}
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
  referencePool?: ReturnType<typeof buildReferenceImagePool> | null;
  deleteReference: (entry: { number: number; sourceNodeId: string }) => void;
  draftFeedback: string | null;
  setDraftFeedback: (msg: string | null) => void;
}) {
  const safeReferencePool = referencePool ?? EMPTY_REFERENCE_IMAGE_POOL;
  return (
    <div className="image-gen-view video-gen-view">
      <div className="gen-body">
        <VideoGenBody nodeId={id} data={data} updateNode={updateNode} pool={safeReferencePool} textDraft={textDraft} setTextDraft={setTextDraft} onDeleteRef={deleteReference} />
      </div>
      <GenFooter
        nodeId={id}
        kind="videoGen"
        data={data}
        updateNode={updateNode}
        genText="生成视频"
        useCallbacks={{
          generating: Boolean(data.isGenerating),
          onGenerate: () => {
            const text = String(textDraft ?? data.prompt ?? '');
            const entries = safeReferencePool.entries.filter((entry) =>
              entry.assetId !== data.firstFrameAssetId && entry.assetId !== data.lastFrameAssetId);
            const refIds = entries.map((entry) => entry.assetId);
            const sourceNodeIds = entries.map((entry) => entry.sourceNodeId);
            for (const field of ['firstFrameAssetId', 'lastFrameAssetId', 'videoRefAssetId']) {
              if (typeof data[field] === 'string' && data[field]) {
                refIds.push(data[field] as string);
                sourceNodeIds.push(id);
              }
            }
            const kind = data.kind;
            const referenceRoles = entries.map((entry, index) => ({
              assetId: entry.assetId,
              role: kind === 'imageToVideo'
                ? 'FIRST_FRAME'
                : kind === 'firstLastFrameToVideo'
                  ? index === 0 ? 'FIRST_FRAME' : index === 1 ? 'LAST_FRAME' : 'REFERENCE'
                  : 'REFERENCE',
            }));
            if (typeof data.firstFrameAssetId === 'string' && data.firstFrameAssetId) {
              referenceRoles.push({ assetId: data.firstFrameAssetId, role: 'FIRST_FRAME' });
            }
            if (typeof data.lastFrameAssetId === 'string' && data.lastFrameAssetId) {
              referenceRoles.push({ assetId: data.lastFrameAssetId, role: 'LAST_FRAME' });
            }
            if (typeof data.videoRefAssetId === 'string' && data.videoRefAssetId) {
              referenceRoles.push({ assetId: data.videoRefAssetId, role: 'REFERENCE' });
            }
            const opts = {
              sourceAssetIds: refIds,
              sourceNodeIds,
              params: {
                actionId: 'generate',
                videoMode: data.videoMode,
                model: data.model,
                aspectRatio: data.aspectRatio,
                resolution: data.resolution ?? data.size,
                size: data.size,
                duration: data.duration,
                referenceRoles,
              },
            };
            void submitVideoGeneration(text, id, (msg) => setDraftFeedback(msg), opts)
              .catch((error) => setDraftFeedback(error instanceof Error ? error.message : '生视频提交失败'));
          },
        }}
      />
      {draftFeedback && <small className="node-upload-error nodrag">{draftFeedback}</small>}
    </div>
  );
});



