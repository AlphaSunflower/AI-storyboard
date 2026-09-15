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

/** 页面打开后按画布节点引用回填 IndexedDB Blob 缓存。 */
export async function hydrateReferencedCanvasAssets(canvasId: string) {
  const referenced = new Set(
    useCanvasStore.getState().nodes
      .flatMap((node) => [
        node.data.assetId,
        typeof node.data.firstFrameAssetId === 'string' ? node.data.firstFrameAssetId : undefined,
        typeof node.data.lastFrameAssetId === 'string' ? node.data.lastFrameAssetId : undefined,
      ].filter((assetId): assetId is string => Boolean(assetId))),
  );
  if (!referenced.size) return;
  const metadata = useMediaStore.getState().metadata;
  const missing = (await listCanvasAssets(canvasId))
    .filter((asset) => referenced.has(asset.id) && !metadata[asset.id]);
  for (const asset of missing) {
    const blob = await downloadCanvasAssetBlob(asset);
    const file = new File([blob], asset.name ?? asset.id, { type: asset.mimeType });
    const hydrated: PersistedAsset = asset.type === 'video'
      ? await createVideoAsset(file, asset.id)
      : await createImageAsset(file, asset.id);
    hydrated.projectId = useCanvasStore.getState().projectId ?? undefined;
    hydrated.canvasId = canvasId;
    await saveAsset(hydrated);
    useMediaStore.getState().upsertAsset(hydrated);
  }
}
