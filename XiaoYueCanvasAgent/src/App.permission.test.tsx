// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { clearToken, setJobToken } from './services/jobAuth';
import { saveCanvas } from './services/projectStorage';

const canvas = {
  schemaVersion: 1 as const,
  id: 'canvas-1',
  projectId: 'project-1',
  title: '旧账号画布',
  nodes: [],
  edges: [],
  viewport: { x: 0, y: 0, zoom: 1 },
  generationRecords: [],
  executions: [],
  revision: 1,
  createdAt: 1,
  updatedAt: 2,
};

vi.mock('./canvas/bridgeClient', () => ({
  startCanvasBridge: vi.fn(() => () => undefined),
}));

vi.mock('./components/CanvasWorkspace', () => ({
  CanvasWorkspace: (props: { onOpenHome: () => void }) => (
    <button data-testid="canvas-workspace" onClick={props.onOpenHome}>返回项目中心</button>
  ),
}));

vi.mock('./components/MoonChatWindow', () => ({
  MoonChatWindow: () => null,
}));

vi.mock('./components/InspectorPanel', () => ({
  InspectorPanel: () => null,
}));

vi.mock('./canvas/generationRuntime', () => ({
  recoverGenerationJobs: vi.fn(async () => undefined),
}));

vi.mock('./canvas/graphExecutionService', () => ({
  recoverGraphExecutions: vi.fn(),
}));

vi.mock('./services/canvasMedia', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./services/canvasMedia')>();
  return {
    ...actual,
    hydrateReferencedCanvasAssets: vi.fn(async () => undefined),
  };
});

vi.mock('./services/projectStorage', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./services/projectStorage')>();
  return {
    ...actual,
    listProjects: vi.fn(async () => [{
      id: 'project-1',
      title: '旧项目',
      createdAt: 1,
      updatedAt: 2,
      canvasCount: 1,
      nodeCount: 0,
      assetCount: 0,
    }]),
    loadProject: vi.fn(async () => ({
      schemaVersion: 2,
      id: 'project-1',
      title: '旧项目',
      canvases: ['canvas-1'],
      createdAt: 1,
      updatedAt: 2,
    })),
    listCanvases: vi.fn(async () => [{
      id: 'canvas-1',
      projectId: 'project-1',
      title: '旧账号画布',
      createdAt: 1,
      updatedAt: 2,
      nodeCount: 0,
      assetCount: 0,
    }]),
    loadCanvas: vi.fn(async () => canvas),
    getAssetMetadatas: vi.fn(async () => []),
    saveCanvas: vi.fn(async (value) => value),
  };
});

describe('App canvas permission handling', () => {
  beforeEach(() => {
    localStorage.clear();
    window.history.replaceState(null, '', '#project=project-1&canvas=canvas-1');
    clearToken();
    setJobToken(validJwt(), 'user-1');
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })));
  });

  afterEach(() => {
    cleanup();
    clearToken();
    window.history.replaceState(null, '', '/');
    vi.unstubAllGlobals();
  });

  it('leaves a stale canvas when the backend denies its ownership', async () => {
    render(<App />);

    await waitFor(() => expect(screen.getByTestId('canvas-workspace')).toBeTruthy());

    fireEvent(window, new CustomEvent('morrow:permission-denied', {
      detail: { status: 403, path: '/canvases/canvas-1/graph' },
    }));

    expect(await screen.findByRole('button', { name: /新建画布/ })).toBeTruthy();
    expect(window.location.hash).toBe('#project=project-1');
  });

  it('returns to project home without waiting for a slow canvas save', async () => {
    vi.mocked(saveCanvas).mockImplementationOnce(() => new Promise(() => undefined));
    render(<App />);

    fireEvent.click(await screen.findByTestId('canvas-workspace'));

    expect(await screen.findByRole('button', { name: /新建画布/ })).toBeTruthy();
    expect(window.location.hash).toBe('#project=project-1');
  });

  it('accepts the authenticated identity sent by the parent app', async () => {
    clearToken();
    window.history.replaceState(
      null,
      '',
      '/canvas/?embedded=1&canvasMode=1&conversationId=conversation-1&projectId=project-1'
        + `&parentOrigin=${encodeURIComponent(window.location.origin)}`,
    );
    render(<App />);

    expect(await screen.findByPlaceholderText('you@example.com')).toBeTruthy();

    fireEvent(window, new MessageEvent('message', {
      origin: window.location.origin,
      source: window,
      data: {
        type: 'ai-storyboard:canvas-auth',
        accessToken: validJwt(),
      },
    }));

    expect(await screen.findByText('旧项目')).toBeTruthy();

    fireEvent(window, new CustomEvent('morrow:auth-rejected', {
      detail: { status: 401, token: null },
    }));

    expect(screen.getByText('旧项目')).toBeTruthy();
  });
});

function validJwt(): string {
  const encode = (value: object) => btoa(JSON.stringify(value))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;
}
