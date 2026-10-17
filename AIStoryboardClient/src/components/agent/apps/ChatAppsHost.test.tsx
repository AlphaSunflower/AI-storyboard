import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { ChatAppsHost, canvasPatchFromMetadata } from './ChatAppsHost';
import type { ChatAppsController, ChatAppLayout } from './useChatApps';
import { useAgentStore } from '../../../stores/agentStore';
import { CANVAS_GRAPH_UPDATED_MESSAGE } from '../../../lib/embeddedAppMessages';

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

describe('canvasPatchFromMetadata', () => {
  const patch = { nodes: [{ id: 'n1', type: 'image', position: { x: 0, y: 0 }, data: { kind: 'image' } }] };

  it('取合法补丁；缺失或结构非法时退回 undefined（只发 revision）', () => {
    expect(canvasPatchFromMetadata({ canvasPatch: patch })).toEqual(patch);
    expect(canvasPatchFromMetadata({})).toBeUndefined();
    expect(canvasPatchFromMetadata(null)).toBeUndefined();
    expect(canvasPatchFromMetadata({ canvasPatch: { nodes: [{ position: { x: 0, y: 0 } }] } })).toBeUndefined();
  });

  it('canvasPatchOverflow 为 true 时不发补丁（画布整图重载兜底）', () => {
    expect(canvasPatchFromMetadata({ canvasPatch: patch, canvasPatchOverflow: true })).toBeUndefined();
  });
});

describe('ChatAppsHost canvas patch relay', () => {
  const validPatch = {
    nodes: [{ id: 'n1', type: 'image', position: { x: 0, y: 0 }, data: { kind: 'image' } }],
    edges: [{ id: 'e1', source: 'n1', target: 'n2' }],
    removedNodeIds: [],
    removedEdgeIds: [],
  };
  const canvasTab = {
    key: 'canvas' as const,
    title: '画布',
    url: '/canvas/',
    canvasMode: {
      conversationId: 'c1',
      projectId: 'p1',
      parentOrigin: window.location.origin,
      canvasId: 'cv1',
    },
  };
  const canvasApps = () => ({
    tabs: [canvasTab],
    activeKey: 'canvas',
    activeTab: canvasTab,
    layout: { width: 420, height: 600, fullscreen: false, minimized: false },
    windowOpen: true,
    hasTabs: true,
    open: vi.fn(),
    close: vi.fn(),
    closeAll: vi.fn(),
    releaseCanvasMode: vi.fn(),
    bindCanvasMode: vi.fn(),
    select: vi.fn(),
    setLayout: vi.fn(),
  }) as unknown as ChatAppsController;
  const canvasRunMessage = (revision: number, canvasPatch?: unknown) => ({
    id: `message-${revision}`,
    conversationId: 'c1',
    role: 'assistant' as const,
    content: '',
    createdAt: new Date().toISOString(),
    metadata: {
      kind: 'canvas-run' as const,
      canvasId: 'cv1',
      canvasRunId: 'run-1',
      revision,
      ...(canvasPatch === undefined ? {} : { canvasPatch }),
    },
  });

  const renderCanvasHost = () => render(
    <ChatAppsHost
      apps={canvasApps()}
      docked={false}
      splitMaxWidth={800}
      expandedSplitMaxWidth={900}
      sidebarCollapsed
      sidebarCollapseWidth={450}
      onCollapseSidebar={vi.fn()}
      onRequestDock={vi.fn()}
    />,
  );

  const nextCanvasMessage = async (type: string = CANVAS_GRAPH_UPDATED_MESSAGE) => {
    const frame = document.querySelector<HTMLIFrameElement>('iframe[data-app-key="canvas"]');
    expect(frame?.contentWindow).toBeTruthy();
    return new Promise<Record<string, unknown>>((resolve) => {
      // 画布宿主还会先发 canvas-mode-control 等控制消息：只认目标类型。
      // 之前用 { once: true } 抓第一条消息，断言会拿到控制消息而假失败。
      const onMessage = (event: MessageEvent) => {
        const data = event.data as Record<string, unknown> | null;
        if (!data || data.type !== type) return;
        frame!.contentWindow!.removeEventListener('message', onMessage);
        resolve(data);
      };
      frame!.contentWindow!.addEventListener('message', onMessage);
    });
  };

  afterEach(() => {
    useAgentStore.setState({ messages: [] });
  });

  it('把 canvas-run 消息的 metadata.canvasPatch 一起 post 给画布 iframe', async () => {
    useAgentStore.setState({ messages: [] });
    renderCanvasHost();
    const received = nextCanvasMessage();

    await act(async () => {
      useAgentStore.setState({ messages: [canvasRunMessage(5, validPatch) as never] });
    });

    const data = await received;
    expect(data.type).toBe(CANVAS_GRAPH_UPDATED_MESSAGE);
    expect(data).toMatchObject({
      conversationId: 'c1',
      projectId: 'p1',
      canvasId: 'cv1',
      revision: 5,
      patch: validPatch,
    });
  });

  it('累积超限（canvasPatchOverflow）只发 revision，不带补丁', async () => {
    useAgentStore.setState({ messages: [] });
    renderCanvasHost();
    const received = nextCanvasMessage();

    const overflowMessage = canvasRunMessage(6, validPatch);
    (overflowMessage.metadata as Record<string, unknown>).canvasPatchOverflow = true;

    await act(async () => {
      useAgentStore.setState({ messages: [overflowMessage as never] });
    });

    const data = await received;
    expect(data.revision).toBe(6);
    expect(data.patch).toBeUndefined();
  });
});
