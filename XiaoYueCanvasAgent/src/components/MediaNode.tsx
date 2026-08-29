import { memo, useEffect, useMemo, useRef, useState } from 'react';
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
import { buildReferenceImagePool } from '../canvas/referenceImagePool';
import { removeReferenceTokenByNumber } from '../canvas/referenceTokenEditing';
import { ImageNodeTools } from './ImageNodeTools';
import { GeneratePanel } from './GeneratePanel';
import { StoryboardGenView } from './nodes/StoryboardGenView';
import { ImageGenNode } from './nodes/ImageGenNode';
import { VideoGenNode } from './nodes/VideoGenNode';
import { GroupedNodeMenu } from './ui/GroupedNodeMenu';
import { NodeResizeHandle } from './NodeResizeHandle';

function SelectedNodeChrome({ node }: { node: CanvasNode }) {
  const zoom = useStore((state) => state.transform[2]);
  const viewportX = useStore((state) => state.transform[0]);
  const canvasWidth = useStore((state) => state.width);
  return node.data.kind === 'image' && node.data.assetId
    ? <ImageNodeTools node={node} />
    : <div className="empty-node-note nodrag nowheel" style={{ '--inverse-zoom': 1 / zoom, '--generator-shift': `${Math.max(0, viewportX + canvasWidth * 0)}px` } as React.CSSProperties}>上传真实图片后可使用本地编辑工具</div>;
}

/**
 * 生成节点 footer（对齐参考：通道|模型 + 比例/尺寸 + 生成按钮）。
 * 当前生成走 jobRunner(agent.request)，模型选择仅存 node.data，后端接生图/生视频时透传。
 */
