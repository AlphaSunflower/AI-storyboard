import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, Film, LoaderCircle, Merge, Trash2 } from 'lucide-react';
import type { CanvasNode } from '../types';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { getAsset } from '../services/projectStorage';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { createVideoAsset, getVideoNodeSize } from '../utils/mediaPipeline';
import { persistExistingCanvasAsset } from '../services/canvasMedia';
import { processVideoRequest, probeVideoRequest } from '../services/videoProcessingService';
import { createId } from '../utils/id';

const formatDuration = (seconds?: number) =>
  typeof seconds === 'number' && Number.isFinite(seconds)
    ? `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`
    : '--:--';

function MergeClipThumbnail({ assetId }: { assetId?: string }) {
  const asset = useMediaStore((state) => assetId ? state.assets[assetId] : undefined);
  const url = useObjectUrl(asset?.thumbnailBlob ?? asset?.previewBlob, Boolean(assetId));
  if (!assetId) return <span className="video-merge-thumbnail is-empty"><Film size={16} /></span>;
  if (!url) return <span className="video-merge-thumbnail is-loading"><Film size={16} /></span>;
  return <span className="video-merge-thumbnail"><img src={url} alt="" /></span>;
}

export function VideoMergeNode({ node }: { node: CanvasNode }) {
  const nodes = useCanvasStore((state) => state.nodes);
  const edges = useCanvasStore((state) => state.edges);
  const reorderOrderedInputs = useCanvasStore((state) => state.reorderOrderedInputs);
  const deleteEdge = useCanvasStore((state) => state.deleteEdge);
  const createDerivedNodes = useCanvasStore((state) => state.createDerivedNodes);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const metadata = useMediaStore((state) => state.metadata);
  const [processing, setProcessing] = useState(false);
  const [progress, setProgress] = useState(0);
  const [feedback, setFeedback] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const clips = useMemo(() => edges
    .filter((edge) => edge.target === node.id && edge.targetHandle === 'clips')
    .sort((left, right) =>
      (left.data?.portOrder ?? Number.MAX_SAFE_INTEGER) - (right.data?.portOrder ?? Number.MAX_SAFE_INTEGER))
    .flatMap((edge) => {
      const source = nodes.find((candidate) => candidate.id === edge.source);
      return source ? [{ edge, source }] : [];
    }), [edges, node.id, nodes]);

  useEffect(() => {
    for (const { source } of clips) {
      const assetId = source.data.assetId;
      if (!assetId || useMediaStore.getState().assets[assetId]) continue;
      void getAsset(assetId).then((asset) => { if (asset) upsertAsset(asset); }).catch(() => undefined);
    }
  }, [clips, upsertAsset]);

  const move = (index: number, offset: number) => {
    const target = index + offset;
    if (target < 0 || target >= clips.length) return;
    const ids = clips.map(({ edge }) => edge.id);
    [ids[index], ids[target]] = [ids[target], ids[index]];
    reorderOrderedInputs(node.id, 'clips', ids);
  };

  const mergeVideos = async () => {
    if (processing) return;
    const runnable = clips.length >= 2 && clips.every(({ source }) => Boolean(source.data.assetId));
    if (!runnable) {
      setFeedback('至少连接 2 个具有真实素材的视频');
      return;
    }
    setProcessing(true);
    setProgress(0);
    setFeedback(null);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const clipInputs = await Promise.all(clips.map(async ({ source }) => {
        const asset = useMediaStore.getState().assets[source.data.assetId!] ?? await getAsset(source.data.assetId!);
        if (!asset) throw new Error(`片段「${source.data.label}」素材不存在`);
        const metadata = await probeVideoRequest(asset.originalBlob, asset.name, { signal: controller.signal });
        return {
          source: asset.originalBlob,
          fileName: asset.name,
          width: metadata.width,
          height: metadata.height,
          fps: metadata.fps,
          hasAudio: metadata.hasAudio,
          durationSeconds: metadata.durationSeconds,
        };
      }));
      const result = await processVideoRequest({ type: 'merge', clips: clipInputs }, {
        signal: controller.signal,
        onProgress: setProgress,
      });
      const operation = {
        type: 'videoMerge' as const,
        params: {
          clipAssetIds: clips.map(({ source }) => source.data.assetId ?? '').join(','),
          clipCount: clips.length,
        },
        at: Date.now(),
      };
      const file = new File([result.blob], result.fileName, { type: result.blob.type });
      const local = await createVideoAsset(file, createId(), {
        derivedFrom: clips[0]?.source.data.assetId,
        operation,
      });
      const persisted = await persistExistingCanvasAsset(local, node.id);
      createDerivedNodes(node.id, [{
        data: {
          kind: 'video',
          assetId: persisted.id,
          label: `${node.data.label} · 合并结果`,
          operation: persisted.operation ?? operation,
        },
        size: getVideoNodeSize(persisted.width, persisted.height),
        edgeData: { operation: 'videoMerge', operationDetail: '合并' },
      }]);
      setFeedback(`已合并 ${clips.length} 个视频`);
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') setFeedback('已取消视频合并');
      else setFeedback(error instanceof Error ? error.message : '视频合并失败');
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setProcessing(false);
      setProgress(0);
    }
  };

  return (
    <div className="video-merge-node">
      <div className="video-merge-heading">
        <div><Merge size={16} /><strong>片段顺序</strong></div>
        <span>{clips.length}/12</span>
      </div>
      {clips.length ? (
        <ol className="video-merge-list">
          {clips.map(({ edge, source }, index) => (
            <li key={edge.id}>
              <span className="video-merge-order">{index + 1}</span>
              <MergeClipThumbnail assetId={source.data.assetId} />
              <span className="video-merge-name" title={source.data.label}>{source.data.label}</span>
              <span className="video-merge-duration">{formatDuration(
                source.data.assetId ? metadata[source.data.assetId]?.durationSeconds : undefined,
              )}</span>
              <div className="video-merge-actions nodrag">
                <button aria-label={`上移 ${source.data.label}`} disabled={index === 0 || processing} onClick={() => move(index, -1)}><ChevronUp size={14} /></button>
                <button aria-label={`下移 ${source.data.label}`} disabled={index === clips.length - 1 || processing} onClick={() => move(index, 1)}><ChevronDown size={14} /></button>
                <button aria-label={`移除 ${source.data.label}`} disabled={processing} onClick={() => deleteEdge(edge.id)}><Trash2 size={13} /></button>
              </div>
            </li>
          ))}
        </ol>
      ) : (
        <div className="video-merge-empty"><Film size={22} /><span>连接至少两个视频节点</span></div>
      )}
      <div className="video-merge-footer">
        <div>
          {processing && <LoaderCircle className="video-tool-spinner" size={14} />}
          <span>{processing ? `合并中 ${Math.round(progress * 100)}%` : feedback ?? '按当前顺序硬切拼接'}</span>
        </div>
        {processing && <button className="video-merge-cancel nodrag" onClick={() => abortRef.current?.abort()}>取消</button>}
        <button
          className="node-upload node-upload--generate nodrag"
          disabled={processing || clips.length < 2 || clips.some(({ source }) => !source.data.assetId)}
          onClick={() => void mergeVideos()}
        >
          <Merge size={15} /> 合并视频
        </button>
      </div>
      {feedback && <span className="sr-only" role="status">{feedback}</span>}
    </div>
  );
}
