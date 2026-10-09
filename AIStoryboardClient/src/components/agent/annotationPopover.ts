/** 注释浮层（操作条 / 评论框 / 列表 / 胶囊）共用的定位工具。 */
export interface AnchorRect {
  top: number;
  left: number;
  bottom: number;
  right: number;
}

export function toAnchorRect(rect: DOMRect): AnchorRect {
  return { top: rect.top, left: rect.left, bottom: rect.bottom, right: rect.right };
}

export const ANNOTATION_POPOVER_WIDTH = 300;
export const ANNOTATION_POPOVER_MARGIN = 12;

/** 把浮层固定在锚点附近并夹进视口;空间不够时翻到锚点上方。 */
export function popoverPosition(anchor: AnchorRect, width = ANNOTATION_POPOVER_WIDTH) {
  const viewportWidth = typeof window === 'undefined' ? 1024 : window.innerWidth;
  const viewportHeight = typeof window === 'undefined' ? 768 : window.innerHeight;
  const left = Math.min(
    Math.max(ANNOTATION_POPOVER_MARGIN, anchor.left),
    Math.max(ANNOTATION_POPOVER_MARGIN, viewportWidth - width - ANNOTATION_POPOVER_MARGIN),
  );
  const below = anchor.bottom + 8;
  if (below > viewportHeight - 200) {
    return { left, bottom: Math.max(ANNOTATION_POPOVER_MARGIN, viewportHeight - anchor.top + 8), top: undefined };
  }
  return { left, top: below, bottom: undefined };
}
