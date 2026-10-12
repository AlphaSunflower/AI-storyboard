import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DocumentPreviewModal } from './DocumentPreviewModal';

vi.mock('../../lib/documentPreview', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/documentPreview')>();
  return {
    ...actual,
    loadDocumentSource: vi.fn().mockRejectedValue(new Error('offline')),
  };
});

afterEach(cleanup);

describe('DocumentPreviewModal', () => {
  it('renders nothing without a target', () => {
    const { container } = render(<DocumentPreviewModal target={null} onClose={vi.fn()} />);
    expect(container.querySelector('.library-doc-preview')).toBeNull();
  });

  it('renders the enlarged preview shell and closes from esc, backdrop and button', async () => {
    const onClose = vi.fn();
    render(
      <DocumentPreviewModal
        target={{ url: '/api/library/media/m1/content?token=t', name: '说明.md', mimeType: 'text/markdown' }}
        onClose={onClose}
      />,
    );

    expect(screen.getByRole('dialog', { name: '预览 说明.md' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '下载' })).toBeTruthy();
    expect(screen.getByText('text/markdown')).toBeTruthy();

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);

    onClose.mockClear();
    fireEvent.click(screen.getByText('text/markdown'));
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('dialog'));
    expect(onClose).toHaveBeenCalledTimes(1);

    onClose.mockClear();
    fireEvent.click(screen.getByRole('button', { name: '关闭预览' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
