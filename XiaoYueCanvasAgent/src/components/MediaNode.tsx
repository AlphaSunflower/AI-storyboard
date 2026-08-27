import { memo, useEffect, useRef, useState } from 'react';
import { Handle, Position, useStore, type NodeProps } from '@xyflow/react';
import { Image as ImageIcon, Plus, Sparkles, Type, Upload } from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { getAsset, getAssetVariant, saveAsset } from '../services/projectStorage';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { useViewportVisibility } from '../hooks/useViewportVisibility';
import type { CanvasNode } from '../types';
import { createImageAsset, disposeRuntimeAsset, getMediaNodeSize } from '../utils/mediaPipeline';
import { ImageNodeTools } from './ImageNodeTools';
import { GeneratePanel } from './GeneratePanel';

function SelectedNodeChrome({ node }: { node: CanvasNode }) {
  const zoom = useStore((state) => state.transform[2]);
  const viewportX = useStore((state) => state.transform[0]);
  const canvasWidth = useStore((state) => state.width);
  return node.data.kind === 'image' && node.data.assetId
    ? <ImageNodeTools node={node} />
    : <div className="empty-node-note nodrag nowheel" style={{ '--inverse-zoom': 1 / zoom, '--generator-shift': `${Math.max(0, viewportX + canvasWidth * 0)}px` } as React.CSSProperties}>上传真实图片后可使用本地编辑工具</div>;
}

function MediaNodeComponent({ id, data, selected, width, height, positionAbsoluteX, positionAbsoluteY }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((state) => state.updateNode);
  const extendNode = useCanvasStore((state) => state.extendNode);
  const attachAssetToNode = useCanvasStore((state) => state.attachAssetToNode);
  const upsertAsset = useMediaStore((state) => state.upsertAsset);
  const asset = useMediaStore((state) => data.assetId ? state.assets[data.assetId] : undefined);
  const metadata = useMediaStore((state) => data.assetId ? state.metadata[data.assetId] : undefined);
  const quality = useMediaStore((state) => state.quality);
  const visibility = useViewportVisibility<HTMLElement>(id, selected);
  const [lazyVariant, setLazyVariant] = useState<{ key: string; blob: Blob } | null>(null);
  const desiredVariant = quality === 'preview' ? 'preview' : 'thumbnail';
  const mediaBlob = asset
    ? (selected || quality === 'preview' ? asset.previewBlob : asset.thumbnailBlob)
    : lazyVariant?.key === `${data.assetId}:${desiredVariant}` ? lazyVariant.blob : undefined;
  const mediaUrl = useObjectUrl(mediaBlob, visibility.visible);
  const [textDraft, setTextDraft] = useState(data.prompt);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const node: CanvasNode = { id, type: 'media', position: { x: positionAbsoluteX, y: positionAbsoluteY }, width, height, data };
  const Icon = data.kind === 'text' ? Type : ImageIcon;

  useEffect(() => {
    if (!data.assetId || asset || !metadata || !visibility.visible || !selected) return;
    let cancelled = false;
    void getAsset(data.assetId).then((loaded) => {
      if (!cancelled && loaded) upsertAsset(loaded);
    }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [asset, data.assetId, metadata, selected, upsertAsset, visibility.visible]);

  useEffect(() => {
    if (!data.assetId || asset || !metadata || !visibility.visible || selected || quality === 'placeholder') return;
    let cancelled = false;
    const key = `${data.assetId}:${desiredVariant}`;
    void getAssetVariant(data.assetId, desiredVariant).then((blob) => {
      if (!cancelled && blob) setLazyVariant({ key, blob });
    }).catch(() => undefined);
    return () => { cancelled = true; };
  }, [asset, data.assetId, desiredVariant, metadata, quality, selected, visibility.visible]);

  useEffect(() => {
    if (!visibility.visible) setLazyVariant(null);
  }, [visibility.visible]);

  return (
    <article ref={visibility.ref} data-media-active={mediaUrl ? 'true' : 'false'} className={`media-node media-node--${data.kind} ${selected ? 'is-selected' : ''}`}>
      <div className="node-floating-title"><Icon size={12} />{selected ? <input className="nodrag" aria-label="请输入标题" key={data.label} defaultValue={data.label} onBlur={(event) => updateNode(id, { label: event.target.value })} /> : <span>{data.label}</span>}</div>
      <div className="node-card">
        {data.kind === 'text' ? (
          <textarea className="text-node-editor nodrag nowheel" aria-label="文字内容" value={textDraft} placeholder="在此输入文字…" onChange={(event) => setTextDraft(event.target.value)} onBlur={() => { if (textDraft !== data.prompt) updateNode(id, { prompt: textDraft }); }} />
        ) : metadata ? (
          !mediaUrl ? <div className="media-placeholder"><ImageIcon size={22} /><span>{metadata.name}</span></div> : <div className="media-asset-frame"><img key={mediaUrl} src={mediaUrl} alt={metadata.name} loading="eager" decoding="async" draggable={false} width={metadata.width} height={metadata.height} /><span className="media-quality-badge">{selected ? 'PREVIEW' : quality.toUpperCase()}</span></div>
        ) : (
          <div className="empty-image-node" onClick={() => setGenerating((open) => !open)}><ImageIcon size={28} /><strong>空图片节点</strong><span>点击生图，或上传文件创建图片资产</span></div>
        )}
      </div>
      {selected && data.kind === 'image' && !metadata && <><input ref={fileInput} className="canvas-file-input nodrag" type="file" accept="image/png,image/jpeg,image/webp" onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; setUploadError(null); let created: Awaited<ReturnType<typeof createImageAsset>> | null = null; void createImageAsset(file).then(async (nextAsset) => { created = nextAsset; await saveAsset(nextAsset); upsertAsset(nextAsset); attachAssetToNode(id, { assetId: nextAsset.id, label: file.name.replace(/\.[^.]+$/, ''), size: getMediaNodeSize(nextAsset.width, nextAsset.height) }); }).catch((error) => { if (created) disposeRuntimeAsset(created); setUploadError(error instanceof Error ? error.message : '图片导入失败'); }); }} /><button className="node-upload nodrag" onClick={() => fileInput.current?.click()}><Upload size={16} /> 上传图片</button><button className="node-upload node-upload--generate nodrag" onClick={() => setGenerating((open) => !open)}><Sparkles size={16} /> 生图</button>{generating && <GeneratePanel node={node} onClose={() => setGenerating(false)} />}{uploadError && <div className="node-upload-error nodrag">{uploadError}</div>}</>}
      {selected && data.kind === 'image' && asset && <SelectedNodeChrome node={node} />}
      <Handle className="node-handle" id="left" type="target" position={Position.Left} />
      <Handle className="node-handle" id="right" type="source" position={Position.Right} />
      {selected && <><button className="node-side-plus node-side-plus--left nodrag" onPointerDown={(event) => event.stopPropagation()} onClick={(event) => { event.stopPropagation(); extendNode(id, data.kind, 'left'); }} aria-label="向左添加节点"><Plus size={18} /></button><button className="node-side-plus node-side-plus--right nodrag" onPointerDown={(event) => event.stopPropagation()} onClick={(event) => { event.stopPropagation(); extendNode(id, data.kind, 'right'); }} aria-label="向右添加节点"><Plus size={18} /></button></>}
    </article>
  );
}

export const MediaNode = memo(MediaNodeComponent);
