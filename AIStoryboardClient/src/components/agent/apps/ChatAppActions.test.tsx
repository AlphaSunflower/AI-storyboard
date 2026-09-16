import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ChatAppActions } from './ChatAppActions';

afterEach(cleanup);

describe('ChatAppActions', () => {
  it('renders canvas, editor, and docs as inline actions', () => {
    const onOpen = vi.fn();
    render(<ChatAppActions onOpen={onOpen} />);

    expect(screen.getByRole('button', { name: '画布' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '编辑器' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '使用文档' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '应用' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '编辑器' }));
    expect(onOpen).toHaveBeenCalledWith('editor');
  });

  it('turns the canvas action into an exit action while locked', () => {
    const onExit = vi.fn();
    render(<ChatAppActions onOpen={vi.fn()} canvasPhase="locked" onExitCanvas={onExit} />);

    fireEvent.click(screen.getByRole('button', { name: '离开画布编辑模式' }));

    expect(onExit).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: '画布' })).toBeNull();
  });

  it('shows a queued exit label while the current turn is running', () => {
    render(<ChatAppActions onOpen={vi.fn()} canvasPhase="exit_pending" onExitCanvas={vi.fn()} />);

    expect(screen.getByRole('button', { name: '本轮结束后离开' }).hasAttribute('disabled')).toBe(true);
  });
});
