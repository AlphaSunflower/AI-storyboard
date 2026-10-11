import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { ChatAppsHost } from './ChatAppsHost';
import type { ChatAppsController, ChatAppLayout } from './useChatApps';

const tabs = [{
  key: 'docs' as const,
  title: '使用文档',
  url: '/docs?embedded=1',
}];

function LayoutLoopHarness() {
  const [layout, setLayout] = useState<ChatAppLayout>({
    width: 420,
    height: 600,
    fullscreen: false,
    minimized: false,
  });
  const apps = {
    tabs,
    activeKey: 'docs',
    activeTab: tabs[0],
    layout,
    windowOpen: true,
    hasTabs: true,
    open: vi.fn(),
    close: vi.fn(),
    closeAll: vi.fn(),
    releaseCanvasMode: vi.fn(),
    bindCanvasMode: vi.fn(),
    select: vi.fn(),
    setLayout,
  } as unknown as ChatAppsController;

  return (
    <ChatAppsHost
      apps={apps}
      docked={false}
      splitMaxWidth={800}
      expandedSplitMaxWidth={900}
      sidebarCollapsed
      sidebarCollapseWidth={450}
      onCollapseSidebar={vi.fn()}
      onRequestDock={vi.fn()}
    />
  );
}

afterEach(cleanup);

describe('ChatAppsHost layout updates', () => {
  it('does not enter a maximum update depth loop when the app reports its layout', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<LayoutLoopHarness />);

    await act(async () => {
      await new Promise((resolve) => window.setTimeout(resolve, 50));
    });

    expect(screen.getByText('使用文档')).toBeTruthy();
    expect(errorSpy.mock.calls.flat().join(' '))
      .not.toContain('Maximum update depth exceeded');
    errorSpy.mockRestore();
  });
});
