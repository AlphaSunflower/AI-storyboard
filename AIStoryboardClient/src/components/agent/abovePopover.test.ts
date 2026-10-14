import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveAbovePopover, useAbovePopoverPlacement } from './abovePopover';

const viewport = { width: 1440, height: 900 };

describe('resolveAbovePopover', () => {
  it('opens above the anchor and keeps the fixed width for left-aligned popovers', () => {
    const placement = resolveAbovePopover(
      { left: 468, right: 754, top: 830, width: 286 },
      viewport,
      { gap: 10, width: 286 },
    );

    expect(placement.left).toBe(468);
    expect(placement.bottom).toBe(900 - 830 + 10);
    expect(placement.width).toBe(286);
    expect(placement.maxHeight).toBe(830 - 10 - 8);
  });

  it('stretches to the anchor width for the slash menu', () => {
    const placement = resolveAbovePopover(
      { left: 453, right: 1233, top: 437, width: 780 },
      viewport,
      { gap: 8, align: 'stretch' },
    );

    expect(placement.width).toBe(780);
    expect(placement.left).toBe(453);
    expect(placement.bottom).toBe(900 - 437 + 8);
  });

  it('clamps the popover inside the viewport on both sides', () => {
    expect(
      resolveAbovePopover({ left: -20, right: 266, top: 400, width: 286 }, viewport, { gap: 10, width: 286 }).left,
    ).toBe(8);
    expect(
      resolveAbovePopover({ left: 1400, right: 1686, top: 400, width: 286 }, viewport, { gap: 10, width: 286 }).left,
    ).toBe(1440 - 286 - 8);
  });

  it('caps the stretched width to the viewport and never returns a negative height', () => {
    const narrow = resolveAbovePopover(
      { left: 0, right: 2000, top: 0, width: 2000 },
      { width: 800, height: 600 },
      { gap: 8, align: 'stretch' },
    );

    expect(narrow.width).toBe(800 - 16);
    expect(narrow.maxHeight).toBe(0);
    expect(narrow.bottom).toBe(600 + 8);
  });
});

describe('useAbovePopoverPlacement', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function anchorWith(width: () => number) {
    const node = document.createElement('div');
    node.getBoundingClientRect = (() => {
      const w = width();
      return {
        left: 300, right: 300 + w, top: 500, bottom: 560, width: w, height: 60, x: 300, y: 500,
        toJSON: () => ({}),
      } as DOMRect;
    }) as Element['getBoundingClientRect'];
    return { current: node };
  }

  it('keeps tracking the anchor when it resizes', () => {
    // 输入卡从收缩态展开有 260ms 的 max-width 过渡（480→780）。只在打开那一刻量一次，
    // 斜杠命令列表会停在中间宽度上，比输入框窄一截。
    const callbacks: Array<() => void> = [];
    class FakeResizeObserver {
      constructor(callback: () => void) { callbacks.push(callback); }
      observe() { /* 记录锚点即可，测试手动触发 */ }
      unobserve() {}
      disconnect() {}
    }
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);

    let renderedWidth = 480;
    const ref = anchorWith(() => renderedWidth);
    const { result } = renderHook(() =>
      useAbovePopoverPlacement(true, ref, { gap: 8, align: 'stretch' }));

    expect(result.current?.width).toBe(480);

    renderedWidth = 780;
    act(() => { callbacks.forEach((callback) => callback()); });

    expect(result.current?.width).toBe(780);
    expect(callbacks).toHaveLength(1);
  });

  it('measures without a ResizeObserver in the host', () => {
    // jsdom 默认没有 ResizeObserver，缺失时不能抛错。
    const ref = anchorWith(() => 780);
    const { result } = renderHook(() =>
      useAbovePopoverPlacement(true, ref, { gap: 8, align: 'stretch' }));

    expect(result.current?.width).toBe(780);
  });
});
