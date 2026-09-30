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
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 } as Response);
    vi.stubGlobal('fetch', fetchMock);

    await authorizedFetch('/api/test');

    const headers = fetchMock.mock.calls[0][1]?.headers as Headers;
    expect(headers.has('Authorization')).toBe(false);
    expect(localStorage.getItem('accessToken')).toBeNull();
  });

  it('purges the legacy localStorage access token on logout', () => {
    localStorage.setItem('accessToken', 'legacy-token');
    localStorage.setItem('user', '{}');

    clearAuthSession();

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
  });
});
