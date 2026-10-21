import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ChatEntryCards } from './ChatEntryCards';

afterEach(cleanup);

describe('ChatEntryCards', () => {
  it('renders 画布 / 编辑器 / 项目库 and reports the clicked entry', () => {
    const onOpen = vi.fn();
    render(<ChatEntryCards hasProject onOpen={onOpen} />);

    const buttons = screen.getAllByRole('button');
    expect(buttons.map((button) => button.getAttribute('data-entry'))).toEqual([
      'canvas',
      'editor',
      'library',
    ]);

    fireEvent.click(screen.getByRole('button', { name: /^画布/ }));
    expect(onOpen).toHaveBeenCalledWith('canvas');

    fireEvent.click(screen.getByRole('button', { name: /^编辑器/ }));
    expect(onOpen).toHaveBeenCalledWith('editor');

    fireEvent.click(screen.getByRole('button', { name: /^项目库/ }));
    expect(onOpen).toHaveBeenCalledWith('library');
  });

  it('asks for a project before the editor can be opened', () => {
    render(<ChatEntryCards hasProject={false} onOpen={vi.fn()} />);

    expect(screen.getByRole('button', { name: /^编辑器/ }).textContent).toContain('选择项目后');
  });

  it('does not reopen the canvas while a canvas turn is binding', () => {
    const onOpen = vi.fn();
    render(<ChatEntryCards hasProject canvasPhase="binding" onOpen={onOpen} />);

    const canvas = screen.getByRole('button', { name: /^画布/ }) as HTMLButtonElement;
    expect(canvas.disabled).toBe(true);
    fireEvent.click(canvas);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('keeps the compact variant to one card per destination', () => {
    render(<ChatEntryCards hasProject variant="compact" onOpen={vi.fn()} />);

    expect(screen.getAllByRole('button')).toHaveLength(3);
    expect(screen.getByRole('navigation', { name: '创作工作台入口' })).toBeTruthy();
  });
});
