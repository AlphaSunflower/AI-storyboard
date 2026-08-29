import { useCallback, useEffect, useRef } from 'react';

/**
 * 节点右下角拖拽 resize 手柄（对齐参考 NodeResizeHandle）。
 * 用 ┘ 形边框指示，拖拽对角同时改宽高，min/max clamp，onResize 回调持续触发。
 * 挂在 node-inner 的位置:relative 容器上（bottom/right: -offset）。
 */
export interface ResizeResult {
  width: number;
  height: number;
}

interface NodeResizeHandleProps {
  width: number;
  height?: number;
  onResize: (result: ResizeResult) => void;
  minWidth?: number;
  maxWidth?: number;
  minHeight?: number;
  maxHeight?: number;
  size?: number;
  offset?: number;
}

export function NodeResizeHandle({
  width,
  height,
  onResize,
  minWidth = 360,
  maxWidth = 900,
  minHeight = 280,
  maxHeight = 1000,
  size = 18,
  offset = 6,
}: NodeResizeHandleProps) {
  const start = useRef({ x: 0, y: 0, w: 0, h: 0 });
  const dragging = useRef(false);

  const onMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    dragging.current = true;
    start.current = { x: e.clientX, y: e.clientY, w: width, h: height ?? 0 };
  }, [width, height]);

  useEffect(() => {
    const onMouseMove = (e: MouseEvent) => {
      if (!dragging.current) return;
      const dx = e.clientX - start.current.x;
      const dy = e.clientY - start.current.y;
      const w = Math.max(minWidth, Math.min(maxWidth, start.current.w + dx));
      const h = Math.max(minHeight, Math.min(maxHeight, start.current.h + dy));
      onResize({ width: w, height: h });
    };
    const onMouseUp = () => { dragging.current = false; };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => { document.removeEventListener('mousemove', onMouseMove); document.removeEventListener('mouseup', onMouseUp); };
  }, [onResize, minWidth, maxWidth, minHeight, maxHeight]);

  return (
    <div
      className="nodrag"
      onMouseDown={onMouseDown}
      style={{
        position: 'absolute',
        bottom: -offset,
        right: -offset,
        width: size,
        height: size,
        cursor: 'nwse-resize',
        zIndex: 20,
        borderRight: '2px solid rgba(238,240,223,.45)',
        borderBottom: '2px solid rgba(238,240,223,.45)',
        borderRadius: '0 0 6px 0',
        opacity: .35,
        transition: 'opacity .15s, border-color .15s',
      }}
      onMouseEnter={(e) => { const el = e.currentTarget; el.style.opacity = '.8'; el.style.borderColor = 'var(--acid)'; }}
      onMouseLeave={(e) => { if (!dragging.current) { const el = e.currentTarget; el.style.opacity = '.35'; el.style.borderColor = 'rgba(238,240,223,.45)'; } }}
    />
  );
}
