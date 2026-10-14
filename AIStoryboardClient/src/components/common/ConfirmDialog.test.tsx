import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmDialog } from './ConfirmDialog';

function renderDialog(overrides: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmDialog
      open
      title="删除这条素材？"
      description="存储里的文件会一并删除。"
      confirmLabel="删除"
      onConfirm={onConfirm}
      onCancel={onCancel}
      {...overrides}
    />,
  );
  return { onConfirm, onCancel };
}

describe('ConfirmDialog', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders nothing while closed', () => {
    render(
      <ConfirmDialog open={false} title="删除？" confirmLabel="删除" onConfirm={vi.fn()} onCancel={vi.fn()} />,
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('calls onConfirm from the confirm button', () => {
    const { onConfirm } = renderDialog();
    fireEvent.click(screen.getByRole('button', { name: '删除' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('calls onCancel from the cancel button', () => {
    const { onCancel } = renderDialog();
    fireEvent.click(screen.getByRole('button', { name: '取消' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape', () => {
    const { onCancel } = renderDialog();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('closes when the backdrop is clicked but not when the dialog itself is', () => {
    const { onCancel } = renderDialog();
    const dialog = screen.getByRole('alertdialog');

    fireEvent.pointerDown(dialog);
    expect(onCancel).not.toHaveBeenCalled();

    fireEvent.pointerDown(dialog.parentElement!);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('disables the confirm button while busy', () => {
    const { onConfirm } = renderDialog({ busy: true });
    const button = screen.getByRole('button', { name: '处理中…' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.click(button);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
