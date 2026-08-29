import { memo, useEffect, useRef, useState } from 'react';
import { Handle, Position, useStore, type NodeProps } from '@xyflow/react';
import { Clapperboard, Copy, FileText, Image as ImageIcon, LayoutGrid, Layers, LoaderCircle, MessageSquare, Plus, Sparkles, Trash2, Type, Upload, Wand2 } from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import { getAsset, getAssetVariant, saveAsset } from '../services/projectStorage';
import { submitScriptDrafting } from '../services/jobRunner';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { useViewportVisibility } from '../hooks/useViewportVisibility';
import type { CanvasNode } from '../types';
import { createImageAsset, disposeRuntimeAsset, getMediaNodeSize } from '../utils/mediaPipeline';
import { ImageNodeTools } from './ImageNodeTools';
import { GeneratePanel } from './GeneratePanel';
import { StoryboardGenView } from './nodes/StoryboardGenView';
import { GroupedNodeMenu } from './ui/GroupedNodeMenu';

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
  const createConnectedNode = useCanvasStore((state) => state.createConnectedNode);
  const createNodeBefore = useCanvasStore((state) => state.createNodeBefore);
  const addNode = useCanvasStore((state) => state.addNode);
  const nodes = useCanvasStore((state) => state.nodes);
  const duplicateSelected = useCanvasStore((state) => state.duplicateSelected);
  const deleteSelected = useCanvasStore((state) => state.deleteSelected);
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
  const edges = useCanvasStore((state) => state.edges);
  const upstreamEdge = edges.find((e) => e.target === id);
  const upstreamAssetId = (upstreamEdge?.source ? nodes.find((n) => n.id === upstreamEdge.source)?.data.assetId : undefined) as string | undefined;
  const upstreamBlob = useMediaStore((state) => upstreamAssetId ? state.assets[upstreamAssetId]?.previewBlob : undefined);
  const upstreamUrl = useObjectUrl(upstreamBlob, visibility.visible);
  const [textDraft, setTextDraft] = useState(data.prompt);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [drafting, setDrafting] = useState(false);
  const [draftFeedback, setDraftFeedback] = useState<string | null>(null);
  const [addDir, setAddDir] = useState<null | 'left' | 'right'>(null);
  const [childMenu, setChildMenu] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const node: CanvasNode = { id, type: 'media', position: { x: positionAbsoluteX, y: positionAbsoluteY }, width, height, data };
  const Icon = data.kind === 'text' ? Type : data.kind === 'script' ? FileText : data.kind === 'note' ? MessageSquare : data.kind === 'prompt' ? Wand2 : data.kind === 'sequence' ? Layers : data.kind === 'storyboard' ? Clapperboard : data.kind === 'storyboardGen' ? LayoutGrid : data.kind === 'imageGen' ? Sparkles : ImageIcon;

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
      {selected && (
        <div className="node-selected-bar nodrag">
          <button aria-label="复制节点" onClick={(e) => { e.stopPropagation(); duplicateSelected(); }}><Copy size={13} /></button>
          <button aria-label="删除节点" className="is-danger" onClick={(e) => { e.stopPropagation(); deleteSelected(); }}><Trash2 size={13} /></button>
        </div>
      )}
      <div className="node-card">
        {data.kind === 'script' ? (
          <div className="script-node-view">
            <textarea className="text-node-editor nodrag nowheel" aria-label="剧本内容" value={data.scriptText ?? textDraft} placeholder="在此粘贴分镜剧本…" onChange={(event) => { setTextDraft(event.target.value); updateNode(id, { scriptText: event.target.value }); }} />
            <button className="node-upload node-upload--generate nodrag" disabled={drafting} onClick={() => { const text = data.scriptText ?? textDraft; if (!text.trim()) { setDraftFeedback('请先输入剧本'); window.setTimeout(() => setDraftFeedback(null), 2200); return; } setDrafting(true); void submitScriptDrafting(text, id, (msg) => setDraftFeedback(msg)).then(() => setDraftFeedback('分镜任务已提交')).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '分镜任务提交失败')).finally(() => setDrafting(false)); }}>{drafting ? <LoaderCircle className="is-spinning" size={14} /> : <FileText size={14} />} {drafting ? '生成中' : '生成分镜'}</button>
            {draftFeedback && <small className="script-node-feedback nodrag">{draftFeedback}</small>}
          </div>
        ) : data.kind === 'storyboard' ? (
          <div className="storyboard-grid">
            {Array.isArray(data.frames) && data.frames.length ? data.frames.map((frame, i) => (
              <figure key={i} className="storyboard-frame">{frame.img && <img src={frame.img} alt="" />}<figcaption>{frame.label ?? `镜头${i + 1}`}</figcaption></figure>
            )) : <div className="empty-image-node"><Clapperboard size={26} /><strong>分镜节点</strong><span>连接剧本节点，生成后展示分镜帧</span></div>}
          </div>
        ) : data.kind === 'storyboardGen' ? (
          <StoryboardGenView node={node} updateNode={updateNode} />
        ) : data.kind === 'imageGen' ? (
          <div className="image-gen-view">
            <textarea className="text-node-editor nodrag nowheel" aria-label="AI 图片提示词" value={data.prompt ?? textDraft} placeholder="描述要生成的画面…" onChange={(event) => { setTextDraft(event.target.value); updateNode(id, { prompt: event.target.value }); }} />
            {upstreamUrl ? (
              <figure className="image-gen-reference"><img src={upstreamUrl} alt="" /><figcaption>参考图</figcaption></figure>
            ) : (
              <div className="image-gen-empty"><ImageIcon size={26} /><strong>AI 图片节点</strong><span>连接上方输入节点作为参考图</span></div>
            )}
          </div>
        ) : data.kind === 'text' || data.kind === 'note' || data.kind === 'prompt' ? (
          <textarea className="text-node-editor nodrag nowheel" aria-label={data.kind === 'note' ? '批注内容' : data.kind === 'prompt' ? '提示词内容' : '文字内容'} value={data.kind === 'note' ? (data.richText ?? textDraft) : data.kind === 'prompt' ? (data.promptText ?? textDraft) : textDraft} placeholder={data.kind === 'note' ? '在此记录设计说明…' : data.kind === 'prompt' ? '在此输入 AI 提示词…' : '在此输入文字…'} onChange={(event) => { setTextDraft(event.target.value); const patch = data.kind === 'note' ? { richText: event.target.value } : data.kind === 'prompt' ? { promptText: event.target.value } : { prompt: event.target.value }; updateNode(id, patch); }} onBlur={() => { if (data.kind !== 'note' && data.kind !== 'prompt' && textDraft !== data.prompt) updateNode(id, { prompt: textDraft }); }} />
        ) : data.kind === 'sequence' ? (
          <div className="sequence-node-view">
            <strong>序列节点</strong>
            <span>{Array.isArray(data.children) && data.children.length ? `${data.children.length} 个子节点` : '暂未挂载子节点'}</span>
            {Array.isArray(data.children) && data.children.length > 0 && (
              <ul className="sequence-children">
                {data.children.map((childId) => {
                  const child = nodes.find((n) => n.id === childId);
                  return (
                    <li key={childId}><span>{child?.data.label || childId.slice(0, 10)}</span><button className="nodrag" aria-label="移除子节点" onClick={(e) => { e.stopPropagation(); updateNode(id, { children: (data.children ?? []).filter((c) => c !== childId) }); }}>×</button></li>
                  );
                })}
              </ul>
            )}
            <button className="node-upload node-upload--generate nodrag" onClick={(e) => { e.stopPropagation(); setChildMenu(true); }}>+ 挂载节点</button>
          </div>
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
      {selected && <><button className="node-side-plus node-side-plus--left nodrag" onPointerDown={(event) => event.stopPropagation()} onClick={(event) => { event.stopPropagation(); setAddDir('left'); }} aria-label="向左添加节点"><Plus size={18} /></button><button className="node-side-plus node-side-plus--right nodrag" onPointerDown={(event) => event.stopPropagation()} onClick={(event) => { event.stopPropagation(); setAddDir('right'); }} aria-label="向右添加节点"><Plus size={18} /></button></>}
      {addDir && <>
        <div className="node-add-backdrop nodrag" onClick={(e) => { e.stopPropagation(); setAddDir(null); }} />
        <div className="node-add-popover nodrag nowheel" style={addDir === 'right' ? { left: 'calc(100% + 10px)', top: '50%', transform: 'translateY(-50%)' } : { right: 'calc(100% + 10px)', top: '50%', transform: 'translateY(-50%)' }} onClick={(e) => e.stopPropagation()}>
          <GroupedNodeMenu onAdd={(kind) => {
            const target = { x: node.position.x + (addDir === 'right' ? (width ?? 250) + 90 : -270), y: node.position.y + (height ?? 250) / 2 };
            if (addDir === 'right') createConnectedNode(id, kind as any, target);
            else createNodeBefore(id, kind as any, target);
            setAddDir(null);
          }} />
        </div>
      </>}
      {childMenu && <>
        <div className="node-add-backdrop nodrag" onClick={(e) => { e.stopPropagation(); setChildMenu(false); }} />
        <div className="node-add-popover nodrag nowheel" style={{ left: '50%', top: '50%', transform: 'translate(-50%, -50%)' }} onClick={(e) => e.stopPropagation()}>
          <GroupedNodeMenu onAdd={(kind) => {
            const cid = addNode(kind as any, { x: node.position.x + 40, y: node.position.y + (height ?? 250) + 60 });
            updateNode(id, { children: [...(data.children ?? []), cid] });
            setChildMenu(false);
          }} />
        </div>
      </>}
    </article>
  );
}

export const MediaNode = memo(MediaNodeComponent);
