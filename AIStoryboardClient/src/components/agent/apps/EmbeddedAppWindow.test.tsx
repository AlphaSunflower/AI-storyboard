import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmbeddedAppWindow } from './EmbeddedAppWindow';

const tabs = [{
  key: 'docs' as const,
  title: '使用文档',
  url: '/docs?embedded=1',
}];

afterEach(cleanup);

describe('EmbeddedAppWindow chat dock resizing', () => {
  it('reloads the focused app page from the header refresh button', () => {
    render(
      <EmbeddedAppWindow
        tabs={tabs}
        activeKey="docs"
        onSelectTab={vi.fn()}
        onCloseTab={vi.fn()}
        onCloseWindow={vi.fn()}
        docked={false}
        splitMaxWidth={400}
        expandedSplitMaxWidth={500}
        sidebarCollapsed
        sidebarCollapseWidth={450}
        onCollapseSidebar={vi.fn()}
        onRequestDock={vi.fn()}
        onLayoutChange={vi.fn()}
      />,
    );
    const before = document.querySelector('iframe[data-app-key="docs"]');

    fireEvent.click(screen.getByRole('button', { name: '刷新当前页面' }));

    expect(document.querySelector('iframe[data-app-key="docs"]')).not.toBe(before);
  });

  it('requests docked chat when the user drags beyond the split width', () => {
    const onRequestDock = vi.fn();
    render(
      <EmbeddedAppWindow
        tabs={tabs}
        activeKey="docs"
        onSelectTab={vi.fn()}
        onCloseTab={vi.fn()}
        onCloseWindow={vi.fn()}
        docked={false}
        splitMaxWidth={400}
        expandedSplitMaxWidth={500}
        sidebarCollapsed
        sidebarCollapseWidth={450}
        onCollapseSidebar={vi.fn()}
        onRequestDock={onRequestDock}
        onLayoutChange={vi.fn()}
      />,
    );

    const resize = screen.getByRole('button', { name: '拖拽调整窗口大小' });
    fireEvent.pointerDown(resize, { clientX: 500, clientY: 500, pointerId: 7 });
    fireEvent.pointerMove(document, { clientX: 300, clientY: 500, pointerId: 7 });
    fireEvent.pointerUp(document, { clientX: 300, clientY: 500, pointerId: 7 });

    expect(onRequestDock).toHaveBeenCalledTimes(1);
  });

  it('captures the pointer so inward resizing survives moving over the iframe', () => {
    const onRequestDock = vi.fn();
    const onLayoutChange = vi.fn();
    render(
      <EmbeddedAppWindow
        tabs={tabs}
        activeKey="docs"
        onSelectTab={vi.fn()}
        onCloseTab={vi.fn()}
        onCloseWindow={vi.fn()}
        docked
        splitMaxWidth={400}
        expandedSplitMaxWidth={400}
        sidebarCollapsed
        sidebarCollapseWidth={500}
        onCollapseSidebar={vi.fn()}
        onRequestDock={onRequestDock}
        onLayoutChange={onLayoutChange}
      />,
    );

    const resize = screen.getByRole('button', { name: '拖拽调整窗口大小' });
    const setPointerCapture = vi.fn();
    resize.setPointerCapture = setPointerCapture;
    fireEvent.pointerDown(resize, { clientX: 300, clientY: 500, pointerId: 9 });
    fireEvent.pointerMove(document, { clientX: 520, clientY: 500, pointerId: 9 });
    fireEvent.pointerUp(document, { clientX: 520, clientY: 500, pointerId: 9 });

    expect(setPointerCapture).toHaveBeenCalledWith(9);
    expect(onLayoutChange).toHaveBeenCalledWith(expect.objectContaining({ width: 360 }));
  });

  it('does not request docked chat while the app stays within the split width', () => {
    const onRequestDock = vi.fn();
    render(
      <EmbeddedAppWindow
        tabs={tabs}
        activeKey="docs"
        onSelectTab={vi.fn()}
        onCloseTab={vi.fn()}
        onCloseWindow={vi.fn()}
        docked={false}
        splitMaxWidth={600}
        expandedSplitMaxWidth={600}
        sidebarCollapsed
        sidebarCollapseWidth={800}
        onCollapseSidebar={vi.fn()}
        onRequestDock={onRequestDock}
        onLayoutChange={vi.fn()}
      />,
    );

    const resize = screen.getByRole('button', { name: '拖拽调整窗口大小' });
    fireEvent.pointerDown(resize, { clientX: 500, clientY: 500, pointerId: 11 });
    fireEvent.pointerMove(document, { clientX: 490, clientY: 500, pointerId: 11 });
    fireEvent.pointerUp(document, { clientX: 490, clientY: 500, pointerId: 11 });

    expect(onRequestDock).not.toHaveBeenCalled();
  });
});
