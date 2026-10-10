import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { hardenOfficeDom } from '../../lib/docxPreview';
import { DocxPreview } from './DocxPreview';

const renderAsync = vi.fn();

vi.mock('docx-preview', () => ({
  renderAsync,
}));

describe('DocxPreview', () => {
  beforeEach(() => {
    renderAsync.mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders a DOCX blob through docx-preview and keeps its layout styles', async () => {
    renderAsync.mockImplementation(async (_source: Blob, body: HTMLElement) => {
      body.innerHTML = [
        '<section class="document-docx">',
        '<p style="text-align: center; font-size: 20pt">居中标题</p>',
        '<script>window.__unsafe = true</script>',
        '<a href="javascript:alert(1)">危险链接</a>',
        '</section>',
      ].join('');
    });
    const source = new Blob(['docx-bytes'], {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });

    const { container } = render(<DocxPreview source={source} />);

    expect(await screen.findByText('居中标题')).toBeTruthy();
    expect(renderAsync).toHaveBeenCalledWith(
      source,
      expect.any(HTMLDivElement),
      expect.any(HTMLDivElement),
      expect.objectContaining({
        className: 'document-docx',
        breakPages: true,
        ignoreFonts: false,
        ignoreLastRenderedPageBreak: false,
        useBase64URL: true,
        renderAltChunks: false,
      }),
    );
    const paragraph = container.querySelector<HTMLParagraphElement>('p');
    expect(paragraph?.style.textAlign).toBe('center');
    expect(paragraph?.style.fontSize).toBe('20pt');
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('a')?.hasAttribute('href')).toBe(false);
  });

  it('shows a readable error when docx-preview fails', async () => {
    renderAsync.mockRejectedValue(new Error('invalid docx'));
    render(<DocxPreview source={new Blob(['bad'])} />);

    expect(await screen.findByText('Word 文档渲染失败')).toBeTruthy();
    expect(screen.getByText('invalid docx')).toBeTruthy();
  });
});

describe('hardenOfficeDom', () => {
  it('removes active content and unsafe URL protocols', () => {
    const root = document.createElement('div');
    root.innerHTML = [
      '<iframe src="https://unsafe.example"></iframe>',
      '<img src="javascript:alert(1)" onerror="alert(1)">',
      '<img src="data:image/png;base64,AAAA">',
      '<a href="https://example.com">safe</a>',
    ].join('');

    hardenOfficeDom(root);

    expect(root.querySelector('iframe')).toBeNull();
    expect(root.querySelector('img')?.hasAttribute('src')).toBe(false);
    expect(root.querySelector('img')?.hasAttribute('onerror')).toBe(false);
    expect(root.querySelector('img[src^="data:image/"]')).toBeTruthy();
    expect(root.querySelector('a')?.getAttribute('target')).toBe('_blank');
    expect(root.querySelector('a')?.getAttribute('rel')).toBe('noreferrer');
  });
});
