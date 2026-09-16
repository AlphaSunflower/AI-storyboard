import { apiFetch, apiJson } from './morrowApi';

export interface CanvasAssetDescriptor {
  id: string;
  canvasId?: string;
  type: 'image' | 'video' | 'audio';
  mimeType: string;
  url: string;
  name?: string;
  generationRecordId?: string;
  producerNodeId?: string;
  createdAt?: string;
}

interface CanvasAssetRecord {
  id: string;
  canvasId?: string;
  type: 'image' | 'video' | 'audio';
  mimeType: string;
  objectUrl?: string;
  url?: string;
  generationRecordId?: string;
  producerNodeId?: string;
  createdAt?: string;
}

const normalizeAsset = (asset: CanvasAssetRecord): CanvasAssetDescriptor => ({
  id: asset.id,
  canvasId: asset.canvasId,
  type: asset.type,
  mimeType: asset.mimeType,
  url: asset.objectUrl ?? asset.url ?? '',
  name: asset.objectUrl ?? asset.url
    ? (asset.objectUrl ?? asset.url ?? '').substring((asset.objectUrl ?? asset.url ?? '').lastIndexOf('/') + 1)
    : undefined,
  generationRecordId: asset.generationRecordId,
  producerNodeId: asset.producerNodeId,
  createdAt: asset.createdAt,
});

export async function uploadCanvasAsset(
  canvasId: string,
  producerNodeId: string,
  kind: 'image' | 'video' | 'audio',
  blob: Blob,
  filename: string,
): Promise<CanvasAssetDescriptor> {
  const form = new FormData();
  form.set('canvasId', canvasId);
  form.set('producerNodeId', producerNodeId);
  form.set('type', kind);
  form.set('file', blob, filename);
  const asset = await apiJson<CanvasAssetRecord>('/assets/upload', {
    method: 'POST',
    body: form,
  });
  return normalizeAsset(asset);
}

export async function linkGeneratedCanvasAssets(
  canvasId: string,
  producerNodeId: string,
  generationRecordId: string,
): Promise<CanvasAssetDescriptor[]> {
  const assets = await apiJson<CanvasAssetRecord[]>('/assets/link-generation', {
    method: 'POST',
    body: JSON.stringify({ canvasId, producerNodeId, generationRecordId }),
  });
  return assets.map(normalizeAsset);
}

export async function listCanvasAssets(canvasId: string): Promise<CanvasAssetDescriptor[]> {
  const assets = await apiJson<CanvasAssetRecord[]>(
    `/canvases/${encodeURIComponent(canvasId)}/assets`);
  return assets.map(normalizeAsset);
}

export async function downloadCanvasAssetBlob(asset: CanvasAssetDescriptor): Promise<Blob> {
  const response = await apiFetch(`/assets/${encodeURIComponent(asset.id)}`, {
    headers: { Accept: asset.mimeType },
  });
  if (!response.ok) {
    throw new Error(`读取画布资产失败: HTTP ${response.status}`);
  }
  const blob = await response.blob();
  return blob.type ? blob : blob.slice(0, blob.size, asset.mimeType);
}
