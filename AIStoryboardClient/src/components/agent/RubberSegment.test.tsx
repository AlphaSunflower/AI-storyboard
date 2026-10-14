import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RubberSegment, type RubberSegmentItem } from './RubberSegment';

vi.mock('gsap', () => ({
  default: {
    killTweensOf: vi.fn(),
    set: vi.fn(),
    to: vi.fn(),
    timeline: vi.fn(() => ({ to: vi.fn().mockReturnThis() })),
  },
}));

type Key = 'tasks' | 'assets' | 'records';

const ITEMS: RubberSegmentItem<Key>[] = [
  { key: 'tasks', label: '生成任务', count: 2 },
  { key: 'assets', label: '会话资产' },
  { key: 'records', label: '生成记录', opensDialog: true },
];

function renderSegment(value: Key = 'tasks') {
  const onChange = vi.fn();
  const view = render(
    <RubberSegment items={ITEMS} value={value} onChange={onChange} ariaLabel="生成中心视图" />,
  );
  return { onChange, view };
}

describe('RubberSegment', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders one tab per segment inside a labelled tablist', () => {
    renderSegment();

    const tablist = screen.getByRole('tablist', { name: '生成中心视图' });
    expect(within(tablist).getAllByRole('tab')).toHaveLength(3);
    expect(within(tablist).getByRole('tab', { name: /生成任务/ })).toBeTruthy();
    expect(within(tablist).getByRole('tab', { name: /会话资产/ })).toBeTruthy();
  });

  it('marks only the current segment as selected', () => {
    renderSegment('assets');

    expect(screen.getByRole('tab', { name: /会话资产/ }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: /生成任务/ }).getAttribute('aria-selected')).toBe('false');
    expect(screen.getByRole('tab', { name: /生成记录/ }).getAttribute('aria-selected')).toBe('false');
  });

  it('reports the clicked segment key to the caller', () => {
    const { onChange } = renderSegment();

    fireEvent.click(screen.getByRole('tab', { name: /生成记录/ }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('records');
  });

  it('declares the dialog segment as opening a popup', () => {
    renderSegment();

    expect(screen.getByRole('tab', { name: /生成记录/ }).getAttribute('aria-haspopup')).toBe('dialog');
    expect(screen.getByRole('tab', { name: /生成任务/ }).getAttribute('aria-haspopup')).toBeNull();
  });

  it('hides a zero count instead of showing a badge', () => {
    render(
      <RubberSegment
        items={[{ key: 'tasks', label: '生成任务', count: 0 }]}
        value="tasks"
        onChange={vi.fn()}
        ariaLabel="生成中心视图"
      />,
    );

    expect(screen.getByRole('tab', { name: '生成任务' }).querySelector('b')).toBeNull();
  });

  it('keeps a single tab stop and moves the selection with the arrow keys', () => {
    const { onChange } = renderSegment('tasks');

    expect(screen.getByRole('tab', { name: /生成任务/ }).getAttribute('tabindex')).toBe('0');
    expect(screen.getByRole('tab', { name: /会话资产/ }).getAttribute('tabindex')).toBe('-1');

    fireEvent.keyDown(screen.getByRole('tab', { name: /生成任务/ }), { key: 'ArrowRight' });

    expect(onChange).toHaveBeenCalledWith('assets');
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: /会话资产/ }));
  });

  it('wraps around at both ends', () => {
    const { onChange } = renderSegment('records');

    fireEvent.keyDown(screen.getByRole('tab', { name: /生成记录/ }), { key: 'ArrowRight' });
    expect(onChange).toHaveBeenCalledWith('tasks');

    fireEvent.keyDown(screen.getByRole('tab', { name: /生成记录/ }), { key: 'ArrowLeft' });
    expect(onChange).toHaveBeenLastCalledWith('assets');
  });

  it('keeps rendering when the host has no matchMedia', () => {
    const original = window.matchMedia;
    // @ts-expect-error 故意移除，模拟 jsdom / 老宿主
    delete window.matchMedia;

    expect(() => renderSegment()).not.toThrow();

    window.matchMedia = original;
  });
});
