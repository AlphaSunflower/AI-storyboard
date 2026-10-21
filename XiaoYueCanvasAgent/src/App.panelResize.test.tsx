// @vitest-environment jsdom

import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { clearToken, setJobToken } from './services/jobAuth';

const canvas = {
  schemaVersion: 1 as const,
  id: 'canvas-1',
  projectId: 'project-1',
  title: '默认画布',
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
  CanvasWorkspace: () => <div data-testid="canvas-workspace" />,
}));

vi.mock('./components/MoonChatWindow', () => ({
  // 真实子站是 iframe；这里只关心面板拉伸时的状态类，用占位元素代替
  MoonChatWindow: () => <iframe data-testid="moon-chat-frame" title="Moon 智能体" />,
}));

vi.mock('./components/InspectorPanel', () => ({
  InspectorPanel: () => null,
}));

vi.mock('./canvas/generationRuntime', () => ({
  recoverGenerationJobs: vi.fn(async () => undefined),
}));

vi.mock('./canvas/graphExecutionService', () => ({
  recoverGraphExecutions: vi.fn(),
  syncServerExecutions: vi.fn(async () => undefined),
}));

vi.mock('./services/canvasMedia', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./services/canvasMedia')>();
  return { ...actual, hydrateReferencedCanvasAssets: vi.fn(async () => undefined) };
});

vi.mock('./services/projectStorage', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./services/projectStorage')>();
  return {
    ...actual,
    listProjects: vi.fn(async () => [{
      id: 'project-1',
      title: '默认项目',
      createdAt: 1,
      updatedAt: 2,
      canvasCount: 1,
      nodeCount: 0,
      assetCount: 0,
    }]),
    loadProject: vi.fn(async () => ({
      schemaVersion: 2,
      id: 'project-1',
      title: '默认项目',
      canvases: ['canvas-1'],
      createdAt: 1,
      updatedAt: 2,
    })),
    listCanvases: vi.fn(async () => [{
      id: 'canvas-1',
      projectId: 'project-1',
      title: '默认画布',
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

function validJwt(): string {
  const encode = (value: object) => btoa(JSON.stringify(value))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;
}

describe('App 面板头部与拉伸', () => {
  beforeEach(() => {
    localStorage.clear();
    window.history.replaceState(null, '', '#project=project-1&canvas=canvas-1');
    clearToken();
    setJobToken(validJwt(), 'user-1');
    Object.defineProperty(window, 'innerWidth', { value: 1440, configurable: true, writable: true });
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
    delete (window as unknown as Record<string, unknown>).innerWidth;
  });

  it('拖拽分隔条期间给 app-shell 挂 is-panel-resizing（iframe 让出指针事件），松开后摘掉', async () => {
    render(<App />);
    await waitFor(() => expect(screen.getByTestId('canvas-workspace')).toBeTruthy());

    const shell = document.querySelector<HTMLElement>('.app-shell');
    const handle = document.querySelector<HTMLElement>('.panel-resize-handle');
    expect(shell).toBeTruthy();
    expect(handle).toBeTruthy();
    expect(screen.getByTestId('moon-chat-frame')).toBeTruthy();

    fireEvent.mouseDown(handle!, { clientX: 900 });
    expect(shell!.className).toContain('is-panel-resizing');

    fireEvent.mouseMove(document, { clientX: 860, buttons: 1 });
    fireEvent.mouseUp(document);
    expect(shell!.className).not.toContain('is-panel-resizing');
  });

  it('头部是「Moon 智能体 / 节点属性」两个标签，选中态跟着当前视图走', async () => {
    render(<App />);
    await waitFor(() => expect(screen.getByTestId('canvas-workspace')).toBeTruthy());

    const agentTab = screen.getByRole('tab', { name: 'Moon 智能体' });
    const inspectorTab = screen.getByRole('tab', { name: '节点属性' });
    expect(agentTab.getAttribute('aria-selected')).toBe('true');
    expect(inspectorTab.getAttribute('aria-selected')).toBe('false');

    fireEvent.click(inspectorTab);
    expect(inspectorTab.getAttribute('aria-selected')).toBe('true');
    expect(agentTab.getAttribute('aria-selected')).toBe('false');

    fireEvent.click(agentTab);
    expect(agentTab.getAttribute('aria-selected')).toBe('true');
    expect(inspectorTab.getAttribute('aria-selected')).toBe('false');
  });

  it('内嵌画布子站（没有 Moon 对话视图）只留「节点属性」一栏', async () => {
    clearToken();
    window.history.replaceState(
      null,
      '',
      '/canvas/?embedded=1&canvasMode=1&conversationId=conversation-1&projectId=project-1'
        + `&parentOrigin=${encodeURIComponent(window.location.origin)}`
        + '#project=project-1&canvas=canvas-1',
    );
    render(<App />);

    fireEvent(window, new MessageEvent('message', {
      origin: window.location.origin,
      source: window,
      data: { type: 'ai-storyboard:canvas-auth', accessToken: validJwt() },
    }));

    const tabs = await screen.findAllByRole('tab');
    expect(tabs).toHaveLength(1);
    expect(tabs[0].textContent).toBe('节点属性');
    expect(tabs[0].getAttribute('aria-selected')).toBe('true');
  });

  it('标签页切换用 data-active 表达，两个视图都保持挂载（iframe 不重载）', async () => {
    render(<App />);
    await waitFor(() => expect(screen.getByTestId('canvas-workspace')).toBeTruthy());

    const panes = () => Array.from(document.querySelectorAll<HTMLElement>('.chat-view'));
    expect(panes()).toHaveLength(2);
    expect(panes()[0].dataset.active).toBe('true');
    expect(panes()[1].dataset.active).toBe('false');

    const frame = screen.getByTestId('moon-chat-frame');
    fireEvent.click(screen.getByRole('tab', { name: '节点属性' }));

    expect(panes()[0].dataset.active).toBe('false');
    expect(panes()[1].dataset.active).toBe('true');
    // 视图不卸载：Moon 对话还是同一个 iframe 节点，切回来不会重载
    expect(screen.getByTestId('moon-chat-frame')).toBe(frame);
  });

  it('拖拽把手与「打开面板」浮标都有进出场状态，收起后浮标留下、把手卸载', async () => {
    render(<App />);
    await waitFor(() => expect(screen.getByTestId('canvas-workspace')).toBeTruthy());

    const handle = () => document.querySelector<HTMLElement>('.panel-resize-handle');
    const fab = () => document.querySelector<HTMLElement>('.reopen-chat');

    await waitFor(() => expect(handle()?.dataset.present).toBe('true'));
    expect(handle()?.className).toContain('motion-presence');
    expect(fab()).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '收起面板' }));

    // 浮标进场（挂上就是在场态，进场由 CSS keyframe 负责）；把手等退场结束才卸载
    await waitFor(() => expect(fab()).toBeTruthy());
    expect(fab()?.dataset.present).toBe('true');
    await waitFor(() => expect(handle()).toBeNull());

    fireEvent.click(fab()!);
    await waitFor(() => expect(handle()).toBeTruthy());
    await waitFor(() => expect(fab()).toBeNull());
  });
});
