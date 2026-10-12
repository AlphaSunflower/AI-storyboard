import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastHost } from './ToastHost';
import { notify, useToastStore } from '../../stores/toastStore';

// jsdom 没有 Web Animations API：给燃线打桩并手动驱动 onfinish，模拟「烧到尽头」
let lastFuseAnimation: { onfinish: (() => void) | null; playState: string } | null = null;

function stubFuseAnimation() {
  const animation = {
    playState: 'running',
    onfinish: null as (() => void) | null,
    play() { animation.playState = 'running'; },
    pause() { animation.playState = 'paused'; },
    cancel() { animation.playState = 'idle'; },
  };
  lastFuseAnimation = animation;
  return animation as unknown as Animation;
}

describe('ToastHost', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
    Element.prototype.animate = stubFuseAnimation as unknown as Element['animate'];
    useToastStore.setState({ toasts: [] });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    lastFuseAnimation = null;
    useToastStore.setState({ toasts: [] });
  });

  it('renders nothing while the stack is empty', () => {
    const { container } = render(<ToastHost />);

    expect(container.querySelectorAll('.swipe-toast')).toHaveLength(0);
  });

  it('shows a notice and removes it once the fuse burns out', () => {
    render(<ToastHost />);

    act(() => { notify({ title: '已归档', description: '「对话一」' }); });
    expect(screen.getByText('已归档')).toBeTruthy();
    expect(screen.getByText('「对话一」')).toBeTruthy();
    expect(document.querySelector('.swipe-toast[data-phase="open"]')).toBeTruthy();

    // 燃线烧完 → 组件自行滑出，滑出结束后才通知 store 回收
    act(() => { lastFuseAnimation?.onfinish?.(); });
    expect(document.querySelector('.swipe-toast[data-phase="closing"]')).toBeTruthy();

    act(() => { vi.advanceTimersByTime(1000); });
    expect(useToastStore.getState().toasts).toHaveLength(0);
    expect(document.querySelectorAll('.swipe-toast')).toHaveLength(0);
  });

  it('keeps at most three notices open and closes the oldest first', () => {
    render(<ToastHost />);

    act(() => {
      for (let index = 0; index < 4; index += 1) notify({ title: `第 ${index} 条` });
    });

    const cards = document.querySelectorAll('.swipe-toast');
    expect(cards).toHaveLength(4);
    expect(document.querySelectorAll('.swipe-toast[data-phase="open"]')).toHaveLength(3);
    expect(cards[0].getAttribute('data-phase')).toBe('closing');
  });

  it('paints the fuse and the icon per variant', () => {
    render(<ToastHost />);

    act(() => { notify({ title: '删除失败', variant: 'error' }); });

    const card = document.querySelector('.swipe-toast__card');
    expect(card).toBeTruthy();
    expect(document.querySelector('.swipe-toast[data-fuse="bottom"]')).toBeTruthy();
    expect(document.querySelector('.swipe-toast__fuse')).toBeTruthy();
  });

  it('dismisses a notice from its close button', () => {
    render(<ToastHost />);

    act(() => { notify({ title: '已删除对话', description: '「文本主旨概括」' }); });
    const close = document.querySelector('.swipe-toast__close');
    expect(close).toBeTruthy();

    act(() => { fireEvent.click(close as Element); });
    expect(document.querySelector('.swipe-toast[data-phase="closing"]')).toBeTruthy();

    act(() => { vi.advanceTimersByTime(1000); });
    expect(useToastStore.getState().toasts).toHaveLength(0);
  });
});
