import { memo } from 'react';
import { useCanvasStore } from '../../store/canvasStore';
import type { CanvasNode } from '../../types';
import { NodeFloatPanel, NodeSurface } from './nodePanel';

// sequence 节点 body：预览面只说明挂载概况，子节点列表与「挂载节点」按钮都挂在框下面板。
// 挂载弹层（GroupedNodeMenu popover）必须渲染在 .node-card(overflow:hidden) 之外，故由 MediaNode 壳层持有 childMenu 状态，
// 通过 onOpenChildMenu 回调触发（与 addDir 弹层同属节点 chrome，留在壳层）。
function SequenceNodeComponent({ id, data, updateNode, onOpenChildMenu }: {
  id: string;
  data: CanvasNode['data'];
  updateNode: (id: string, patch: Record<string, unknown>) => void;
  onOpenChildMenu: () => void;
}) {
  const nodes = useCanvasStore((state) => state.nodes);
  const children = (data.children ?? []) as string[];
  return (
    <div className="sequence-node-view">
      <NodeSurface state={children.length ? 'ready' : 'empty'} className="sequence-node-surface">
        <div className="node-surface__empty">
          <strong>{children.length ? `${children.length} 个子节点` : '序列节点'}</strong>
          <span>{children.length ? '挂载的子节点随序列一起执行' : '暂未挂载子节点'}</span>
        </div>
      </NodeSurface>
      <NodeFloatPanel nodeId={id} className="sequence-node-panel">
        {children.length > 0 && (
          <ul className="sequence-children">
            {children.map((childId) => {
              const child = nodes.find((n) => n.id === childId);
              return (
                <li key={childId}><span>{child?.data.label || childId.slice(0, 10)}</span><button className="nodrag" aria-label="移除子节点" onClick={(e) => { e.stopPropagation(); updateNode(id, { children: children.filter((c) => c !== childId) }); }}>×</button></li>
              );
            })}
          </ul>
        )}
        <button className="node-upload node-upload--generate nodrag" onClick={(e) => { e.stopPropagation(); onOpenChildMenu(); }}>+ 挂载节点</button>
      </NodeFloatPanel>
    </div>
  );
}

export const SequenceNode = memo(SequenceNodeComponent);
