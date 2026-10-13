/**
 * 底部状态栏浮层的定位计算（纯函数，便于回归测试）。
 *
 * 注意：必须用「可见状态栏行」的 rect 定位，而不是外层盒子的 rect——
 * 外层盒子在真实 /chat 里是整列宽度，直接拿它的 left/right 会把卡片甩到屏幕边缘。
 */

/** 浮层与状态栏行之间的间距（px）。 */
export const CARD_GAP = 10;
/** 浮层宽度（与 ChatStatusBar.css 的 .chat-status-card 保持一致）。 */
export const CARD_WIDTH = 236;
/** 浮层总宽（含边框）+ 视口留白，用于水平夹取。 */
export const CARD_EDGE = CARD_WIDTH + 2 + 8;

export interface CardAnchor {
  /** 左对齐浮层的 left（runs 卡）。 */
  left: number;
  /** 右对齐浮层的 right（tokens / context 卡）。 */
  right: number;
  /** 浮层底边距离视口底部的距离。 */
  bottom: number;
  /** 上方可用高度，超出时浮层内部滚动。 */
  maxHeight: number;
}

export interface StatusBarRect {
  left: number;
  right: number;
  top: number;
}

export interface ViewportSize {
  width: number;
  height: number;
}

function clampSide(value: number, viewportWidth: number): number {
  const max = Math.max(8, viewportWidth - CARD_EDGE);
  return Math.min(Math.max(8, value), max);
}

/** 状态栏行 rect + 视口尺寸 → 浮层定位；水平方向夹取到视口内。 */
export function resolveCardAnchor(rect: StatusBarRect, viewport: ViewportSize): CardAnchor {
  return {
    left: clampSide(rect.left, viewport.width),
    right: clampSide(viewport.width - rect.right, viewport.width),
    bottom: Math.max(0, viewport.height - rect.top) + CARD_GAP,
    maxHeight: Math.max(0, rect.top - CARD_GAP - 8),
  };
}
