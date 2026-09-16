import { expect, test } from '@playwright/test';

test('reports a controlled canvas only after it is bridge-ready', async ({ page }) => {
  const projectId = 'embed-project';
  const canvasId = 'embed-canvas';

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
  await page.route('http://127.0.0.1:8787/health', (route) => route.fulfill({
    json: { ok: true, version: 1, projects: [] },
  }));
  await page.route('http://127.0.0.1:8787/register', (route) => route.fulfill({ json: { ok: true } }));
  await page.route('http://127.0.0.1:8787/poll**', (route) => route.fulfill({ json: {} }));
  await page.route('http://127.0.0.1:8787/unregister', (route) => route.fulfill({ json: { ok: true } }));

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    localStorage.setItem('morrow_job_token', 'embed-token');
    localStorage.setItem('morrow_job_user', 'embed-user');
    const request = indexedDB.open('morrow-creative-canvas-v1', 4);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
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
    const transaction = db.transaction(['projects', 'canvases'], 'readwrite');
    transaction.objectStore('projects').put({
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
