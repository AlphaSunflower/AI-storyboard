import { expect, test } from '@playwright/test';
import { mockApiFallback, mockCanvasAuth, postCanvasAuth } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

test('reports a controlled canvas only after it is bridge-ready', async ({ page }) => {
  const projectId = 'embed-project';
  const canvasId = 'embed-canvas';

  // 内嵌 iframe 与宿主同源，同样会走 /api/auth/refresh 续期；不 mock 会打到宿主网关并 401 登出
  await mockCanvasAuth(page);
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '嵌入测试项目' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: canvasId, projectId, title: '嵌入测试画布' }] });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: { graph, revision: 1 } });
    }
    return route.fulfill({ json: {} });
  });
  // 画布命令信箱已并入 canvas 服务，走同源 /api/canvas/bridge/**
  await page.route('**/api/canvas/bridge/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/canvas/bridge', '');
    if (path === '/health') return route.fulfill({ json: { ok: true, version: 1, projects: [] } });
    if (path === '/poll') return route.fulfill({ json: {} });
    return route.fulfill({ json: { ok: true } });
  });

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    localStorage.setItem('morrow_job_token', 'embed-token');
    localStorage.setItem('morrow_job_user', 'embed-user');
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('legacyProjects')) db.createObjectStore('legacyProjects', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('libraryEntries')) db.createObjectStore('libraryEntries', { keyPath: 'assetId' });
      if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
      if (!db.objectStoreNames.contains('assetMeta')) db.createObjectStore('assetMeta', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assetVariants')) db.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
      if (!db.objectStoreNames.contains('generationQueue')) db.createObjectStore('generationQueue', { keyPath: 'id' });
    };
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const now = Date.now();
    const transaction = db.transaction(['legacyProjects', 'canvases'], 'readwrite');
    transaction.objectStore('legacyProjects').put({
      schemaVersion: 2,
      id: projectId,
      title: '嵌入测试项目',
      canvases: [canvasId],
      createdAt: now,
      updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1,
      id: canvasId,
      projectId,
      title: '嵌入测试画布',
      nodes: [],
      edges: [],
      viewport: { x: 40, y: 40, zoom: 1 },
      createdAt: now,
      updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();

    const messages: unknown[] = [];
    (window as typeof window & { __canvasModeMessages?: unknown[] }).__canvasModeMessages = messages;
    window.addEventListener('message', (event) => {
      if (event.origin === window.location.origin) messages.push(event.data);
    });
    const iframe = document.createElement('iframe');
    (window as typeof window & { __canvasModeFrame?: HTMLIFrameElement }).__canvasModeFrame = iframe;
    const params = new URLSearchParams({
      token: 'embed-token',
      embedded: '1',
      canvasMode: '1',
      conversationId: 'conversation-embed',
      projectId,
      parentOrigin: window.location.origin,
    });
    iframe.src = `/?${params.toString()}#project=${projectId}&canvas=${canvasId}`;
    document.body.appendChild(iframe);
  }, { projectId, canvasId });

  // 内嵌画布的登录态由宿主页面注入（等价 EmbeddedAppWindow 的行为）
  await postCanvasAuth(page);

  await expect.poll(async () => page.evaluate(() =>
    (window as typeof window & { __canvasModeMessages?: unknown[] }).__canvasModeMessages ?? [],
  )).toEqual(expect.arrayContaining([
    expect.objectContaining({
      type: 'ai-storyboard:canvas-mode-ready',
      conversationId: 'conversation-embed',
      projectId,
      canvasId,
      bridgeReady: true,
    }),
  ]));

  await page.evaluate(({ projectId }) => {
    const frame = (window as typeof window & { __canvasModeFrame?: HTMLIFrameElement }).__canvasModeFrame;
    frame?.contentWindow?.postMessage({
      type: 'ai-storyboard:canvas-mode-control',
      conversationId: 'conversation-embed',
      projectId,
      locked: true,
    }, window.location.origin);
  }, { projectId });
  await expect(page.frameLocator('iframe').getByRole('button', { name: '切换画布' })).toHaveCount(0);

  await page.evaluate(({ projectId }) => {
    const frame = (window as typeof window & { __canvasModeFrame?: HTMLIFrameElement }).__canvasModeFrame;
    frame?.contentWindow?.postMessage({
      type: 'ai-storyboard:canvas-mode-control',
      conversationId: 'conversation-embed',
      projectId,
      locked: false,
    }, window.location.origin);
  }, { projectId });
  await expect(page.frameLocator('iframe').getByRole('button', { name: '切换画布' })).toBeVisible();
});
