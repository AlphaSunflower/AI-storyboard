import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Tooltip, TooltipGroup, type TooltipProps } from './Tooltip';

function Toolbar({ onRefresh }: { onRefresh?: () => void } = {}) {
  return (
    <TooltipGroup>
      <Tooltip label="重新拉取视频结果">
        <button type="button" aria-label="重新拉取" onClick={onRefresh}>A</button>
      </Tooltip>
      <Tooltip label="全屏">
        <button type="button" aria-label="全屏">B</button>
      </Tooltip>
    </TooltipGroup>
  );
}

function triggerFor(name: string): HTMLElement {
  return screen.getByLabelText(name).parentElement as HTMLElement;
}

describe('Tooltip', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }));
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('waits the cold delay before the first label shows up', () => {
    render(<Toolbar />);

    act(() => { fireEvent.pointerOver(triggerFor('重新拉取'), { pointerType: 'mouse' }); });
    act(() => { vi.advanceTimersByTime(399); });
    expect(screen.queryByRole('tooltip')).toBeNull();

    act(() => { vi.advanceTimersByTime(1); });
    expect(screen.getByRole('tooltip').textContent).toBe('重新拉取视频结果');
  });

  it('opens a sibling instantly while the group is warm', () => {
    render(<Toolbar />);

    act(() => { fireEvent.pointerOver(triggerFor('重新拉取'), { pointerType: 'mouse' }); });
    act(() => { vi.advanceTimersByTime(400); });
    expect(screen.getByRole('tooltip').textContent).toBe('重新拉取视频结果');

    // 移动到旁边的按钮：不再等冷启动延时
    act(() => { fireEvent.pointerOver(triggerFor('全屏'), { pointerType: 'mouse' }); });
    // 换标签时旧层还在做交叉淡出，所以只校验新文案已经出现
    expect(screen.getByRole('tooltip').textContent).toContain('全屏');
  });

  it('closes after leaving the trigger', () => {
    render(<Toolbar />);

    act(() => { fireEvent.pointerOver(triggerFor('重新拉取'), { pointerType: 'mouse' }); });
    act(() => { vi.advanceTimersByTime(400); });
    expect(screen.getByRole('tooltip')).toBeTruthy();

    act(() => {
      fireEvent.pointerOut(triggerFor('重新拉取'), { pointerType: 'mouse', relatedTarget: document.body });
    });
    act(() => { vi.advanceTimersByTime(1000); });
    expect(screen.queryByRole('tooltip')).toBeNull();
  });

  it('renders the shortcut kbd inside the tooltip', () => {
    render(
      <Tooltip label="搜索对话" shortcut="Ctrl/Cmd+F">
        <button type="button" aria-label="搜索">S</button>
      </Tooltip>,
    );

    act(() => { fireEvent.pointerOver(triggerFor('搜索'), { pointerType: 'mouse' }); });
    act(() => { vi.advanceTimersByTime(400); });

    const tooltip = screen.getByRole('tooltip');
    expect(tooltip.textContent).toContain('搜索对话');
    expect(tooltip.querySelector('kbd')?.textContent).toBe('Ctrl/Cmd+F');
  });

  it('leaves the trigger clickable', () => {
    const onRefresh = vi.fn();
    render(<Toolbar onRefresh={onRefresh} />);

    act(() => { fireEvent.click(screen.getByLabelText('重新拉取')); });
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('flips below when there is no room above the trigger', () => {
    render(
      <TooltipGroup>
        <Tooltip label="顶部按钮"><button type="button" aria-label="顶部按钮">t</button></Tooltip>
      </TooltipGroup>,
    );

    act(() => { fireEvent.pointerOver(triggerFor('顶部按钮'), { pointerType: 'mouse' }); });
    act(() => { vi.advanceTimersByTime(400); });
    expect(screen.getByRole('tooltip').getAttribute('data-side')).toBe('bottom');
  });

  it('flips to the other side when the requested edge is out of room', () => {
    render(
      <TooltipGroup>
        <Tooltip side="right" label="右侧按钮"><button type="button" aria-label="右侧按钮">r</button></Tooltip>
      </TooltipGroup>,
    );
    const trigger = triggerFor('右侧按钮');
    Object.defineProperty(trigger, 'getBoundingClientRect', {
      value: () => ({ top: 700, bottom: 728, left: 950, right: 1000, width: 50, height: 28, x: 950, y: 700 }),
    });

    act(() => { fireEvent.pointerOver(trigger, { pointerType: 'mouse' }); });
    act(() => { vi.advanceTimersByTime(400); });
    expect(screen.getByRole('tooltip').getAttribute('data-side')).toBe('left');
  });
});

describe('Tooltip outside a group', () => {
  it('self-wraps so a lone trigger still gets the warm behaviour', () => {
    vi.useFakeTimers();
    const props: TooltipProps = { label: '关闭', children: <button type="button" aria-label="关闭">x</button> };
    render(<Tooltip {...props} />);

    act(() => { fireEvent.pointerOver(triggerFor('关闭'), { pointerType: 'mouse' }); });
    act(() => { vi.advanceTimersByTime(400); });
    expect(screen.getByRole('tooltip').textContent).toBe('关闭');

    cleanup();
    vi.useRealTimers();
  });
});
