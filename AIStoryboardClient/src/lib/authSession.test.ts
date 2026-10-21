import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authorizedFetch, clearAuthSession, setAccessToken } from './authSession';

describe('authSession', () => {
  beforeEach(() => {
    localStorage.clear();
    setAccessToken(null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('does not resurrect a legacy localStorage access token', async () => {
    localStorage.setItem('accessToken', 'legacy-token');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: '未登录' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response('ok', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await authorizedFetch('/api/test');

    const headers = fetchMock.mock.calls[1][1]?.headers as Headers;
    expect(headers.has('Authorization')).toBe(false);
    expect(localStorage.getItem('accessToken')).toBeNull();
  });

  it('refreshes before the first protected request when no token is in memory', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: { accessToken: 'fresh-token', userId: 'user-1', displayName: '测试用户' },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response('ok', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await authorizedFetch('/api/agent/tasks/events', {
      headers: { Accept: 'text/event-stream' },
    });

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      expect.stringContaining('/api/auth/refresh'),
      expect.objectContaining({ method: 'POST', credentials: 'include' }),
    );
    const headers = fetchMock.mock.calls[1][1]?.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer fresh-token');
  });

  it('uses a token shared by another tab before hitting refresh', async () => {
    class PeerChannel {
      onmessage: ((event: MessageEvent) => void) | null = null;
      postMessage(data: { type: string; requestId?: string }) {
        if (data.type !== 'token-request' || !data.requestId) return;
        queueMicrotask(() => this.onmessage?.({
          data: { type: 'token-response', requestId: data.requestId, token: 'peer-token' },
        } as MessageEvent));
      }
      close() { /* noop */ }
    }
    vi.stubGlobal('BroadcastChannel', PeerChannel);
    const fetchMock = vi.fn().mockResolvedValue(new Response('ok', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await authorizedFetch('/api/test');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const headers = fetchMock.mock.calls[0][1]?.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer peer-token');
  });

  it('purges the legacy localStorage access token on logout', () => {
    localStorage.setItem('accessToken', 'legacy-token');
    localStorage.setItem('user', '{}');

    clearAuthSession();

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
  });

  it('redirects to /login when a protected request stays 401 after refresh', async () => {
    vi.resetModules();
    const fresh = await import('./authSession');
    fresh.setAccessToken('expired-token');
    window.history.pushState({}, '', '/chat');
    const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/refresh')) {
        return new Response(JSON.stringify({ message: '未登录' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(JSON.stringify({ code: '40101', message: '未授权：缺少 token' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    try {
      const response = await fresh.authorizedFetch('/api/agent/tasks/events');

      expect(response.status).toBe(401);
      // 401 持久化后必须清掉本地会话（token + user），并走 /login 跳转。
      expect(fresh.getAccessToken()).toBeNull();
      expect(localStorage.getItem('user')).toBeNull();
    } finally {
      // 关闭本模块的 BroadcastChannel，避免后续用例从“已登录标签页”拿到陈旧 token。
      fresh.setAccessToken(null);
      window.history.pushState({}, '', '/');
    }
  });

  it('backs off after a failed refresh instead of hammering /api/auth/refresh', async () => {
    // 刷新本身 401（Cookie 过期）时，后续请求不能在冷却期内再打刷新接口：
    // 之前每个轮询请求都会各触发一次 401 刷新，55 秒能打 20 次。
    // 用独立模块实例，避免上一个用例留下的 BroadcastChannel / 冷却状态串场。
    vi.resetModules();
    const fresh = await import('./authSession');
    const fetchMock = vi.fn().mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/refresh')) {
        return new Response(JSON.stringify({ message: '未登录' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response('ok', { status: 200 });
    });
    vi.stubGlobal('fetch', fetchMock);

    await fresh.authorizedFetch('/api/agent/tasks/events');
    await fresh.authorizedFetch('/api/agent/tasks/events');
    await fresh.authorizedFetch('/api/agent/tasks/events');

    const refreshCalls = fetchMock.mock.calls
      .filter((call) => String(call[0]).includes('/api/auth/refresh'));
    expect(refreshCalls).toHaveLength(1);
  });
});
