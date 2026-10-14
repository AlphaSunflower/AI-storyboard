import { getNodeDefinition } from '../../canvas/graphContracts';
import type { CanvasNode } from '../../types';
import { NodePortHandles } from './NodePortHandles';

export function CompactNodeView({
  nodeId,
  data,
  selected,
}: {
  nodeId: string;
  data: CanvasNode['data'];
  selected: boolean;
}) {
  const definition = getNodeDefinition(data.kind);
  const label = data.label?.trim() || definition?.title || String(data.kind);

  return (
    <article className={`media-node media-node--${data.kind} media-node--compact node-drag-handle ${selected ? 'is-selected' : ''}`}>
      <span className="compact-node-label" title={label}>{label}</span>
      <NodePortHandles nodeId={nodeId} nodeKind={data.kind} definition={definition} />
    </article>
  );
}
