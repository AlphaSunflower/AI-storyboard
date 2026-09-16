import { describe, expect, it, vi } from 'vitest';
import { CanvasBridgeCore } from '../../bridge/canvasBridgeCore';
import { BRIDGE_TAB_STALE_MS } from './bridgeProtocol';

describe('CanvasBridgeCore', () => {
  it('routes a command to the exact project and canvas tab', async () => {
    const core = new CanvasBridgeCore();
    core.register({
      tabId: 'tab-1',
      projectId: 'project-1',
      projectTitle: '项目一',
      canvasId: 'canvas-1',
      canvasTitle: '分镜画布',
    });

    expect(core.health().projects).toContainEqual(expect.objectContaining({
      projectId: 'project-1',
      projectTitle: '项目一',
      canvasId: 'canvas-1',
      canvasTitle: '分镜画布',
    }));

    const command = core.command({
      source: 'mcp',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      method: 'canvas.get_summary',
      params: {},
    });
    const polled = core.poll('tab-1');
    expect(polled.command?.method).toBe('canvas.get_summary');
    core.result({
      requestId: polled.command!.requestId,
      response: {
        version: 1,
        id: polled.command!.requestId,
        ok: true,
        status: 'applied',
        result: { nodeCount: 2 },
      },
    });
    await expect(command).resolves.toMatchObject({ ok: true, result: { nodeCount: 2 } });
  });

  it('rejects ambiguous project routing when multiple canvases are open', async () => {
    const core = new CanvasBridgeCore();
    core.register({ tabId: 'tab-1', projectId: 'project-1', canvasId: 'canvas-1' });
    core.register({ tabId: 'tab-2', projectId: 'project-1', canvasId: 'canvas-2' });

    await expect(core.command({
      source: 'cli',
      projectId: 'project-1',
      method: 'canvas.get_summary',
      params: {},
    })).resolves.toMatchObject({ ok: false, error: { code: 'AMBIGUOUS_CANVAS' } });
  });

  it('promotes the remaining tab when a replaced tab unregisters', async () => {
    const core = new CanvasBridgeCore();
    core.register({ tabId: 'old-tab', projectId: 'project-1', canvasId: 'canvas-1' });
    core.register({ tabId: 'new-tab', projectId: 'project-1', canvasId: 'canvas-1' });
    core.unregister('old-tab');

    const command = core.command({
      source: 'cli',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      method: 'canvas.get_summary',
      params: {},
    });
    const polled = core.poll('new-tab');
    expect(polled.command?.method).toBe('canvas.get_summary');
    const requestId = polled.command?.requestId ?? '';
    core.result({
      requestId,
      response: { version: 1, id: requestId, ok: true, status: 'applied', result: {} },
    });
    await expect(command).resolves.toMatchObject({ ok: true });
  });

  it('does not route to a browser tab that stopped polling', async () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-09-16T08:00:00.000Z'));
      const core = new CanvasBridgeCore();
      core.register({ tabId: 'stale-tab', projectId: 'project-1', canvasId: 'canvas-1' });

      vi.advanceTimersByTime(BRIDGE_TAB_STALE_MS + 1);

      expect(core.health().projects).toEqual([]);
      await expect(core.command({
        source: 'mcp',
        projectId: 'project-1',
        canvasId: 'canvas-1',
        method: 'canvas.get_summary',
        params: {},
      })).resolves.toMatchObject({
        ok: false,
        error: { code: 'CANVAS_NOT_OPEN' },
      });

      core.poll('stale-tab');
      expect(core.health().projects).toContainEqual(expect.objectContaining({
        projectId: 'project-1',
        canvasId: 'canvas-1',
      }));
    } finally {
      vi.useRealTimers();
    }
  });
});
