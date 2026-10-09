import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ContextUsageRing } from './ContextUsageRing';

afterEach(cleanup);

const anchor = () => document.querySelector('.chat-context-ring-anchor')!;

describe('ContextUsageRing', () => {
  it('renders the live usage percentage inside the ring', () => {
    render(<ContextUsageRing usedTokens={70_000} limitTokens={996_000} />);
    const meter = screen.getByRole('meter');
    expect(meter.textContent).toContain('7%');
    expect(meter.getAttribute('aria-label')).toContain('上下文占用 7%');
  });

  it('shows the dark detail card on hover with used/remaining and token counts', () => {
    render(<ContextUsageRing usedTokens={70_000} limitTokens={996_000} />);
    expect(document.querySelector('.chat-context-tip')).toBeNull();

    fireEvent.mouseEnter(anchor());
    const tip = document.querySelector('.chat-context-tip')!;
    expect(tip).not.toBeNull();
    expect(tip.textContent).toContain('背景信息窗口：');
    expect(tip.textContent).toContain('7% 已用（剩余 93%）');
    expect(tip.textContent).toContain('已用 70k 标记，共 996k');
  });

  it('hides the detail card when disabled (read-only conversation)', () => {
    render(<ContextUsageRing usedTokens={70_000} limitTokens={996_000} disabled />);
    fireEvent.mouseEnter(anchor());
    expect(document.querySelector('.chat-context-tip')).toBeNull();
  });

  it('appends a cache-hit line to the detail card when cached tokens exist', () => {
    render(<ContextUsageRing usedTokens={70_000} limitTokens={996_000} cachedTokens={42_000} />);
    fireEvent.mouseEnter(anchor());
    expect(document.querySelector('.chat-context-tip')!.textContent).toContain('其中缓存命中 42k');
  });

  it('escalates the ring color as occupancy crosses thresholds', () => {
    const strokeOf = () =>
      screen.getByRole('meter').querySelectorAll('circle')[1]?.getAttribute('stroke');
    const { rerender } = render(<ContextUsageRing usedTokens={10_000} limitTokens={128_000} />);
    expect(strokeOf()).toBe('rgb(65, 118, 230)');

    rerender(<ContextUsageRing usedTokens={100_000} limitTokens={128_000} />);
    expect(strokeOf()).toBe('rgb(212, 160, 23)');

    rerender(<ContextUsageRing usedTokens={124_000} limitTokens={128_000} />);
    expect(strokeOf()).toBe('rgb(217, 45, 32)');
  });
});
