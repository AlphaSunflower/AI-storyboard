import { memo, useState } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { FileText, LoaderCircle } from 'lucide-react';
import { useCanvasStore } from '../../store/canvasStore';
import { submitScriptDrafting } from '../../services/jobRunner';
import type { CanvasNode } from '../../types';

function ScriptNodeComponent({ id, data, selected }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((s) => s.updateNode);
  const [drafting, setDrafting] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  return (
    <article className={`media-node is-script ${selected ? 'is-selected' : ''}`}>
      <div className="node-floating-title"><FileText size={12} /><span>{data.label}</span></div>
      <div className="node-card script-node-view">
        <textarea className="text-node-editor nodrag nowheel" aria-label="剧本内容" value={data.scriptText ?? ''} placeholder="在此粘贴分镜剧本…" onChange={(e) => updateNode(id, { scriptText: e.target.value })} />
        <button className="node-upload node-upload--generate nodrag" disabled={drafting} onClick={() => { const text = (data.scriptText ?? '').trim(); if (!text) { setFeedback('请先输入剧本'); window.setTimeout(() => setFeedback(null), 2200); return; } setDrafting(true); void submitScriptDrafting(text, id, (msg) => setFeedback(msg)).then(() => setFeedback('分镜任务已提交')).catch((err) => setFeedback(err instanceof Error ? err.message : '分镜提交失败')).finally(() => setDrafting(false)); }}>
          {drafting ? <LoaderCircle className="is-spinning" size={14} /> : <FileText size={14} />} {drafting ? '生成中' : '生成分镜'}
        </button>
        {feedback && <small className="node-upload-error nodrag">{feedback}</small>}
      </div>
      <Handle className="node-handle" id="left" type="target" position={Position.Left} />
      <Handle className="node-handle" id="right" type="source" position={Position.Right} />
    </article>
  );
}
export const ScriptNode = memo(ScriptNodeComponent);
