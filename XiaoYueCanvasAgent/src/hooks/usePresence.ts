import { useEffect, useState } from 'react';

/**
 * 显示/隐藏统一动效开关（presence）：
 * - open=true → 立刻挂载，present=true。进场由 CSS keyframe 动画负责：keyframe 不依赖「前一帧的样式」，
 *   元素插进来的那一帧就能动；用过渡的话浏览器从没渲染过起始态，进场会被整段吃掉。
 * - open=false → present 立刻翻成 false（CSS 从在场态过渡到退场态，这里一定存在前序状态），
 *   等 duration 之后才真正卸载。
 *
 * 渲染约定：根元素挂 `className="motion-presence"` + `data-present={present}`，
 * 视觉与时长由 styles.css 的动效层负责；`prefers-reduced-motion: reduce` 时这里直接即时卸载，
 * 那边也会关掉全部过渡与动画。
 */

// 与 styles.css 的 --motion-dur 对齐：退场动画播完再卸载，否则会被截断
const DEFAULT_DURATION = 220;

export function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export function usePresence(
  open: boolean,
  options: { duration?: number } = {},
): { mounted: boolean; present: boolean } {
  const duration = options.duration ?? DEFAULT_DURATION;
  const exitMs = prefersReducedMotion() ? 0 : duration;
  const [mounted, setMounted] = useState(open);

  useEffect(() => {
    if (open) {
      setMounted(true);
      return;
    }
    if (exitMs === 0) {
      setMounted(false);
      return;
    }
    const timer = window.setTimeout(() => setMounted(false), exitMs);
    return () => window.clearTimeout(timer);
  }, [open, exitMs]);

  return { mounted, present: open };
}
