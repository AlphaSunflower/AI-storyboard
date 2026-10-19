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
});
