import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { bootstrapJobAuth, clearToken } from './jobAuth';

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
});
