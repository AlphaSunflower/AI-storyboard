// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { clearToken, getJobToken, setJobToken } from './services/jobAuth';

vi.mock('./services/projectStorage', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./services/projectStorage')>();
  return {
    ...actual,
    listProjects: vi.fn(async () => getJobToken() ? [{
      id: 'recent-project',
      title: '最近项目 A',
      createdAt: 1,
      updatedAt: 2,
      canvasCount: 1,
      nodeCount: 0,
      assetCount: 0,
    }] : []),
  };
});

describe('App authentication flow', () => {
  beforeEach(() => {
    localStorage.clear();
    clearToken();
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })));
    const baseFetch = globalThis.fetch;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/api/auth/login')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({ data: { accessToken: validJwt('login'), userId: 'user-1' } }),
        } as Response;
      }
      if (String(input).includes('/api/auth/refresh')) {
        return { ok: false, status: 401, json: async () => ({}) } as Response;
      }
      return baseFetch(input, init);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('loads recent projects immediately after login without a page refresh', async () => {
    render(<App />);

    fireEvent.change(screen.getByPlaceholderText('you@example.com'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('••••••'), { target: { value: 'secret123' } });
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByText('最近项目 A')).toBeTruthy();
  });

  it('ignores a late 401 from an old token after a new login', async () => {
    const oldToken = validJwt('old');
    const newToken = validJwt('new');
    setJobToken(oldToken);
    render(<App />);
    await waitFor(() => expect(screen.getByText('最近项目 A')).toBeTruthy());

    setJobToken(newToken);
    fireEvent(window, new CustomEvent('morrow:auth-rejected', { detail: { status: 401, token: oldToken } }));
    expect(screen.getByText('最近项目 A')).toBeTruthy();

    fireEvent(window, new CustomEvent('morrow:auth-rejected', { detail: { status: 401, token: newToken } }));
    expect(await screen.findByPlaceholderText('you@example.com')).toBeTruthy();
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
