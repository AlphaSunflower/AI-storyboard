import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { FileText } from 'lucide-react';
import { useCanvasStore } from '../../store/canvasStore';
import type { CanvasNode } from '../../types';
import { useNodeTextDraft } from '../../hooks/useNodeTextDraft';

function ScriptNodeComponent({ id, data, selected }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((s) => s.updateNode);
  const scriptDraft = useNodeTextDraft({
    nodeId: `${id}:script`,
    value: String(data.scriptText ?? ''),
    onChange: (value) => updateNode(id, { scriptText: value }),
  });
  return (
    <article className={`media-node is-script ${selected ? 'is-selected' : ''}`}>
      <div className="node-floating-title"><FileText size={12} /><span>{data.label}</span></div>
      <div className="node-card script-node-view">
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
      <Handle className="node-handle" id="left" type="target" position={Position.Left} />
      <Handle className="node-handle" id="right" type="source" position={Position.Right} />
    </article>
  );
}
export const ScriptNode = memo(ScriptNodeComponent);
