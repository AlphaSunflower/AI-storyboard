import { memo } from 'react';
import { EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';
import { operationLabel } from '../canvas/operationLabels';
import type { CanvasEdge as CanvasEdgeType } from '../types';

/**
 * 边质感（对齐参考 DataFlowEdge.tsx）：
 * halo 半透明底层 + glow 发光 filter + 白色流动虚线 + 2 个 animateMotion 流动光点 + 箭头 marker。
 * 配色沿用本项目 acid（荧光绿）主题，不抄参考的橙——项目配色红线。
 */
function CanvasEdgeComponent({
  id,
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
  const isSelected = Boolean(selected);

  // acid 荧光绿 + 选中更亮
  const lineColor = isSelected ? '#d7ff54' : 'rgba(215, 255, 84, 0.5)';
  const shadowColor = isSelected ? 'rgba(215, 255, 84, 0.5)' : 'rgba(215, 255, 84, 0.28)';
  const arrowId = `arrow-${id}`;
  const glowId = `glow-${id}`;
  const haloId = `halo-${id}`;

  return (
    <>
      <defs>
        <marker id={arrowId} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto">
          <path d="M 0 1.2 L 8.2 5 L 0 8.8 z" fill={lineColor} style={{ transition: 'fill 0.3s ease' }} />
        </marker>
        <filter id={glowId} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3.5" result="blur" />
          <feMerge>
            <feMergeNode in="blur" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
        <filter id={haloId} x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur stdDeviation="2.8" result="blur" />
          <feMerge>
            <feMergeNode in="blur" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>

      {/* 宽透明命中区 */}
      <path d={path} fill="none" stroke="transparent" strokeWidth={20} style={{ cursor: 'pointer' }} />

      {/* halo 底层 */}
      <path d={path} fill="none" stroke={shadowColor} strokeWidth={isSelected ? 12 : 9} strokeLinecap="round" opacity={isSelected ? 0.55 : 0.42} filter={`url(#${haloId})`} style={{ pointerEvents: 'none' }} />

      {/* 主边线（发光 + 可选选中态，React Flow 交互命中由上方透明宽 path 承担） */}
      <path className={`canvas-edge ${isSelected ? 'is-selected' : ''}`} d={path} fill="none" stroke={lineColor} strokeWidth={isSelected ? 4.5 : 3.4} strokeLinecap="round" opacity={isSelected ? 1 : 0.92} style={{ pointerEvents: 'none', transition: 'stroke 0.3s ease, stroke-width 0.3s ease, opacity 0.3s ease', filter: `drop-shadow(0 0 10px ${shadowColor})` }} />

      {/* 白色流动虚线 */}
      <path d={path} fill="none" stroke="rgba(255,255,255,0.42)" strokeWidth={isSelected ? 1.5 : 1} strokeLinecap="round" strokeDasharray="10 12" opacity={isSelected ? 0.85 : 0.5} style={{ pointerEvents: 'none' }} />

      {/* 箭头（不受虚线影响） */}
      <path d={path} fill="none" stroke="none" markerEnd={`url(#${arrowId})`} style={{ pointerEvents: 'none' }} />

      {/* 流动光点 1 */}
      <circle r="4.2" fill={lineColor} filter={`url(#${glowId})`} opacity="0.95" style={{ pointerEvents: 'none' }}>
        <animateMotion dur="2.5s" repeatCount="indefinite" path={path} />
      </circle>
      {/* 流动光点 2 */}
      <circle r="2.8" fill="#fff9d6" opacity="0.72" style={{ pointerEvents: 'none' }}>
        <animateMotion dur="2.5s" repeatCount="indefinite" path={path} begin="1.25s" />
      </circle>

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
