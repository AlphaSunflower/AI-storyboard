import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmbeddedAppWindow } from './EmbeddedAppWindow';
import { clearAuthSession, setAccessToken } from '../../../lib/authSession';

vi.mock('../DocumentPreviewSurface', () => ({
  DocumentPreviewSurface: () => <div data-testid="local-document-preview">本地文档预览</div>,
}));

const tabs = [{
  key: 'docs' as const,
  title: '使用文档',
  url: '/docs?embedded=1',
}];

afterEach(() => {
  cleanup();
  clearAuthSession();
});

describe('EmbeddedAppWindow chat dock resizing', () => {
  it('renders local document content like an app window without an iframe', () => {
    render(
      <EmbeddedAppWindow
        tabs={[{
          key: 'document',
          title: '项目计划书.docx',
          url: '',
          documentAttachment: {
            url: '/api/files/files/plan.docx',
            name: '项目计划书.docx',
            mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            kind: 'file',
            source: 'upload',
          },
        }]}
        activeKey="document"
        onSelectTab={vi.fn()}
        onCloseTab={vi.fn()}
        onCloseWindow={vi.fn()}
        docked={false}
        splitMaxWidth={800}
        expandedSplitMaxWidth={900}
        sidebarCollapsed
        sidebarCollapseWidth={450}
        onCollapseSidebar={vi.fn()}
        onRequestDock={vi.fn()}
        onLayoutChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId('local-document-preview')).toBeTruthy();
    expect(document.querySelector('iframe[data-app-key="document"]')).toBeNull();
    expect(screen.queryByRole('button', { name: '跳转到网页' })).toBeNull();
  });

  it('minimizes from the header action', () => {
    const onMinimize = vi.fn();
    render(
      <EmbeddedAppWindow
        tabs={tabs}
        activeKey="docs"
        onSelectTab={vi.fn()}
        onCloseTab={vi.fn()}
        onCloseWindow={vi.fn()}
        onMinimize={onMinimize}
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

    fireEvent.click(screen.getByRole('button', { name: '最小化为图标' }));
    expect(onMinimize).toHaveBeenCalledTimes(1);
  });

  it('keeps the iframe mounted while minimized and reuses it after restore', () => {
    const props = {
      tabs,
      activeKey: 'docs' as const,
      onSelectTab: vi.fn(),
      onCloseTab: vi.fn(),
      onCloseWindow: vi.fn(),
      docked: false,
      splitMaxWidth: 400,
      expandedSplitMaxWidth: 500,
      sidebarCollapsed: true,
      sidebarCollapseWidth: 450,
      onCollapseSidebar: vi.fn(),
      onRequestDock: vi.fn(),
      onLayoutChange: vi.fn(),
    };
    const { container, rerender } = render(
      <EmbeddedAppWindow {...props} minimized />,
    );
    const minimizedFrame = container.querySelector<HTMLIFrameElement>('iframe[data-app-key="docs"]');
    expect(minimizedFrame).toBeTruthy();
    expect(container.querySelector('.embedded-app-window')?.classList.contains('is-minimized'))
      .toBe(true);

    rerender(<EmbeddedAppWindow {...props} minimized={false} />);
    expect(container.querySelector('iframe[data-app-key="docs"]')).toBe(minimizedFrame);
  });

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

  it('passes the current access token to the canvas iframe', () => {
    setAccessToken('canvas-token');
    render(
      <EmbeddedAppWindow
        tabs={[{
          key: 'canvas',
          title: '画布',
          url: '/canvas/?embedded=1',
        }]}
        activeKey="canvas"
        onSelectTab={vi.fn()}
        onCloseTab={vi.fn()}
        onCloseWindow={vi.fn()}
        docked={false}
        splitMaxWidth={600}
        expandedSplitMaxWidth={600}
        sidebarCollapsed
        sidebarCollapseWidth={800}
        onCollapseSidebar={vi.fn()}
        onRequestDock={vi.fn()}
        onLayoutChange={vi.fn()}
      />,
    );
    const frame = document.querySelector<HTMLIFrameElement>('iframe[data-app-key="canvas"]');
    const postMessage = vi.spyOn(frame!.contentWindow!, 'postMessage');

    fireEvent.load(frame!);

    expect(postMessage).toHaveBeenCalledWith({
      type: 'ai-storyboard:canvas-auth',
      accessToken: 'canvas-token',
    }, window.location.origin);
  });
});
