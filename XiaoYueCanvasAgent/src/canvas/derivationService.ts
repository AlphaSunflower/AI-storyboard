import { getAsset, saveAssets } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { getMediaNodeSize } from '../utils/mediaPipeline';
import { createId } from '../utils/id';
import { failure } from './canvasErrors';
import { check } from './canvasTransaction';
import type { CanvasCommandContext, CanvasCommandResult } from './canvasTypes';
import type { ImageOperation, MediaAsset, PersistedAsset } from '../types';

export interface DerivationRequest {
  context: CanvasCommandContext;
  sourceNodeId: string;
  operationType: ImageOperation['type'];
  labelSuffix: string;
  process: (source: PersistedAsset) => Promise<MediaAsset[]>;
  layout?: { columns?: number };
}

// 统一的派生事务：把"对源素材做某操作"落成画布上的一条因果链。
// 非 dryRun：读源 → 处理 → 编码真实 Blob → IndexedDB 持久化 → 一次事务创建产物节点 + 带操作标签的因果边。
// dryRun：只校验并返回预览，不真正处理、不写入。
export async function deriveFromAsset(request: DerivationRequest): Promise<CanvasCommandResult> {
  const { context, sourceNodeId, operationType, labelSuffix, process, layout } = request;
  const invalid = check(context);
  if (invalid) return invalid;

  const state = useCanvasStore.getState();
  const sourceNode = state.nodes.find((node) => node.id === sourceNodeId);
  if (!sourceNode) return failure('NODE_NOT_FOUND', '源节点不存在', { nodeId: sourceNodeId });
  if (!sourceNode.data.assetId) return failure('ASSET_NOT_FOUND', '源节点没有真实素材，请先上传图片', { nodeId: sourceNodeId });

  const description = `对「${sourceNode.data.label}」执行${labelSuffix}`;

  if (context.dryRun) {
    return { ok: true, status: 'preview', transactionId: `tx-${createId()}`, revision: state.revision, changes: { description }, result: { sourceNodeId, operation: operationType } };
  }

  const source = await getAsset(sourceNode.data.assetId);
  if (!source) return failure('ASSET_NOT_FOUND', '源素材不存在或已损坏', { assetId: sourceNode.data.assetId });

  const results = await process(source);
  if (!results.length) return failure('PROCESS_FAILED', '处理未产生任何结果');

  const at = Date.now();
  const { projectId, canvasId } = useCanvasStore.getState();
  results.forEach((asset) => {
    if (asset.operation && asset.operation.at === undefined) asset.operation.at = at;
    if (asset.projectId === undefined) asset.projectId = projectId ?? undefined;
    if (asset.canvasId === undefined) asset.canvasId = canvasId ?? undefined;
  });

  await saveAssets(results);
  useMediaStore.getState().upsertAssets(results);

  const sourceWidth = sourceNode.width ?? sourceNode.measured?.width ?? 250;
  const sourceHeight = sourceNode.height ?? sourceNode.measured?.height ?? 250;
  const nodeIds = useCanvasStore.getState().createDerivedNodes(sourceNodeId, results.map((asset) => ({
    data: { assetId: asset.id, label: `${sourceNode.data.label} · ${labelSuffix}`, operation: asset.operation },
    size: asset.kind === 'image' ? getMediaNodeSize(asset.width, asset.height) : { width: sourceWidth, height: sourceHeight },
  })), layout);

  return {
    ok: true,
    status: 'applied',
    transactionId: `tx-${createId()}`,
    revision: useCanvasStore.getState().revision,
    changes: { createdNodeIds: nodeIds, description },
    result: { nodeIds, operation: operationType },
  };
}
