import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AssetPreviewPopover, type AssetPreviewTarget } from './AssetPreviewPopover';

const rect = { top: 200, bottom: 286, left: 1000 };

function target(kind: AssetPreviewTarget['kind']): AssetPreviewTarget {
  return { url: `/api/files/images/a.${kind === 'video' ? 'mp4' : 'png'}`, kind, rect };
}

describe('AssetPreviewPopover', () => {
  afterEach(cleanup);

  it('renders nothing without a target', () => {
    const { container } = render(<AssetPreviewPopover target={null} />);
    expect(container.innerHTML).toBe('');
    expect(document.querySelector('.asset-preview')).toBeNull();
  });

  it('floats an image to the left of the hovered tile', () => {
    render(<AssetPreviewPopover target={target('image')} />);

    const popover = document.querySelector('.asset-preview') as HTMLElement;
    expect(popover).not.toBeNull();
    // 左侧 1000px 的格子，浮层要落在它左边而不是压在上面。
    expect(Number.parseFloat(popover.style.left)).toBeLessThan(rect.left);
    // 浮层标了 aria-hidden（纯装饰，读屏不该念一遍），所以只能从 DOM 取图。
    expect(popover.querySelector('img')?.getAttribute('src')).toContain('a.png');
  });

  it('clamps the floating panel into the viewport', () => {
    render(<AssetPreviewPopover target={{ ...target('image'), rect: { top: 0, bottom: 20, left: 40 } }} />);
    const popover = document.querySelector('.asset-preview') as HTMLElement;
    expect(Number.parseFloat(popover.style.left)).toBeGreaterThan(0);
    expect(Number.parseFloat(popover.style.top)).toBeGreaterThanOrEqual(0);
  });

  it('plays videos muted and inline', () => {
    render(<AssetPreviewPopover target={target('video')} />);

    const video = document.querySelector('.asset-preview video') as HTMLVideoElement;
    expect(video).not.toBeNull();
    expect(video.muted).toBe(true);
    expect(video.loop).toBe(true);
    expect(video.autoplay).toBe(true);
    expect(video.hasAttribute('playsinline')).toBe(true);
  });

  it('shows a document glyph instead of a broken frame', () => {
    render(<AssetPreviewPopover target={target('document')} />);

    expect(document.querySelector('.asset-preview--document')).not.toBeNull();
    expect(document.querySelector('.asset-preview img')).toBeNull();
    expect(document.querySelector('.asset-preview video')).toBeNull();
  });
});
