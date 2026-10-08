import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bootstrapJobAuth, clearToken, setJobToken, tokenExpired } from './jobAuth';

describe('jobAuth', () => {
  beforeEach(() => {
    clearToken();
  });

  afterEach(() => {
    clearToken();
    vi.unstubAllGlobals();
  });

  it('single-flights concurrent refresh requests', async () => {
    let resolveResponse: ((value: Response) => void) | null = null;
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => {
      resolveResponse = resolve;
    }));
    vi.stubGlobal('fetch', fetchMock);

    const first = bootstrapJobAuth();
    const second = bootstrapJobAuth();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    resolveResponse?.({
      ok: true,
      status: 200,
      json: async () => ({ data: { accessToken: 'token-1', userId: 'user-1' } }),
    } as Response);

    await expect(first).resolves.toBe('token-1');
    await expect(second).resolves.toBe('token-1');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('refreshes an expired access token instead of reusing it', async () => {
    const expired = jwt({ exp: Math.floor(Date.now() / 1000) - 60 });
    setJobToken(expired, 'user-1');
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      data: { accessToken: jwt({ exp: Math.floor(Date.now() / 1000) + 3600 }), userId: 'user-1' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    expect(tokenExpired()).toBe(true);
    await expect(bootstrapJobAuth()).resolves.not.toBe(expired);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

function jwt(payload: Record<string, number>): string {
  const encode = (value: object) => btoa(JSON.stringify(value))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.signature`;
}
