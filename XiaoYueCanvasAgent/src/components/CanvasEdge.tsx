import { memo } from 'react';
import { EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import { operationLabel } from '../canvas/operationLabels';
import type { CanvasEdge as CanvasEdgeType } from '../types';

function CanvasEdgeComponent({
  sourceX,
  sourceY,
  sourcePosition,
  targetX,
  targetY,
  targetPosition,
  selected,
  data,
}: EdgeProps<CanvasEdgeType>) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    curvature: 0.34,
  });

  const label = operationLabel(data?.operation);

  return (
    <>
      <g className={`canvas-edge ${selected ? 'is-selected' : ''}`}>
        <path className="canvas-edge__hit" d={path} />
        <path className="canvas-edge__line" d={path} />
      </g>
      {label && (
        <EdgeLabelRenderer>
          <div
            className="canvas-edge__label nodrag"
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              fontSize: 11,
              lineHeight: 1,
              padding: '3px 7px',
              borderRadius: 5,
              background: 'rgba(30, 32, 38, 0.85)',
              color: '#e6e3da',
              pointerEvents: 'none',
              whiteSpace: 'nowrap',
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

export const CanvasEdge = memo(CanvasEdgeComponent);
