import { memo, useEffect, useMemo, useState } from 'react';
import { NodeResizer, NodeToolbar, Position, type NodeProps } from '@xyflow/react';
import { Image as ImageIcon, Minimize2, PanelRightOpen } from 'lucide-react';
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
import { TEXT_EDIT_NODE_KINDS, getNodeDefinition } from '../canvas/graphContracts';
import { useCanvasUi } from '../canvas/CanvasUiContext';
import { useNodeTextDraft } from '../hooks/useNodeTextDraft';
import { usePresence } from '../hooks/usePresence';
import { VideoMergeNode } from './VideoMergeNode';
import { CompactNodeView } from './nodes/CompactNodeView';
import { NodeExternalTitle } from './nodes/NodeExternalTitle';
import { NodePortHandles } from './nodes/NodePortHandles';

type MediaNodeProps = NodeProps<CanvasNode> & {
  displayCollapsed?: boolean;
  showMinimize?: boolean;
  onMinimize?: (nodeId: string) => void;
};

/**
 * 「预览面 + 框下编辑面板」的节点：除文字节点外的全部节点。
 * 外层的 .node-card 只留布局（不画第二层框），框内交给 .node-surface 预览面，
 * 所有操作控件由各视图挂到框下的 .node-float-panel。
 * 文字节点（text / note / prompt / script）保持原样：正文输入区就是内容本身。
 */
const SURFACE_KINDS = new Set([
  'image',
  'imageGen', 'textToImage', 'imageToImage', 'batchImageRefine', 'imageGeneration',
  'videoGen', 'textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo', 'videoGeneration',
  'storyboard', 'storyboardGen', 'storyboardPlanner', 'shot', 'videoMerge', 'sequence',
]);

function FullMediaNodeComponent({ id, data, selected, width, height, positionAbsoluteX, positionAbsoluteY, showMinimize, onMinimize }: MediaNodeProps) {
  const updateNode = useCanvasStore((state) => state.updateNode);
  const addNode = useCanvasStore((state) => state.addNode);
  const nodes = useCanvasStore((state) => state.nodes);
  const updateNodeGeometry = useCanvasStore((state) => state.updateNodeGeometry);
  const edges = useCanvasStore((state) => state.edges);
  const deleteEdge = useCanvasStore((state) => state.deleteEdge);
  const [draftFeedback, setDraftFeedback] = useState<string | null>(null);
  const [childMenu, setChildMenu] = useState(false);
  // 节点下方「+ 添加子节点」菜单：关闭时先退场再卸载
  const childMenuPresence = usePresence(childMenu, { duration: 140 });
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
  const definition = getNodeDefinition(data.kind);
  const generating = Boolean(data.isGenerating);

  // 参考图池：仅生成类节点（imageGen/videoGen）需要；其它节点不建池（省计算）。
  const referencePool = useMemo(() => {
    if (!['imageGen', 'videoGen', 'textToImage', 'imageToImage', 'batchImageRefine', 'storyboardPlanner', 'shot', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo', 'imageGeneration', 'videoGeneration'].includes(data.kind)) return null;
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
    <article className={`media-node media-node--${data.kind} ${SURFACE_KINDS.has(String(data.kind)) ? 'has-surface' : ''} ${selected ? 'is-selected' : ''}`}>
      <NodeExternalTitle nodeId={id} kind={data.kind} label={data.label} dragHandle />
      <div className="node-card">
        <div className={`node-card__header${generating ? ' is-generating' : ''}`}>
          {(data.kind === 'imageGen' || data.kind === 'videoGen' || data.kind === 'storyboardGen' || data.kind === 'storyboardPlanner' || data.kind === 'imageGeneration' || data.kind === 'videoGeneration') && data.isGenerating && (
            <span className="node-card__badge-gen">生成中</span>
          )}
          {showMinimize && (
            <button
              className="node-card__minimize nodrag"
              aria-label="最小化节点"
              title="最小化节点"
              onClick={(event) => { event.stopPropagation(); onMinimize?.(id); }}
            >
              <Minimize2 size={14} />
            </button>
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
        ) : data.kind === 'imageGen' || data.kind === 'textToImage' || data.kind === 'imageToImage' || data.kind === 'imageGeneration' ? (
          <ImageGenNode id={id} data={data} selected={selected} updateNode={updateNode} textDraft={promptDraft.value} setTextDraft={promptDraft.setValue} referencePool={referencePool!} deleteReference={deleteReference} draftFeedback={draftFeedback} setDraftFeedback={setDraftFeedback} />
        ) : data.kind === 'videoGen' || data.kind === 'textToVideo' || data.kind === 'imageToVideo' || data.kind === 'firstLastFrameToVideo' || data.kind === 'referenceToVideo' || data.kind === 'videoGeneration' ? (
          <VideoGenNode id={id} data={data} selected={selected} updateNode={updateNode} textDraft={promptDraft.value} setTextDraft={promptDraft.setValue} referencePool={referencePool} deleteReference={deleteReference} draftFeedback={draftFeedback} setDraftFeedback={setDraftFeedback} />
        ) : TEXT_EDIT_NODE_KINDS.has(String(data.kind)) ? (
          <TextNotePromptNode id={id} data={data} updateNode={updateNode} />
        ) : data.kind === 'sequence' ? (
          <SequenceNode id={id} data={data} updateNode={updateNode} onOpenChildMenu={() => setChildMenu(true)} />
        ) : data.kind === 'image' ? (
          <MediaAssetNode id={id} data={data} selected={selected} node={node} />
        ) : data.kind === 'videoMerge' ? (
          <VideoMergeNode node={node} />
        ) : (
          <div className="empty-image-node"><ImageIcon size={28} /><strong>未知节点</strong><span>该节点类型尚未实现</span></div>
        )}
        </div>
      </div>
      <NodePortHandles nodeId={id} nodeKind={data.kind} definition={definition} />
      {childMenuPresence.mounted && (
        <NodeToolbar nodeId={id} isVisible position={Position.Bottom} align="center" offset={12} className="node-add-toolbar motion-presence nodrag nowheel" data-present={childMenuPresence.present} onPointerDown={(e) => e.stopPropagation()}>
          <GroupedNodeMenu onAdd={(kind) => {
            const cid = addNode(kind as any, { x: node.position.x + 40, y: node.position.y + (height ?? 250) + 60 });
            updateNode(id, { children: [...((data.children ?? []) as string[]), cid] });
            setChildMenu(false);
          }} />
        </NodeToolbar>
      )}
      {(['imageGen', 'videoGen', 'storyboardGen', 'storyboard', 'storyboardPlanner', 'shot', 'textToImage', 'imageToImage', 'batchImageRefine', 'textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo', 'imageGeneration', 'videoGeneration', 'videoMerge'] as const).includes(data.kind as never) && (
        <NodeResizer
          nodeId={id}
          isVisible={selected}
          minWidth={360}
          maxWidth={900}
          minHeight={280}
          maxHeight={1000}
          color="#f4f4f5"
          handleClassName="node-resizer-handle"
          lineClassName="node-resizer-line"
          onResize={(_, params) => updateNodeGeometry(id, { width: params.width, height: params.height })}
        />
      )}
    </article>
  );
}

function MediaNodeComponent(props: MediaNodeProps) {
  if (props.displayCollapsed) {
    return <CompactNodeView nodeId={props.id} data={props.data} selected={Boolean(props.selected)} />;
  }
  return <FullMediaNodeComponent {...props} />;
}

export const MediaNode = memo(MediaNodeComponent);
