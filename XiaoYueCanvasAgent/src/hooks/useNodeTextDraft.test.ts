// @vitest-environment jsdom

import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useNodeTextDraft } from './useNodeTextDraft';

describe('useNodeTextDraft', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('coalesces rapid changes into one commit', () => {
    vi.useFakeTimers();
    const commit = vi.fn();
    const { result } = renderHook(() => useNodeTextDraft({
      nodeId: 'script-1',
      value: '',
      onChange: commit,
      delay: 200,
    }));

    act(() => {
      result.current.setValue('w');
      result.current.setValue('ww');
      result.current.setValue('www');
    });
    act(() => {
      vi.advanceTimersByTime(199);
    });
    expect(commit).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(commit).toHaveBeenCalledTimes(1);
    expect(commit).toHaveBeenCalledWith('www');
  });

  it('flushes the latest draft on blur', () => {
    vi.useFakeTimers();
    const commit = vi.fn();
    const { result } = renderHook(() => useNodeTextDraft({
      nodeId: 'script-1',
      value: '',
      onChange: commit,
      delay: 500,
    }));

    act(() => {
      result.current.onFocus();
      result.current.setValue('www');
      result.current.onBlur();
    });

    expect(commit).toHaveBeenCalledTimes(1);
    expect(commit).toHaveBeenCalledWith('www');
  });

  it('does not commit intermediate IME composition text', () => {
    vi.useFakeTimers();
    const commit = vi.fn();
    const { result } = renderHook(() => useNodeTextDraft({
      nodeId: 'script-1',
      value: '',
      onChange: commit,
      delay: 200,
    }));

    act(() => {
      result.current.onCompositionStart();
      result.current.setValue('w');
      result.current.setValue('ww');
      vi.advanceTimersByTime(500);
    });
    expect(commit).not.toHaveBeenCalled();

    act(() => {
      result.current.onCompositionEnd();
      vi.advanceTimersByTime(200);
    });
    expect(commit).toHaveBeenCalledTimes(1);
    expect(commit).toHaveBeenCalledWith('ww');
  });
});
