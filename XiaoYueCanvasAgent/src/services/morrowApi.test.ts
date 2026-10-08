// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiJson } from './morrowApi';
import { clearToken, setJobToken } from './jobAuth';

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  localStorage.clear();
  clearToken();
});

describe('morrowApi auth classification', () => {
  it('does not clear the session for business 403 responses', async () => {
    const authRejected = vi.fn();
    const permissionDenied = vi.fn();
    window.addEventListener('morrow:auth-rejected', authRejected);
    window.addEventListener('morrow:permission-denied', permissionDenied);
    globalThis.fetch = vi.fn(async () => new Response(
      JSON.stringify({ code: 40301, message: '至少保留一个项目' }),
      { status: 403, headers: { 'Content-Type': 'application/json' } },
    ));

    await expect(apiJson('/projects/project-1', { method: 'DELETE' })).rejects.toThrow('至少保留一个项目');
    expect(authRejected).not.toHaveBeenCalled();
    expect(permissionDenied).toHaveBeenCalledTimes(1);
  });

  it('clears the session only for 401 responses', async () => {
    const authRejected = vi.fn();
    window.addEventListener('morrow:auth-rejected', authRejected);
    globalThis.fetch = vi.fn(async () => new Response(
      JSON.stringify({ code: 40101, message: '未授权' }),
      { status: 401, headers: { 'Content-Type': 'application/json' } },
    ));

    await expect(apiJson('/projects')).rejects.toThrow('未授权');
    expect(authRejected).toHaveBeenCalledTimes(1);
  });

  it('refreshes and retries once after a 401 response', async () => {
    setJobToken('expired-token', 'user-1');
    const fresh = `${btoa('{}')}.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))}.sig`;
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 40101, message: '未授权' }), {
        status: 401, headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { accessToken: fresh, userId: 'user-1' } }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }));
    globalThis.fetch = fetchMock;

    await expect(apiJson<{ ok: boolean }>('/projects')).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('retries with a token that arrived while an anonymous request was in flight', async () => {
    let resolveProtected!: (response: Response) => void;
    const protectedResponse = new Promise<Response>((resolve) => {
      resolveProtected = resolve;
    });
    const freshToken = validJwt('fresh');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 40101, message: '未授权' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }))
      .mockImplementationOnce(() => protectedResponse)
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }));
    globalThis.fetch = fetchMock;

    const pending = apiJson<{ ok: boolean }>('/projects');
    await Promise.resolve();
    await Promise.resolve();
    setJobToken(freshToken, 'user-1');
    resolveProtected(new Response(JSON.stringify({ code: 40101, message: '未授权' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    }));

    await expect(pending).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const retryHeaders = fetchMock.mock.calls[2]?.[1]?.headers as Headers;
    expect(retryHeaders.get('Authorization')).toBe(`Bearer ${freshToken}`);
  });
});

function validJwt(jti: string): string {
  const encode = (value: object) => btoa(JSON.stringify(value))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    exp: Math.floor(Date.now() / 1000) + 3600,
    jti,
  })}.signature`;
}
