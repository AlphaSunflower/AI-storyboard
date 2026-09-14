import { useRef, useState } from 'react';

/**
 * 长按拖动 + 松开回弹：
 * 按住拖拽时元素跟随鼠标移动（transform translate），松开后平滑回到原始位置。
 * onTap 用于区分「点击」与「拖动」——移动超过阈值则视为拖动，松开后抑制本次 click，避免误触开合逻辑。
 */
export function useDragSnap(onTap: () => void) {
  const [drag, setDrag] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const [returning, setReturning] = useState(false);
  const st = useRef({ sx: 0, sy: 0, moved: false, suppress: false });
  const returnTimer = useRef<number | null>(null);

  const onMouseDown = (e: React.MouseEvent) => {
    if (returnTimer.current !== null) {
      window.clearTimeout(returnTimer.current);
      returnTimer.current = null;
    }
    setReturning(false);
    st.current = { sx: e.clientX, sy: e.clientY, moved: false, suppress: false };
    setDragging(true);

    const onMove = (ev: MouseEvent) => {
      const dx = ev.clientX - st.current.sx;
      const dy = ev.clientY - st.current.sy;
      if (Math.abs(dx) + Math.abs(dy) > 4) st.current.moved = true;
      setDrag({ x: dx, y: dy });
    };
    const onUp = () => {
      setDragging(false);
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      if (st.current.moved) {
        st.current.suppress = true;
        setReturning(true);
        setDrag({ x: 0, y: 0 });
        returnTimer.current = window.setTimeout(() => {
          setReturning(false);
          returnTimer.current = null;
        }, 460);
        // click 事件在 mouseup 后触发，先吞掉这一下，再恢复
        setTimeout(() => { st.current.suppress = false; }, 0);
      }
    };

    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  const onClick = () => {
    if (st.current.suppress) { st.current.suppress = false; return; }
    onTap();
  };

  return {
    transform: `translate(${drag.x}px, ${drag.y}px)`,
    transition: dragging ? 'none' : 'transform 440ms cubic-bezier(0.22, 1.32, 0.36, 1)',
    dragging,
    returning,
    onMouseDown,
    onClick,
  };
}
