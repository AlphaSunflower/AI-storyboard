import { type RefObject, useLayoutEffect } from 'react';

export function autoGrowTextareaMetrics(
  scrollHeight: number,
  lineHeight: number,
  verticalPadding: number,
  borderHeight: number,
  maxHeight: number,
) {
  const minHeight = lineHeight * 2 + verticalPadding + borderHeight;
  const height = Math.min(Math.max(scrollHeight, minHeight), maxHeight);
  return {
    height,
    minHeight,
    overflowY: scrollHeight > maxHeight ? 'auto' as const : 'hidden' as const,
  };
}

/** Textarea grows from two rows to a viewport-relative cap, then scrolls internally. */
export function useAutoGrowTextarea(
  ref: RefObject<HTMLTextAreaElement | null>,
  value: string,
  maxViewportRatio = 0.4,
) {
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;

    element.style.height = 'auto';
    const computed = window.getComputedStyle(element);
    const lineHeight = Number.parseFloat(computed.lineHeight) || 24;
    const verticalPadding = (Number.parseFloat(computed.paddingTop) || 0)
      + (Number.parseFloat(computed.paddingBottom) || 0);
    const borderHeight = (Number.parseFloat(computed.borderTopWidth) || 0)
      + (Number.parseFloat(computed.borderBottomWidth) || 0);
    const viewportHeight = window.visualViewport?.height ?? window.innerHeight;
    const metrics = autoGrowTextareaMetrics(
      element.scrollHeight,
      lineHeight,
      verticalPadding,
      borderHeight,
      Math.max(lineHeight * 2 + verticalPadding + borderHeight, viewportHeight * maxViewportRatio),
    );

    element.style.height = `${metrics.height}px`;
    element.style.minHeight = `${metrics.minHeight}px`;
    element.style.overflowY = metrics.overflowY;
  }, [maxViewportRatio, ref, value]);
}
