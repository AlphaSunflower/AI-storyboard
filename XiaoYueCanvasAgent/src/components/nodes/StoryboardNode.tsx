import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Layers } from 'lucide-react';
import type { CanvasNode } from '../../types';
import { useCanvasStore } from '../../store/canvasStore';
import { StoryboardEditorView } from './StoryboardEditorView';

function StoryboardNodeComponent({ id, data, selected }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((state) => state.updateNode);
  return (
    <article className={`media-node is-storyboard ${selected ? 'is-selected' : ''}`}>
      <div className="node-floating-title"><Layers size={12} /><span>{data.label}</span></div>
      <div className="node-card">
        <StoryboardEditorView node={{ id, data } as CanvasNode} updateNode={updateNode} />
      </div>
      <Handle className="node-handle" id="left" type="target" position={Position.Left} />
    </article>
  );
}
export const StoryboardNode = memo(StoryboardNodeComponent);