function TitleInput({ nodeId, label, onLabel }: { nodeId: string; label: string; onLabel: (label: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(label);
  if (editing) {
    return (
      <input
        className="node-card__header-title nodrag"
        aria-label="节点标题"
        autoFocus
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => { onLabel(draft.trim() || label); setEditing(false); }}
        onKeyDown={(e) => { if (e.key === 'Enter') { onLabel(draft.trim() || label); setEditing(false); } if (e.key === 'Escape') { setDraft(label); setEditing(false); } }}
      />
    );
  }
  return <span className="node-card__header-title nodrag" title="点击重命名" onClick={() => { setDraft(label); setEditing(true); }}>{label}</span>;
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
  const updateNodeGeometry = useCanvasStore((state) => state.updateNodeGeometry);
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
  const deleteEdge = useCanvasStore((state) => state.deleteEdge);
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

  // 参考图池：沿上游 BFS（含传递闭包）收集带 assetId 的源节点，@图N 编号。
  // 仅生成类节点（imageGen/videoGen）需要；其它节点不建池（省计算）。
  const referencePool = useMemo(() => {
    if (data.kind !== 'imageGen' && data.kind !== 'videoGen') return null;
    return buildReferenceImagePool(id, nodes, edges, 1);
  }, [data.kind, id, nodes, edges]);

  // 当前 prompt 文本（删除参考图 token 时用）
  const promptText = (data.prompt ?? textDraft ?? '') as string;

  // 删除参考图：移除 @图N token + 断连该参考源节点 → 当前节点的上游边（对齐参考"删参考同时断上游"）
  const deleteReference = (entry: { number: number; sourceNodeId: string }) => {
    const cleaned = removeReferenceTokenByNumber(promptText, entry.number);
    setTextDraft(cleaned);
    updateNode(id, { prompt: cleaned });
    const edgesToCut = edges.filter((e) => e.target === id && e.source === entry.sourceNodeId);
    for (const edge of edgesToCut) deleteEdge(edge.id);
  };

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
      {selected && (
        <div className="node-selected-bar nodrag">
          <button aria-label="复制节点" onClick={(e) => { e.stopPropagation(); duplicateSelected(); }}><Copy size={13} /></button>
          <button aria-label="删除节点" className="is-danger" onClick={(e) => { e.stopPropagation(); deleteSelected(); }}><Trash2 size={13} /></button>
        </div>
      )}
      <div className="node-card">
        <div className="node-card__header nodrag">
          <Icon size={15} />
          <TitleInput nodeId={id} label={data.label} onLabel={(label) => updateNode(id, { label })} />
          {(data.kind === 'imageGen' || data.kind === 'storyboardGen') && data.isGenerating && (
            <span className="node-card__badge-gen">生成中</span>
          )}
        </div>
        <div className="node-card__body">
        {data.kind === 'script' ? (
          <div className="script-node-view">
            <textarea className="text-node-editor nodrag nowheel" aria-label="剧本内容" value={data.scriptText ?? textDraft} placeholder="在此粘贴分镜剧本…" onChange={(event) => { setTextDraft(event.target.value); updateNode(id, { scriptText: event.target.value }); }} />
            <button className="node-upload node-upload--generate nodrag" disabled={drafting} onClick={() => { const text = data.scriptText ?? textDraft; if (!text.trim()) { setDraftFeedback('请先输入剧本'); window.setTimeout(() => setDraftFeedback(null), 2200); return; } setDrafting(true); void submitScriptDrafting(text, id, (msg) => setDraftFeedback(msg)).then(() => setDraftFeedback('分镜任务已提交')).catch((error) => setDraftFeedback(error instanceof Error ? error.message : '分镜任务提交失败')).finally(() => setDrafting(false)); }}>{drafting ? <LoaderCircle className="is-spinning" size={14} /> : <FileText size={14} />} {drafting ? '生成中' : '生成分镜'}</button>
            {draftFeedback && <small className="script-node-feedback nodrag">{draftFeedback}</small>}
          </div>
        ) : data.kind === 'storyboard' ? (
          <div className="storyboard-grid">
            {Array.isArray(data.frames) && data.frames.length ? data.frames.map((frame, i) => (
              <figure key={i} className="storyboard-frame">
                <div className="storyboard-frame__thumb">{frame.img ? <img src={frame.img} alt="" /> : <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="rgba(238,240,223,.4)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" opacity="0.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>}</div>
                <figcaption className="storyboard-frame__cap"><span className="storyboard-frame__no">#{String(i + 1).padStart(2, '0')}</span></figcaption>
                {frame.description && <p className="storyboard-frame__desc-text">{frame.description}</p>}
              </figure>
            )) : <div className="empty-image-node"><Clapperboard size={26} /><strong>分镜节点</strong><span>连接剧本节点，生成后展示分镜帧</span></div>}
          </div>
        ) : data.kind === 'storyboardGen' ? (
          <StoryboardGenView node={node} updateNode={updateNode} />
        ) : data.kind === 'imageGen' ? (
          <ImageGenNode id={id} data={data} updateNode={updateNode} textDraft={textDraft} setTextDraft={setTextDraft} referencePool={referencePool!} deleteReference={deleteReference} draftFeedback={draftFeedback} setDraftFeedback={setDraftFeedback} />
        ) : data.kind === 'videoGen' ? (
          <VideoGenNode id={id} data={data} updateNode={updateNode} textDraft={textDraft} setTextDraft={setTextDraft} referencePool={referencePool!} deleteReference={deleteReference} draftFeedback={draftFeedback} setDraftFeedback={setDraftFeedback} />
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
      {(data.kind === 'imageGen' || data.kind === 'videoGen' || data.kind === 'storyboardGen') && (
        <NodeResizeHandle
          width={width ?? 250}
          height={height ?? 250}
          onResize={({ width: w, height: h }) => updateNodeGeometry(id, { width: w, height: h })}
          minWidth={360}
          maxWidth={900}
          minHeight={280}
          maxHeight={1000}
        />
      )}
    </article>
  );
}

export const MediaNode = memo(MediaNodeComponent);
