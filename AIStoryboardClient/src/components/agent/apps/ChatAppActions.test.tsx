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
});
