import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MinimizedAppsLauncher } from './MinimizedAppsLauncher';

const tabs = [
  { key: 'canvas' as const, title: '画布', url: '/canvas/?embedded=1' },
  { key: 'document' as const, title: '项目计划书.docx', url: '', documentAttachment: {
    url: '/api/files/files/plan.docx',
    name: '项目计划书.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    kind: 'file' as const,
    source: 'upload' as const,
  } },
  { key: 'side-chat' as const, title: '侧边 · 主对话', url: '/chat?embed=1' },
];

afterEach(cleanup);

describe('MinimizedAppsLauncher', () => {
  it('shows the tab count, restores the window, and can close all tabs', () => {
    const restore = vi.fn();
    const closeAll = vi.fn();
    render(
      <MinimizedAppsLauncher
        tabs={tabs}
        activeKey="document"
        onRestore={restore}
        onCloseAll={closeAll}
      />,
    );

    expect(screen.getByText('3')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '展开项目计划书.docx' }));
    expect(restore).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: '关闭全部窗口' }));
    expect(closeAll).toHaveBeenCalledTimes(1);
  });

  it('can be dragged vertically without restoring the window', () => {
    const restore = vi.fn();
    const { container } = render(
      <MinimizedAppsLauncher
        tabs={tabs}
        activeKey="canvas"
        onRestore={restore}
        onCloseAll={vi.fn()}
      />,
    );
    const launcher = container.querySelector<HTMLElement>('.minimized-apps');
    expect(launcher).toBeTruthy();
    const before = launcher!.style.top;
    const drag = screen.getByRole('button', { name: '上下调整图标位置' });

    fireEvent.pointerDown(drag, { clientY: 300, pointerId: 1 });
    fireEvent.pointerMove(drag, { clientY: 360, pointerId: 1 });
    fireEvent.pointerUp(drag, { clientY: 360, pointerId: 1 });

    expect(launcher!.style.top).not.toBe(before);
    expect(restore).not.toHaveBeenCalled();
  });
});
