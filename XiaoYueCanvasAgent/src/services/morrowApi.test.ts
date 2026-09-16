// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiJson } from './morrowApi';

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  localStorage.clear();
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
});
