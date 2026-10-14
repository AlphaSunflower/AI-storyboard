// @vitest-environment jsdom

import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { prefersReducedMotion, usePresence } from './usePresence';

function stubReducedMotion(matches: boolean) {
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })));
}

describe('usePresence', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('打开时立刻挂载并进入在场态（进场交给 CSS keyframe，不靠前一帧状态）', () => {
    stubReducedMotion(false);
    const { result, rerender } = renderHook(({ open }) => usePresence(open), {
      initialProps: { open: false },
    });
    expect(result.current.mounted).toBe(false);

    rerender({ open: true });
    expect(result.current.mounted).toBe(true);
    expect(result.current.present).toBe(true);
  });

  it('关闭时先退场，duration 之后才卸载', () => {
    stubReducedMotion(false);
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ open }) => usePresence(open, { duration: 180 }), {
      initialProps: { open: true },
    });
    expect(result.current.mounted).toBe(true);

    rerender({ open: false });
    expect(result.current.present).toBe(false);
    expect(result.current.mounted).toBe(true);

    act(() => { vi.advanceTimersByTime(179); });
    expect(result.current.mounted).toBe(true);

    act(() => { vi.advanceTimersByTime(1); });
    expect(result.current.mounted).toBe(false);
  });

  it('卸载时清掉未到期的定时器', () => {
    stubReducedMotion(false);
    vi.useFakeTimers();
    const clear = vi.spyOn(window, 'clearTimeout');
    const { rerender, unmount } = renderHook(({ open }) => usePresence(open, { duration: 180 }), {
      initialProps: { open: true },
    });

    rerender({ open: false });
    const before = clear.mock.calls.length;
    unmount();
    expect(clear.mock.calls.length).toBeGreaterThan(before);
    clear.mockRestore();
  });

  it('reduced-motion 下即时卸载且不等待', () => {
    stubReducedMotion(true);
    expect(prefersReducedMotion()).toBe(true);
    const { result, rerender } = renderHook(({ open }) => usePresence(open), {
      initialProps: { open: true },
    });
    expect(result.current.mounted).toBe(true);

    rerender({ open: false });
    expect(result.current.mounted).toBe(false);
  });

  it('没有 matchMedia 时不炸（jsdom 默认不实现）', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(prefersReducedMotion()).toBe(false);
  });
});
