import { useMediaStore } from '../store/mediaStore';
import { useObjectUrl } from '../hooks/useObjectUrl';

/** 取一个资产当前显示的缩略图/preview URL：assetId → mediaStore blob → objectURL（引用计数、可见才加载）。 */
export function useAssetPreviewUrl(assetId: string | undefined, enabled = true): string | null {
  const asset = useMediaStore((s) => assetId ? s.assets[assetId] : undefined);
  const blob = asset ? (asset.previewBlob ?? asset.thumbnailBlob) : undefined;
  // enabled=false（节点不可见/未选中时）不生成 objectURL，省内存
  return useObjectUrl(blob, enabled);
}
