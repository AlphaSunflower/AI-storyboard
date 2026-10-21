// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const apiFetch = vi.fn();
const executeToolCall = vi.fn();

vi.mock('../services/morrowApi', () => ({
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}));
vi.mock('./toolRegistry', () => ({
  executeToolCall: (...args: unknown[]) => executeToolCall(...args),
}));

const jsonResponse = (body: unknown) => ({
  ok: true,
  status: 200,
  json: async () => body,
}) as unknown as Response;

describe('startCanvasBridge', () => {
  let stop: (() => void) | null = null;

  beforeEach(() => {
    stop = null;
    apiFetch.mockReset();
    executeToolCall.mockReset();
  });

  afterEach(() => {
    stop?.();
  });

  it('轮询契约挂在 /api/canvas 前缀下的 /bridge/*（不能重复前缀）', async () => {
    let polled = 0;
    apiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/bridge/health')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/register')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/poll')) {
        polled += 1;
        return jsonResponse(polled === 1
          ? { command: { requestId: 'req-1', method: 'canvas.undo', params: {}, source: 'mcp' } }
          : {});
      }
      if (path.startsWith('/bridge/result')) return jsonResponse({ ok: true });
      return jsonResponse({});
    });
    executeToolCall.mockResolvedValue({ ok: true, result: { undone: true }, status: 'applied' });

    const { startCanvasBridge } = await import('./bridgeClient');
    stop = startCanvasBridge('project-1', 'canvas-1', undefined, { projectTitle: 'P', canvasTitle: 'C' });

    await vi.waitFor(() => {
      expect(apiFetch.mock.calls.map((call) => String(call[0]))).toContainEqual(
        expect.stringMatching(/^\/bridge\/result$/));
    }, { timeout: 3000 });

    const paths = apiFetch.mock.calls.map((call) => String(call[0]));
    expect(paths[0]).toBe('/bridge/health');
    expect(paths).toContainEqual('/bridge/register');
    expect(paths).toContainEqual(expect.stringMatching(/^\/bridge\/poll\?tabId=/));
    for (const path of paths) {
      expect(path.startsWith('/bridge/')).toBe(true);
      expect(path.startsWith('/api/canvas')).toBe(false);
    }

    const resultCall = apiFetch.mock.calls.find((call) => String(call[0]) === '/bridge/result');
    const body = JSON.parse(String((resultCall?.[1] as RequestInit).body));
    expect(body.requestId).toBe('req-1');
    expect(body.response).toMatchObject({ ok: true, id: 'req-1', status: 'applied' });

    stop();
    stop = null;
    await vi.waitFor(() => {
      expect(apiFetch.mock.calls.map((call) => String(call[0]))).toContainEqual('/bridge/unregister');
    }, { timeout: 3000 });
  });

  it('本地图落后（NODE_NOT_FOUND）时先对齐服务端画布、再重试一次同一条命令', async () => {
    let polls = 0;
    apiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/bridge/health')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/register')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/poll')) {
        polls += 1;
        return jsonResponse(polls === 1
          ? { command: { requestId: 'req-stale', method: 'canvas.group.create', params: { nodeIds: ['n1', 'n2'] }, source: 'mcp' } }
          : {});
      }
      if (path.startsWith('/bridge/result')) return jsonResponse({ ok: true });
      return jsonResponse({});
    });
    executeToolCall
      .mockResolvedValueOnce({ ok: false, error: { code: 'NODE_NOT_FOUND', message: '节点不存在：n1、n2' } })
      .mockResolvedValueOnce({ ok: true, status: 'applied', result: { groupId: 'group-1' } });

    // 宿主 App 收到 resync 请求 → 整图重载 → 回 resynced
    let resyncRequests = 0;
    const onResync = () => {
      resyncRequests += 1;
      window.dispatchEvent(new CustomEvent('morrow:canvas-resynced', { detail: {} }));
    };
    window.addEventListener('morrow:canvas-resync', onResync);

    try {
      const { startCanvasBridge } = await import('./bridgeClient');
      stop = startCanvasBridge('project-1', 'canvas-1');

      await vi.waitFor(() => expect(executeToolCall).toHaveBeenCalledTimes(2), { timeout: 5000 });
      expect(resyncRequests).toBe(1);
      // 重试用的是同一条命令
      expect(executeToolCall.mock.calls[1][1]).toMatchObject({ name: 'canvas.group.create' });

      const resultCall = apiFetch.mock.calls.find((call) => String(call[0]) === '/bridge/result');
      const body = JSON.parse(String((resultCall?.[1] as RequestInit).body));
      expect(body.response).toMatchObject({ ok: true, status: 'applied', result: { groupId: 'group-1' } });
    } finally {
      window.removeEventListener('morrow:canvas-resync', onResync);
    }
  }, 10_000);

  it('轮询遇到 401/403 时不清场、不中断，服务恢复后继续领取命令', async () => {
    let polls = 0;
    apiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/bridge/health')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/register')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/poll')) {
        polls += 1;
        if (polls === 1) return { ok: false, status: 401, json: async () => ({}) } as unknown as Response;
        if (polls === 2) return { ok: false, status: 403, json: async () => ({}) } as unknown as Response;
        if (polls === 3) {
          return jsonResponse({ command: { requestId: 'req-late', method: 'canvas.undo', params: {}, source: 'mcp' } });
        }
        return jsonResponse({});
      }
      if (path.startsWith('/bridge/result')) return jsonResponse({ ok: true });
      return jsonResponse({});
    });
    executeToolCall.mockResolvedValue({ ok: true, status: 'applied' });

    const { startCanvasBridge } = await import('./bridgeClient');
    stop = startCanvasBridge('project-1', 'canvas-1');

    await vi.waitFor(() => {
      expect(executeToolCall).toHaveBeenCalledTimes(1);
    }, { timeout: 6000 });
    expect(executeToolCall.mock.calls[0][1]).toMatchObject({ name: 'canvas.undo' });

    const paths = apiFetch.mock.calls.map((call) => String(call[0]));
    expect(paths.filter((path) => path === '/bridge/register')).toHaveLength(1);
    expect(paths).not.toContainEqual('/bridge/unregister');
  }, 10_000);

  it('轮询中断后按重试节奏自愈并重新注册', async () => {
    let polls = 0;
    apiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/bridge/health')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/register')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/poll')) {
        polls += 1;
        if (polls === 1) throw new Error('network down');
        return jsonResponse({});
      }
      return jsonResponse({});
    });

    const { startCanvasBridge } = await import('./bridgeClient');
    stop = startCanvasBridge('project-1', 'canvas-1');

    await vi.waitFor(() => {
      const registers = apiFetch.mock.calls.filter((call) => String(call[0]) === '/bridge/register');
      expect(registers.length).toBeGreaterThanOrEqual(2);
    }, { timeout: 8000 });
  }, 12_000);

  it('切换画布时旧标签注销、新标签用新 canvasId 注册', async () => {
    apiFetch.mockImplementation(async (path: string) => {
      if (path.startsWith('/bridge/health')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/register')) return jsonResponse({ ok: true });
      if (path.startsWith('/bridge/poll')) return jsonResponse({});
      if (path.startsWith('/bridge/unregister')) return jsonResponse({ ok: true });
      return jsonResponse({});
    });

    const registerBodies = () => apiFetch.mock.calls
      .filter((call) => String(call[0]) === '/bridge/register')
      .map((call) => JSON.parse(String((call[1] as RequestInit).body)) as { tabId: string; canvasId: string });

    const { startCanvasBridge } = await import('./bridgeClient');
    const stopFirst = startCanvasBridge('project-1', 'canvas-a');
    await vi.waitFor(() => { expect(registerBodies()).toHaveLength(1); }, { timeout: 3000 });
    stopFirst();
    await vi.waitFor(() => {
      expect(apiFetch.mock.calls.map((call) => String(call[0]))).toContainEqual('/bridge/unregister');
    }, { timeout: 3000 });

    stop = startCanvasBridge('project-1', 'canvas-b');
    await vi.waitFor(() => { expect(registerBodies()).toHaveLength(2); }, { timeout: 3000 });

    const [first, second] = registerBodies();
    expect(first.canvasId).toBe('canvas-a');
    expect(second.canvasId).toBe('canvas-b');
    expect(second.tabId).not.toBe(first.tabId);
  });
});
