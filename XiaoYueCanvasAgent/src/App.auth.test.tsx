// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { getJobToken } from './services/jobAuth';

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
          json: async () => ({ data: { accessToken: 'header.payload.signature', userId: 'user-1' } }),
        } as Response;
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
});
