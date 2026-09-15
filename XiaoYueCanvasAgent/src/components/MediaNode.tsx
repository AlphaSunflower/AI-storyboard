import { memo, useEffect, useMemo, useState } from 'react';
import { Handle, NodeResizer, NodeToolbar, Position, type NodeProps } from '@xyflow/react';
import { Clapperboard, Copy, FileText, GripVertical, Image as ImageIcon, LayoutGrid, Layers, MessageSquare, PanelRightOpen, Sparkles, Trash2, Type, Wand2 } from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasNode } from '../types';
import { buildReferenceImagePool } from '../canvas/referenceImagePool';
import { removeReferenceTokenByNumber } from '../canvas/referenceTokenEditing';
import { StoryboardGenView } from './nodes/StoryboardGenView';
import { StoryboardEditorView } from './nodes/StoryboardEditorView';
import { ImageGenNode } from './nodes/ImageGenNode';
import { VideoGenNode } from './nodes/VideoGenNode';
import { TextNotePromptNode } from './nodes/TextNotePromptNode';
import { SequenceNode } from './nodes/SequenceNode';
import { MediaAssetNode } from './nodes/MediaAssetNode';
import { StoryboardPlannerView } from './nodes/StoryboardPlannerView';
import { ShotNodeView } from './nodes/ShotNodeView';
import { BatchImageRefineView } from './nodes/BatchImageRefineView';
import { GroupedNodeMenu } from './ui/GroupedNodeMenu';
import { getNodeDefinition } from '../canvas/graphContracts';
import { useCanvasUi } from '../canvas/CanvasUiContext';
import { useNodeTextDraft } from '../hooks/useNodeTextDraft';

/** 节点标题（点击重命名）。 */
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
  return <span className="node-card__header-title" title="点击重命名" onClick={() => { setDraft(label); setEditing(true); }}>{label}</span>;
}

