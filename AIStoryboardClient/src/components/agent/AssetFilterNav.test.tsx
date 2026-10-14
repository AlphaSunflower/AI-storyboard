import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AssetFilterNav } from './AssetFilterNav';
import type { AssetsTab } from './assetBrowserModel';

vi.mock('gsap', () => ({
  default: {
    killTweensOf: vi.fn(),
    set: vi.fn(),
    to: vi.fn(),
    timeline: vi.fn(() => ({ to: vi.fn().mockReturnThis() })),
  },
}));

function renderNav(value: AssetsTab = 'all') {
  const onChange = vi.fn();
  render(<AssetFilterNav value={value} onChange={onChange} />);
  return { onChange, tablist: screen.getByRole('tablist', { name: '会话资产筛选' }) };
}

describe('AssetFilterNav', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('offers every asset category including the storyboard and the catch-all', () => {
    const { tablist } = renderNav();

    const labels = within(tablist).getAllByRole('tab').map((tab) => tab.textContent);
    expect(labels).toEqual(['全部', '图片', '视频', '文档', '分镜', '其他']);
  });

  it('marks only the current category as selected', () => {
    const { tablist } = renderNav('document');

    expect(within(tablist).getByRole('tab', { name: '文档' }).getAttribute('aria-selected')).toBe('true');
    expect(within(tablist).getByRole('tab', { name: '全部' }).getAttribute('aria-selected')).toBe('false');
  });

  it('reports the chosen category back to the caller', () => {
    const { onChange, tablist } = renderNav();

    fireEvent.click(within(tablist).getByRole('tab', { name: '其他' }));

    expect(onChange).toHaveBeenCalledWith('other');
  });

  it('keeps a single tab stop and walks the categories with the arrow keys', () => {
    const { onChange, tablist } = renderNav('all');

    expect(within(tablist).getByRole('tab', { name: '全部' }).getAttribute('tabindex')).toBe('0');
    expect(within(tablist).getByRole('tab', { name: '图片' }).getAttribute('tabindex')).toBe('-1');

    fireEvent.keyDown(within(tablist).getByRole('tab', { name: '全部' }), { key: 'ArrowRight' });

    expect(onChange).toHaveBeenCalledWith('image');
    expect(document.activeElement).toBe(within(tablist).getByRole('tab', { name: '图片' }));
  });

  it('jumps to the first and last category with Home and End', () => {
    const { onChange, tablist } = renderNav('video');

    fireEvent.keyDown(within(tablist).getByRole('tab', { name: '视频' }), { key: 'End' });
    expect(onChange).toHaveBeenCalledWith('other');

    fireEvent.keyDown(within(tablist).getByRole('tab', { name: '视频' }), { key: 'Home' });
    expect(onChange).toHaveBeenLastCalledWith('all');
  });

  it('renders the goo filter definition used by the moving blob', () => {
    const { container } = render(
      <AssetFilterNav value="all" onChange={vi.fn()} />,
    );

    expect(container.querySelector('filter#asset-filter-goo')).toBeTruthy();
    expect(container.querySelector('feGaussianBlur')).toBeTruthy();
    expect(container.querySelector('feColorMatrix')).toBeTruthy();
  });

  it('keeps the colour layer out of the accessibility tree', () => {
    const { container } = render(
      <AssetFilterNav value="all" onChange={vi.fn()} />,
    );

    expect(container.querySelector('.asset-filter__goo')?.getAttribute('aria-hidden')).toBe('true');
  });
});
