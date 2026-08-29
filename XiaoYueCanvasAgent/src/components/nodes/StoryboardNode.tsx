import { memo } from 'react';
import { Handle, Position, useStore, type NodeProps } from '@xyflow/react';
import { Layers } from 'lucide-react';
import type { CanvasNode } from '../../types';

// 分镜帧结构当前未定型，用宽松字段避免过度约束。
type StoryboardFrame = { img?: string; label?: string };

function StoryboardNodeComponent({ id, data, selected }: NodeProps<CanvasNode>) {
  const frames = (data.frames as StoryboardFrame[] | undefined) ?? [];
  const zoom = useStore((s) => s.transform[2]);
  return (
    <article className={`media-node is-storyboard ${selected ? 'is-selected' : ''}`}>
      <div className="node-floating-title"><Layers size={12} /><span>{data.label}</span></div>
      <div className="node-card storyboard-grid" style={{ '--inverse-zoom': 1 / zoom } as React.CSSProperties}>
        {frames.length ? frames.map((frame, i) => (
          <figure key={i} className="storyboard-frame">{frame.img && <img src={frame.img} alt="" />}<figcaption>{frame.label ?? `镜头${i + 1}`}</figcaption></figure>
        )) : <div className="empty-image-node"><span>连接剧本节点生成分镜</span></div>}
      </div>
      <Handle className="node-handle" id="left" type="target" position={Position.Left} />
    </article>
  );
}
export const StoryboardNode = memo(StoryboardNodeComponent);
