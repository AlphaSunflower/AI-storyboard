import { downloadCanvasAssetBlob } from './canvasAssets';
import { firstCanvasImageAssetId, getAssetVariant } from './projectStorage';

/**
 * 画布封面解析：优先用后端返回的封面资产（第一个放入画布的图片），
 * 后端未提供时退回本地索引里该画布最早的图片资产。
 * 标题页一次会渲染几十张卡片，因此按画布缓存 Promise，避免切页重复请求。
 */
const coverCache = new Map<string, Promise<Blob | null>>();

export async function resolveCanvasCoverBlob(
  canvasId: string,
  coverAssetId?: string | null,
): Promise<Blob | null> {
  const key = `${canvasId}:${coverAssetId ?? ''}`;
  const cached = coverCache.get(key);
  if (cached) return cached;
  const pending = loadCanvasCoverBlob(canvasId, coverAssetId)
    .catch(() => null)
    .then((blob) => {
      // 失败不长期记忆：素材稍后同步进来时下次挂载还能拿到封面。
      if (!blob) coverCache.delete(key);
      return blob;
    });
  coverCache.set(key, pending);
  return pending;
}

async function loadCanvasCoverBlob(canvasId: string, coverAssetId?: string | null): Promise<Blob | null> {
  const assetId = coverAssetId?.trim() || await firstCanvasImageAssetId(canvasId);
  if (!assetId) return null;
  const localVariant = await getAssetVariant(assetId, 'thumbnail') ?? await getAssetVariant(assetId, 'preview');
  if (localVariant) return localVariant;
  try {
    return await downloadCanvasAssetBlob({ id: assetId, type: 'image', mimeType: 'image/*', url: '' });
  } catch {
    return null;
  }
}

/** 测试与画布删除后复用：清空封面缓存。 */
export function clearCanvasCoverCache() {
  coverCache.clear();
}
