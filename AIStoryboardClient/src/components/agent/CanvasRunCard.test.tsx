import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useProjectStore } from '../../stores/projectStore';
import { ChatAppsProvider } from './apps/ChatAppsContext';
import { CanvasRunCard } from './CanvasRunCard';

describe('CanvasRunCard', () => {
  afterEach(() => {
    cleanup();
    useProjectStore.setState({ currentProject: null });
  });

  it('renders persistent canvas run progress and opens the canvas on demand', () => {
    const openApp = vi.fn();
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: '项目' } as never,
    });

    render(
      <ChatAppsProvider open={openApp}>
        <CanvasRunCard
          conversationId="conversation-1"
          metadata={{
            kind: 'canvas-run',
            canvasRunId: 'run-1',
            canvasId: 'canvas-1',
            turnId: 'turn-1',
            title: '雪茄房转场画布',
            status: 'writing',
            progress: 60,
            nodeCount: 8,
            edgeCount: 9,
            revision: 2,
          }}
        />
      </ChatAppsProvider>,
    );

    expect(screen.getByText('雪茄房转场画布')).toBeTruthy();
    expect(screen.getByText('8 节点 · 9 连线')).toBeTruthy();
    expect(screen.getByText('60%')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '打开画布' }));

    expect(openApp).toHaveBeenCalledWith('canvas', {
      canvasMode: {
        conversationId: 'conversation-1',
        projectId: 'project-1',
        parentOrigin: window.location.origin,
        canvasId: 'canvas-1',
      },
    });
  });

  it('shows a failure without opening a canvas before the user clicks', () => {
    const openApp = vi.fn();
    render(
      <ChatAppsProvider open={openApp}>
        <CanvasRunCard
          conversationId="conversation-1"
          metadata={{
            kind: 'canvas-run',
            canvasRunId: 'run-2',
            canvasId: 'canvas-2',
            status: 'failed',
            error: '端口类型不兼容',
          }}
        />
      </ChatAppsProvider>,
    );

    expect(screen.getByText('端口类型不兼容')).toBeTruthy();
    expect(screen.getByRole('button', { name: '打开画布' })).toBeTruthy();
    expect(openApp).not.toHaveBeenCalled();
  });
});