function MediaNodeComponent({ id, data, selected, width, height, positionAbsoluteX, positionAbsoluteY }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((state) => state.updateNode);
  const addNode = useCanvasStore((state) => state.addNode);
  const nodes = useCanvasStore((state) => state.nodes);
  const duplicateSelected = useCanvasStore((state) => state.duplicateSelected);
  const deleteSelected = useCanvasStore((state) => state.deleteSelected);
  const updateNodeGeometry = useCanvasStore((state) => state.updateNodeGeometry);
  const edges = useCanvasStore((state) => state.edges);
  const deleteEdge = useCanvasStore((state) => state.deleteEdge);
  const [draftFeedback, setDraftFeedback] = useState<string | null>(null);
  const [childMenu, setChildMenu] = useState(false);
  const { openInspector } = useCanvasUi();
  const promptDraft = useNodeTextDraft({
    nodeId: `${id}:prompt`,
    value: String(data.prompt ?? ''),
    onChange: (value) => updateNode(id, { prompt: value }),
  });
  const scriptDraft = useNodeTextDraft({
    nodeId: `${id}:script`,
    value: String(data.scriptText ?? ''),
    onChange: (value) => updateNode(id, { scriptText: value }),
  });
  const node: CanvasNode = { id, type: 'media', position: { x: positionAbsoluteX, y: positionAbsoluteY }, width, height, data };
  const Icon = data.kind === 'text' ? Type : data.kind === 'script' ? FileText : data.kind === 'note' ? MessageSquare : data.kind === 'prompt' ? Wand2 : data.kind === 'sequence' ? Layers : data.kind === 'storyboard' ? Clapperboard : data.kind === 'storyboardGen' ? LayoutGrid : data.kind === 'imageGen' ? Sparkles : ImageIcon;
  const definition = getNodeDefinition(data.kind);

  // 参考图池：仅生成类节点（imageGen/videoGen）需要；其它节点不建池（省计算）。
  const referencePool = useMemo(() => {
    if (!['imageGen', 'videoGen', 'textToImage', 'imageToImage', 'batchImageRefine', 'storyboardPlanner', 'shot', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo'].includes(data.kind)) return null;
    return buildReferenceImagePool(id, nodes, edges, 1);
  }, [data.kind, id, nodes, edges]);

  const promptText = promptDraft.value;

  useEffect(() => {
    if (!childMenu) return;
    const closeMenus = (event: PointerEvent) => {
      if ((event.target as Element).closest('.node-add-toolbar')) return;
      setChildMenu(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setChildMenu(false);
    };
    addEventListener('pointerdown', closeMenus, true);
    addEventListener('keydown', closeOnEscape);
    return () => {
      removeEventListener('pointerdown', closeMenus, true);
      removeEventListener('keydown', closeOnEscape);
    };
  }, [childMenu]);

  // 删除参考图：移除 @图N token + 断连该参考源节点 → 当前节点的上游边。
  const deleteReference = (entry: { number: number; sourceNodeId: string }) => {
    const cleaned = removeReferenceTokenByNumber(promptText, entry.number);
    promptDraft.setValue(cleaned, true);
    const edgesToCut = edges.filter((e) => e.target === id && e.source === entry.sourceNodeId);
    for (const edge of edgesToCut) deleteEdge(edge.id);
  };

  return (
    <article className={`media-node media-node--${data.kind} ${selected ? 'is-selected' : ''}`}>
      {selected && (
        <NodeToolbar nodeId={id} isVisible position={Position.Top} align="center" offset={12} className="node-selected-toolbar nodrag">
          <button aria-label="复制节点" onClick={(e) => { e.stopPropagation(); duplicateSelected(); }}><Copy size={13} /></button>
          <button aria-label="删除节点" className="is-danger" onClick={(e) => { e.stopPropagation(); deleteSelected(); }}><Trash2 size={13} /></button>
        </NodeToolbar>
      )}
      <div className="node-card">
        <div className="node-card__header node-drag-handle">
          <GripVertical className="node-drag-grip" size={14} />
          <Icon size={15} />
          <TitleInput nodeId={id} label={data.label} onLabel={(label) => updateNode(id, { label })} />
          {(data.kind === 'imageGen' || data.kind === 'videoGen' || data.kind === 'storyboardGen' || data.kind === 'storyboardPlanner') && data.isGenerating && (
            <span className="node-card__badge-gen">生成中</span>
          )}
          <button
            className="node-card__inspector nodrag"
            aria-label="编辑详情"
            title="编辑详情"
            onClick={(event) => { event.stopPropagation(); openInspector(id); }}
          >
            <PanelRightOpen size={14} />
          </button>
        </div>
        <div className="node-card__body">
        {data.kind === 'script' ? (
          <div className="script-node-view">
            <textarea
              className="text-node-editor nodrag nowheel"
              aria-label="剧本内容"
              value={scriptDraft.value}
              placeholder="在此粘贴分镜剧本…"
              onChange={(event) => scriptDraft.setValue(event.target.value)}
              onFocus={scriptDraft.onFocus}
              onBlur={scriptDraft.onBlur}
              onCompositionStart={scriptDraft.onCompositionStart}
              onCompositionEnd={scriptDraft.onCompositionEnd}
            />
          </div>
        ) : data.kind === 'storyboard' ? (
          <StoryboardEditorView node={node} updateNode={updateNode} />
        ) : data.kind === 'storyboardPlanner' ? (
          <StoryboardPlannerView node={node} updateNode={updateNode} referencePool={referencePool!} />
        ) : data.kind === 'shot' ? (
          <ShotNodeView node={node} updateNode={updateNode} referencePool={referencePool!} />
        ) : data.kind === 'batchImageRefine' ? (
          <BatchImageRefineView node={node} updateNode={updateNode} referencePool={referencePool!} />
        ) : data.kind === 'storyboardGen' ? (
          <StoryboardGenView node={node} updateNode={updateNode} />
        ) : data.kind === 'imageGen' || data.kind === 'textToImage' || data.kind === 'imageToImage' ? (
          <ImageGenNode id={id} data={data} updateNode={updateNode} textDraft={promptDraft.value} setTextDraft={promptDraft.setValue} referencePool={referencePool!} deleteReference={deleteReference} draftFeedback={draftFeedback} setDraftFeedback={setDraftFeedback} />
        ) : data.kind === 'videoGen' || data.kind === 'textToVideo' || data.kind === 'imageToVideo' || data.kind === 'firstLastFrameToVideo' || data.kind === 'referenceToVideo' ? (
          <VideoGenNode id={id} data={data} updateNode={updateNode} textDraft={promptDraft.value} setTextDraft={promptDraft.setValue} referencePool={referencePool} deleteReference={deleteReference} draftFeedback={draftFeedback} setDraftFeedback={setDraftFeedback} />
        ) : data.kind === 'text' || data.kind === 'note' || data.kind === 'prompt' ? (
          <TextNotePromptNode id={id} data={data} updateNode={updateNode} />
        ) : data.kind === 'sequence' ? (
          <SequenceNode id={id} data={data} updateNode={updateNode} onOpenChildMenu={() => setChildMenu(true)} />
        ) : data.kind === 'image' ? (
          <MediaAssetNode id={id} data={data} selected={selected} node={node} />
        ) : (
          <div className="empty-image-node"><ImageIcon size={28} /><strong>未知节点</strong><span>该节点类型尚未实现</span></div>
        )}
        </div>
      </div>
      {definition ? definition.inputs.map((input, index) => (
        <Handle
          key={input.id}
          className="node-handle"
          id={input.id}
          type="target"
          position={Position.Left}
          style={{ top: `${((index + 1) / (definition.inputs.length + 1)) * 100}%` }}
        />
      )) : <Handle className="node-handle" id="left" type="target" position={Position.Left} />}
      {definition ? definition.outputs.map((output, index) => (
        <Handle
          key={output.id}
          className="node-handle"
          id={output.id}
          type="source"
          position={Position.Right}
          style={{ top: `${((index + 1) / (definition.outputs.length + 1)) * 100}%` }}
        />
      )) : <Handle className="node-handle" id="right" type="source" position={Position.Right} />}
      {childMenu && (
        <NodeToolbar nodeId={id} isVisible position={Position.Bottom} align="center" offset={12} className="node-add-toolbar nodrag nowheel" onPointerDown={(e) => e.stopPropagation()}>
          <GroupedNodeMenu onAdd={(kind) => {
            const cid = addNode(kind as any, { x: node.position.x + 40, y: node.position.y + (height ?? 250) + 60 });
            updateNode(id, { children: [...((data.children ?? []) as string[]), cid] });
            setChildMenu(false);
          }} />
        </NodeToolbar>
      )}
      {(['imageGen', 'videoGen', 'storyboardGen', 'storyboard', 'storyboardPlanner', 'shot', 'textToImage', 'imageToImage', 'batchImageRefine', 'textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo'] as const).includes(data.kind as never) && (
        <NodeResizer
          nodeId={id}
          isVisible={selected}
          minWidth={360}
          maxWidth={900}
          minHeight={280}
          maxHeight={1000}
          color="#d7ff54"
          handleClassName="node-resizer-handle"
          lineClassName="node-resizer-line"
          onResize={(_, params) => updateNodeGeometry(id, { width: params.width, height: params.height })}
        />
      )}
    </article>
  );
}

export const MediaNode = memo(MediaNodeComponent);
