import { Handle, Position } from '@xyflow/react';
import { getNodeDefinition } from '../../canvas/graphContracts';
import type { CanvasNode } from '../../types';

export function CompactNodeView({
  data,
  selected,
}: {
  data: CanvasNode['data'];
  selected: boolean;
}) {
  const definition = getNodeDefinition(data.kind);
  const label = data.label?.trim() || definition?.title || String(data.kind);

  return (
    <article className={`media-node media-node--${data.kind} media-node--compact node-drag-handle ${selected ? 'is-selected' : ''}`}>
      <span className="compact-node-label" title={label}>{label}</span>
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
    </article>
  );
}
