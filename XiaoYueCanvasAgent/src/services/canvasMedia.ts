import { useCanvasStore } from '../store/canvasStore';
import { createImageAsset, createVideoAsset } from '../utils/mediaPipeline';
import { saveAsset } from './projectStorage';
import {
  downloadCanvasAssetBlob,
  listCanvasAssets,
  uploadCanvasAsset,
  type CanvasAssetDescriptor,
} from './canvasAssets';
import { useMediaStore } from '../store/mediaStore';
import type { PersistedAsset } from '../types';
import type { CanvasNode } from '../types';

/** 浏览器文件先写 MinIO、再把数据库资产 ID 作为本地缓存 ID。 */
export async function createPersistedCanvasAsset(
  file: File,
  producerNodeId: string,
): Promise<PersistedAsset> {
  const { canvasId, projectId } = useCanvasStore.getState();
  if (!canvasId) throw new Error('画布尚未保存，无法持久化媒体资产');
  const kind = file.type.startsWith('video/') ? 'video' : 'image';
  const local: PersistedAsset = kind === 'video'
    ? await createVideoAsset(file)
    : await createImageAsset(file);
  const descriptor = await uploadCanvasAsset(
    canvasId,
    producerNodeId,
    kind,
    file,
    file.name,
  );
  const persisted = {
    ...local,
    id: descriptor.id,
    projectId: projectId ?? undefined,
    canvasId,
  };
  await saveAsset(persisted);
  return persisted;
}

/** 把本地/跨项目素材复制到当前画布的 MinIO 资产库，再创建节点引用。 */
export async function persistExistingCanvasAsset(
  asset: PersistedAsset,
  producerNodeId: string,
): Promise<PersistedAsset> {
  const { canvasId, projectId } = useCanvasStore.getState();
  if (!canvasId) throw new Error('画布尚未保存，无法持久化媒体资产');
  const descriptor = await uploadCanvasAsset(
    canvasId,
    producerNodeId,
    asset.kind,
    asset.originalBlob,
    asset.name,
  );
  const persisted = {
    ...asset,
    id: descriptor.id,
    projectId: projectId ?? undefined,
    canvasId,
  };
  await saveAsset(persisted);
  useMediaStore.getState().upsertAsset(persisted);
  return persisted;
}

const generatedNodeParts = (nodeId: string) => {
  const match = /^result:generation-(.+):([^:]+)$/.exec(nodeId);
  return match ? { recordId: match[1], resultKey: match[2] } : null;
};

/**
 * 旧版本节点保存的是浏览器本地资产 ID，服务端映射后 ID 可能变化。
 * 用 generation record 的结果位次和 agent asset ID 找回服务端 CanvasAsset。
 */
export function findReplacementCanvasAsset(
  node: CanvasNode,
  assets: CanvasAssetDescriptor[],
): CanvasAssetDescriptor | undefined {
  const exact = assets.find((asset) => asset.id === node.data.assetId);
  if (exact) return exact;
  const parts = generatedNodeParts(node.id);
  if (!parts) return undefined;
  const record = useCanvasStore.getState().generationRecords.find((item) => item.id === parts.recordId);
  const resultIndex = record?.resultNodeIds.indexOf(node.id) ?? -1;
  const resultKey = resultIndex >= 0
    ? record?.resultNodeIds[resultIndex]?.split(':').at(-1)
    : parts.resultKey;
  return assets.find((asset) => asset.generationRecordId && asset.generationRecordId === resultKey)
    ?? assets.find((asset) => asset.producerNodeId === node.id);
}

