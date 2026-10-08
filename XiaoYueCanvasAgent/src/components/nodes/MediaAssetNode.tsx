import { memo, useEffect, useRef, useState } from 'react';
import { useStore } from '@xyflow/react';
import { Image as ImageIcon, Upload } from 'lucide-react';
import { useCanvasStore } from '../../store/canvasStore';
import { useMediaStore } from '../../store/mediaStore';
import { getAssetMetadata, getAssetVariant } from '../../services/projectStorage';
import { useObjectUrl } from '../../hooks/useObjectUrl';
import { useViewportVisibility } from '../../hooks/useViewportVisibility';
import type { CanvasNode } from '../../types';
import { getMediaNodeSize } from '../../utils/mediaPipeline';
import { ImageNodeTools } from '../ImageNodeTools';
import { createPersistedCanvasAsset } from '../../services/canvasMedia';

// media（image 媒体承载）节点 body：懒加载管线(visibility/objectUrl/lazy 变体) + 媒体帧/空态显示 + 上传与图片编辑工具。
// 媒体渲染与编辑工具整体自持（不再依赖 MediaNode 顶层 state），MediaNode 只负责 chrome。
function MediaAssetNodeComponent({ id, data, selected, node }: {
  id: string;
  data: CanvasNode['data'];
  selected: boolean;
  node: CanvasNode;
}) {
  const zoom = useStore((state) => state.transform[2]);
  const attachAssetToNode = useCanvasStore((state) => state.attachAssetToNode);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const upsertMetadata = useMediaStore((state) => state.upsertMetadata);
  const clearAssetError = useMediaStore((state) => state.clearAssetError);
  const assetError = useMediaStore((state) => data.assetId ? state.assetErrors[data.assetId] : undefined);
  const quality = useMediaStore((state) => state.quality);
  const metadata = useMediaStore((state) => data.assetId ? state.metadata[data.assetId] : undefined);
  const asset = useMediaStore((state) => data.assetId ? state.assets[data.assetId] : undefined);
  const visibility = useViewportVisibility<HTMLDivElement>(id, selected);
  const [lazyVariant, setLazyVariant] = useState<{ key: string; blob: Blob } | null>(null);
  const desiredVariant = quality === 'preview' ? 'preview' : 'thumbnail';
  const mediaBlob = asset
    ? (selected || quality === 'preview' ? asset.previewBlob : asset.thumbnailBlob)
    : lazyVariant?.key === `${data.assetId}:${desiredVariant}` ? lazyVariant.blob : undefined;
  const mediaUrl = useObjectUrl(mediaBlob, visibility.visible);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!data.assetId || metadata || !visibility.visible) return;
    let cancelled = false;
    clearAssetError(data.assetId);
    void getAssetMetadata(data.assetId).then((loaded) => {
      if (!cancelled && loaded) upsertMetadata(loaded);
    }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [clearAssetError, data.assetId, metadata, upsertMetadata, visibility.visible]);

  useEffect(() => {
    if (!data.assetId || asset || !metadata || !visibility.visible || quality === 'placeholder') return;
    let cancelled = false;
    const key = `${data.assetId}:${desiredVariant}`;
    void getAssetVariant(data.assetId, desiredVariant).then((blob) => {
      if (!cancelled && blob) setLazyVariant({ key, blob });
    }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [asset, data.assetId, desiredVariant, metadata, quality, visibility.visible]);

  useEffect(() => {
    if (!visibility.visible) setLazyVariant(null);
  }, [visibility.visible]);

  const content = !data.assetId
    ? <div className="empty-image-node"><ImageIcon size={28} /><strong>空图片节点</strong><span>上传本地图片，或连接生成节点获取结果</span></div>
    : assetError
      ? <div className="empty-image-node"><ImageIcon size={28} /><strong>资产文件缺失</strong><span>{assetError}</span></div>
      : !metadata
        ? <div className="media-placeholder"><span>图片加载中…</span></div>
        : !mediaUrl
          ? <div className="media-placeholder"><ImageIcon size={22} /><span>{metadata.name}</span></div>
          : <div className="media-asset-frame"><img key={mediaUrl} src={mediaUrl} alt={metadata.name} loading="eager" decoding="async" draggable={false} width={metadata.width} height={metadata.height} /><span className="media-quality-badge">{selected ? 'PREVIEW' : quality.toUpperCase()}</span></div>;

  return (
    <div ref={visibility.ref} className="media-node-view">
      {content}
      {selected && !metadata && <>
        <input ref={fileInput} className="canvas-file-input nodrag" type="file" accept="image/png,image/jpeg,image/webp" onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; setUploadError(null); void createPersistedCanvasAsset(file, id).then((nextAsset) => { upsertAsset(nextAsset); attachAssetToNode(id, { assetId: nextAsset.id, label: file.name.replace(/\.[^.]+$/, ''), size: getMediaNodeSize(nextAsset.width, nextAsset.height) }); }).catch((error) => setUploadError(error instanceof Error ? error.message : '图片导入失败')); }} />
        <button className="node-upload nodrag" onClick={() => fileInput.current?.click()}><Upload size={16} /> 上传图片</button>
        {uploadError && <div className="node-upload-error nodrag">{uploadError}</div>}
      </>}
      {selected && asset && (data.kind === 'image' && data.assetId ? <ImageNodeTools node={node} /> : <div className="empty-node-note nodrag nowheel" style={{ '--inverse-zoom': 1 / zoom } as React.CSSProperties}>上传真实图片后可使用本地编辑工具</div>)}
    </div>
  );
}

export const MediaAssetNode = memo(MediaAssetNodeComponent);
