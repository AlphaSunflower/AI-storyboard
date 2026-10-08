import type { AxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import client from './client';
import { clearAuthSession, setAccessToken } from '../lib/authSession';

describe('api client auth bootstrap', () => {
  beforeEach(() => {
    localStorage.clear();
    setAccessToken(null);
  });

  afterEach(() => {
    clearAuthSession();
    vi.unstubAllGlobals();
  });

  it('refreshes before sending a protected request when no token is in memory', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      data: { accessToken: 'fresh-token', userId: 'user-1', displayName: '测试用户' },
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    let authorization: string | undefined;
    const adapter: AxiosRequestConfig['adapter'] = async (config) => {
      authorization = config.headers.get('Authorization')?.toString();
      return {
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      };
    };

    await client.get('/projects', { adapter });

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/auth/refresh'),
      expect.objectContaining({ method: 'POST', credentials: 'include' }),
    );
    expect(authorization).toBe('Bearer fresh-token');
  });
});
