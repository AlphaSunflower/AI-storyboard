import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChatRailItem } from '../../lib/chatMessageRail';
import { ChatMessageRail } from './ChatMessageRail';

const items: ChatRailItem[] = [
  { id: 'u1', anchorId: 'u1', title: '第一条消息', preview: '第一条回复的最后一段' },
  { id: 'u2', anchorId: 'u2', title: '第二条消息', preview: '第二条回复的最后一段' },
  { id: 'u3', anchorId: 'u3', title: '第三条消息', preview: '' },
];

function card(root: HTMLElement) {
  return root.querySelector('.chat-message-rail__card');
}

afterEach(cleanup);

describe('ChatMessageRail', () => {
  it('renders one tick per round', () => {
    render(<ChatMessageRail items={items} activeId={null} onSelect={() => {}} />);

    expect(screen.getAllByRole('button')).toHaveLength(3);
    expect(screen.getByRole('button', { name: '跳转到：第二条消息' })).toBeTruthy();
    expect(screen.getByRole('navigation', { name: '消息导航' })).toBeTruthy();
  });

  it('reports the round behind the clicked tick', () => {
    const onSelect = vi.fn();
    render(<ChatMessageRail items={items} activeId={null} onSelect={onSelect} />);

    fireEvent.click(screen.getByRole('button', { name: '跳转到：第二条消息' }));

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(items[1]);
  });

  it('marks the active round with aria-current', () => {
    render(<ChatMessageRail items={items} activeId="u2" onSelect={() => {}} />);

    expect(screen.getByRole('button', { name: '跳转到：第二条消息' }).getAttribute('aria-current')).toBe('true');
    expect(screen.getByRole('button', { name: '跳转到：第一条消息' }).getAttribute('aria-current')).toBeNull();
  });

  it('shows the two-line card while a tick is hovered and closes it on Escape', () => {
    const { container } = render(<ChatMessageRail items={items} activeId={null} onSelect={() => {}} />);
    const target = screen.getByRole('button', { name: '跳转到：第二条消息' });

    fireEvent.pointerOver(target);
    expect(card(container)?.textContent).toContain('第二条消息');
    expect(card(container)?.textContent).toContain('第二条回复的最后一段');

    fireEvent.keyDown(target, { key: 'Escape' });
    expect(card(container)).toBeNull();
  });

  it('shows the card for the focused tick as well', () => {
    const { container } = render(<ChatMessageRail items={items} activeId={null} onSelect={() => {}} />);

    fireEvent.focus(screen.getByRole('button', { name: '跳转到：第三条消息' }));

    expect(card(container)?.textContent).toBe('第三条消息');
  });

  it('drops the oldest rounds when the stack cannot fit at the minimum gap', () => {
    const clientHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
      configurable: true,
      get: () => 300,
    });

    try {
      const many: ChatRailItem[] = Array.from({ length: 50 }, (_, index) => ({
        id: `u${index}`,
        anchorId: `u${index}`,
        title: `第 ${index + 1} 条消息`,
        preview: '',
      }));

      render(<ChatMessageRail items={many} activeId={null} onSelect={() => {}} />);

      const buttons = screen.getAllByRole('button');
      expect(buttons).toHaveLength(9);
      expect(buttons[0].getAttribute('aria-label')).toBe('跳转到：第 42 条消息');
      expect(buttons[8].getAttribute('aria-label')).toBe('跳转到：第 50 条消息');
    } finally {
      if (clientHeight) Object.defineProperty(HTMLElement.prototype, 'clientHeight', clientHeight);
      else delete (HTMLElement.prototype as { clientHeight?: unknown }).clientHeight;
    }
  });
});
