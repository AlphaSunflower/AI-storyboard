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

  it('purges the legacy localStorage access token on logout', () => {
    localStorage.setItem('accessToken', 'legacy-token');
    localStorage.setItem('user', '{}');

    clearAuthSession();

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
  });
});
