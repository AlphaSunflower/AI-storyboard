import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ImagePreviewModal } from './ImagePreviewModal';

describe('ImagePreviewModal', () => {
  afterEach(() => cleanup());

  it('portals the preview to document.body above other modals', () => {
    const { container } = render(
      <ImagePreviewModal
        url="/api/files/images/reference.png"
        onClose={() => undefined}
      />,
    );

    const dialog = screen.getByRole('dialog', { name: '图片预览' });
    expect(container.querySelector('[role="dialog"]')).toBeNull();
    expect(dialog.parentElement).toBe(document.body);
    expect((dialog as HTMLElement).style.zIndex).toBe('4000');
    expect((dialog as HTMLElement).style.background).toBe('rgb(15, 15, 14)');
    expect((dialog as HTMLElement).style.backdropFilter).toBe('');
  });

  it('keeps image clicks open and closes from the backdrop or close button', () => {
    const onClose = vi.fn();
    render(
      <ImagePreviewModal
        url="/api/files/images/reference.png"
        onClose={onClose}
      />,
    );

    fireEvent.click(screen.getByAltText('预览大图'));
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '关闭预览' }));
    expect(onClose).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('dialog', { name: '图片预览' }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
