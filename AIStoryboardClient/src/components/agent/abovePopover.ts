/**
 * 「从输入卡向上展开」的浮层定位（视口坐标，纯函数 + 订阅 hook）。
 *
 * 这类浮层必须挂到 body：浮窗停靠态外层 `.chat-dock-shell.is-docked` 是
 * `overflow: hidden`，留在输入卡里向上弹会被裁掉——方案批准下拉少一截、
 * 斜杠命令列表整条不可见。
 */

import { useCallback, useEffect, useLayoutEffect, useState, type RefObject } from 'react';

const EDGE = 8;

export interface AbovePopoverPlacement {
  left: number;
  bottom: number;
  width?: number;
  maxHeight: number;
}

export interface AbovePopoverAnchorRect {
  left: number;
  right: number;
  top: number;
  width: number;
}

export interface AbovePopoverOptions {
  /** 浮层与锚点之间的间距 */
  gap: number;
  /** left=左对齐锚点；stretch=与锚点同宽（斜杠命令列表） */
  align?: 'left' | 'stretch';
  /** 左对齐时的固定宽度 */
  width?: number;
  /** 夹取时保留的最小宽度（不传则按 width 计算） */
  minWidth?: number;
}

/** 锚点 rect + 视口 → 向上展开的浮层定位；左右夹取到视口内，过高则内部滚动。 */
export function resolveAbovePopover(
  rect: AbovePopoverAnchorRect,
  viewport: { width: number; height: number },
  options: AbovePopoverOptions,
): AbovePopoverPlacement {
  const stretchWidth = Math.max(
    options.minWidth ?? 0,
    Math.min(rect.width, viewport.width - EDGE * 2),
  );
  const width = options.align === 'stretch' ? stretchWidth : options.width;
  const reserved = width ?? options.minWidth ?? 0;
  const left = Math.min(
    Math.max(EDGE, rect.left),
    Math.max(EDGE, viewport.width - reserved - EDGE),
  );
  return {
    left,
    bottom: Math.max(0, viewport.height - rect.top) + options.gap,
    ...(width ? { width } : {}),
    maxHeight: Math.max(0, rect.top - options.gap - EDGE),
  };
}

/**
 * 展开时测量锚点并跟随 resize / 滚动重新定位；收起时返回 null。
 * 用 layout effect 在浏览器绘制前完成定位，避免浮层先闪在错误位置。
 */
export function useAbovePopoverPlacement(
  open: boolean,
  anchorRef: RefObject<HTMLElement | null>,
  options: AbovePopoverOptions,
): AbovePopoverPlacement | null {
  const { gap, align, width, minWidth } = options;
  const [placement, setPlacement] = useState<AbovePopoverPlacement | null>(null);

  const measure = useCallback((): AbovePopoverPlacement | null => {
    const node = anchorRef.current;
    if (!node) return null;
    return resolveAbovePopover(
      node.getBoundingClientRect(),
      { width: window.innerWidth, height: window.innerHeight },
      { gap, align, width, minWidth },
    );
  }, [anchorRef, gap, align, width, minWidth]);

  useLayoutEffect(() => {
    if (!open) {
      setPlacement(null);
      return;
    }
    setPlacement(measure());
    const reposition = () => setPlacement(measure());
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    // 锚点自己也会变宽：输入卡从收缩态展开有 260ms 的 max-width 过渡（480→780），
    // 只在打开那一刻量一次会把浮层停在中间宽度上，比输入框窄一截。
    // ResizeObserver 跟随锚点尺寸，浮层始终与输入卡同宽。
    const node = anchorRef.current;
    let observer: ResizeObserver | undefined;
    if (node && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(reposition);
      observer.observe(node);
    }
    return () => {
      window.removeEventListener('resize', reposition);
      window.removeEventListener('scroll', reposition, true);
      observer?.disconnect();
    };
  }, [open, measure, anchorRef]);

  // 卸载兜底：锚点消失时清掉残留浮层
  useEffect(() => {
    if (!open) setPlacement(null);
  }, [open]);

  return placement;
}
