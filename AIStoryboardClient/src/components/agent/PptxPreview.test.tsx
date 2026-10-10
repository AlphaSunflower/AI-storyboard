import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PptxPreview } from './PptxPreview';

const init = vi.fn();
const preview = vi.fn();

vi.mock('pptx-preview', () => ({
  init,
}));

describe('PptxPreview', () => {
  beforeEach(() => {
    init.mockReset();
    preview.mockReset();
    init.mockReturnValue({ preview });
  });

  afterEach(() => {
    cleanup();
  });

  it('renders a PPTX blob through pptx-preview and preserves slide content', async () => {
    preview.mockImplementation(async (_buffer: ArrayBuffer) => {
      const host = init.mock.calls[0][0] as HTMLElement;
      host.innerHTML = [
        '<div class="pptx-preview-wrapper">',
        '<section class="pptx-preview-slide-wrapper">',
        '<p style="text-align: center; font-size: 24pt">幻灯片标题</p>',
        '<script>window.__unsafe = true</script>',
        '</section>',
        '</div>',
      ].join('');
    });
    const source = new Blob(['pptx-bytes'], {
      type: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    });

    const { container } = render(<PptxPreview source={source} />);

    expect(await screen.findByText('幻灯片标题')).toBeTruthy();
    expect(init).toHaveBeenCalledWith(
      expect.any(HTMLDivElement),
      { width: 960, mode: 'list' },
    );
    expect(preview).toHaveBeenCalledWith(expect.any(ArrayBuffer));
    const paragraph = container.querySelector<HTMLParagraphElement>('p');
    expect(paragraph?.style.textAlign).toBe('center');
    expect(paragraph?.style.fontSize).toBe('24pt');
    expect(container.querySelector('script')).toBeNull();
  });

  it('shows a readable error when pptx-preview fails', async () => {
    preview.mockRejectedValue(new Error('invalid pptx'));
    render(<PptxPreview source={new Blob(['bad'])} />);

    expect(await screen.findByText('PowerPoint 文档渲染失败')).toBeTruthy();
    expect(screen.getByText('invalid pptx')).toBeTruthy();
  });
});