/** 页面打开后按画布节点引用回填 IndexedDB Blob 缓存。 */
export async function hydrateReferencedCanvasAssets(canvasId: string) {
  const serverAssets = await listCanvasAssets(canvasId);
  const state = useCanvasStore.getState();

  // 图保存可能早于结果绑定落盘；先从服务端资产把空结果槽补回来，再加载 Blob 缓存。
  const usedAssetIds = new Set(state.nodes.flatMap((node) => node.data.assetId ? [node.data.assetId] : []));
  const assetsByProducer = new Map<string, CanvasAssetDescriptor[]>();
  serverAssets
    .filter((asset) => asset.producerNodeId)
    .forEach((asset) => {
      const entries = assetsByProducer.get(asset.producerNodeId!) ?? [];
      entries.push(asset);
      assetsByProducer.set(asset.producerNodeId!, entries);
    });
  for (const entries of assetsByProducer.values()) {
    entries.sort((left, right) =>
      (Date.parse(right.createdAt ?? '') || 0) - (Date.parse(left.createdAt ?? '') || 0));
  }
  for (const node of state.nodes) {
    if (node.data.assetId || (node.data.kind !== 'image' && node.data.kind !== 'video')) continue;
    const resultEdge = state.edges.find((edge) =>
      edge.target === node.id && edge.data?.resultSlot && edge.data.resultSlot);
    if (!resultEdge) continue;
    const replacement = (assetsByProducer.get(resultEdge.source) ?? []).find((asset) =>
      asset.type === node.data.kind && !usedAssetIds.has(asset.id));
    if (!replacement) continue;
    usedAssetIds.add(replacement.id);
    useCanvasStore.getState().updateNode(node.id, {
      assetId: replacement.id,
      mediaLoadState: 'ready',
      status: 'success',
    });
  }

  for (const node of state.nodes) {
    if (!node.data.assetId || serverAssets.some((asset) => asset.id === node.data.assetId)) continue;
    const replacement = findReplacementCanvasAsset(node, serverAssets);
    if (!replacement) continue;
    const previousAssetId = node.data.assetId;
    useCanvasStore.getState().updateNode(node.id, { assetId: replacement.id, mediaLoadState: 'ready' });
    const record = useCanvasStore.getState().generationRecords.find((item) =>
      item.resultNodeIds.includes(node.id)
      || item.materializedAssetIds.includes(previousAssetId));
    if (record) {
      useCanvasStore.getState().updateGenerationRecord({
        ...record,
        materializedAssetIds: record.materializedAssetIds.map((id) => id === previousAssetId ? replacement.id : id),
      });
    }
  }

  const referenced = new Set(useCanvasStore.getState().nodes.flatMap((node) => [
    node.data.assetId,
    typeof node.data.firstFrameAssetId === 'string' ? node.data.firstFrameAssetId : undefined,
    typeof node.data.lastFrameAssetId === 'string' ? node.data.lastFrameAssetId : undefined,
  ].filter((assetId): assetId is string => Boolean(assetId))));
  if (!referenced.size) return;
  const metadata = useMediaStore.getState().metadata;
  const missing = serverAssets
    .filter((asset) => referenced.has(asset.id) && !metadata[asset.id]);
  for (const asset of missing) {
    try {
      useMediaStore.getState().clearAssetError(asset.id);
      const blob = await downloadCanvasAssetBlob(asset);
      const file = new File([blob], asset.name ?? asset.id, { type: asset.mimeType });
      const hydrated: PersistedAsset = asset.type === 'video'
        ? await createVideoAsset(file, asset.id)
        : await createImageAsset(file, asset.id);
      hydrated.projectId = useCanvasStore.getState().projectId ?? undefined;
      hydrated.canvasId = canvasId;
      await saveAsset(hydrated);
      useMediaStore.getState().upsertAsset(hydrated);
    } catch (error) {
      useMediaStore.getState().markAssetError(asset.id, error instanceof Error ? error.message : '资产加载失败');
    }
  }

  for (const assetId of referenced) {
    if (serverAssets.some((asset) => asset.id === assetId)) continue;
    const metadataExists = Boolean(useMediaStore.getState().metadata[assetId]);
    if (!metadataExists) {
      useMediaStore.getState().markAssetError(assetId, '本地资产缓存已丢失，请重新上传或重新生成');
    }
  }
}
