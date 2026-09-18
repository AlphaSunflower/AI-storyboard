import { expect, test } from '@playwright/test';

test('switches between compact nodes, full nodes, and individual expansion', async ({ page }) => {
  const projectId = 'node-display-project';
  const canvasId = 'node-display-canvas';

  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '节点显示测试' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    }
    if (path === '/models' && request.method() === 'GET') {
      return route.fulfill({ json: [] });
    }
    if (path === '/capabilities' && request.method() === 'GET') {
      return route.fulfill({ json: [] });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: { graph, revision: 1 } });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    const now = Date.now();
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
    const transaction = db.transaction(['projects', 'canvases'], 'readwrite');
    transaction.objectStore('projects').put({
      schemaVersion: 2,
      id: projectId,
      title: '节点显示测试',
      canvases: [canvasId],
      createdAt: now,
      updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1,
      id: canvasId,
      projectId,
      title: '默认画布',
      nodes: [
        {
          id: 'display-text-1',
          type: 'text',
          position: { x: 180, y: 220 },
          width: 380,
          height: 280,
          data: { kind: 'text', label: '文字节点', prompt: '', status: 'idle', accent: '#f4c95d' },
        },
        {
          id: 'display-image-to-image-1',
          type: 'imageToImage',
          position: { x: 720, y: 220 },
          width: 520,
          height: 420,
          data: { kind: 'imageToImage', label: '图生图', prompt: '', status: 'idle', accent: '#b48cff' },
        },
        {
          id: 'display-image-1',
          type: 'image',
          position: { x: 180, y: 620 },
          width: 240,
          height: 240,
          data: { kind: 'image', label: '图片素材', prompt: '', status: 'idle', accent: '#ff775f' },
        },
        {
          id: 'display-video-1',
          type: 'video',
          position: { x: 520, y: 620 },
          width: 420,
          height: 236,
          data: { kind: 'video', label: '视频素材', prompt: '', status: 'idle', accent: '#8f9cff' },
        },
        {
          id: 'display-script-1',
          type: 'script',
          position: { x: 1040, y: 620 },
          width: 420,
          height: 320,
          data: { kind: 'script', label: '剧本素材', prompt: '', status: 'idle', accent: '#a9c9f5', scriptText: '' },
        },
      ],
      edges: [],
      viewport: { x: 40, y: 40, zoom: 0.9 },
      createdAt: now,
      updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId, canvasId });

  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);

  const compactNodes = page.locator('.media-node--compact');
  await expect(compactNodes).toHaveCount(1);
  await expect(page.getByText('图生图', { exact: true })).toBeVisible();
  for (const nodeId of ['display-text-1', 'display-image-1', 'display-video-1', 'display-script-1']) {
    const materialNode = page.locator(`.react-flow__node[data-id="${nodeId}"]`);
    await expect(materialNode.locator('.node-card')).toBeVisible();
    await expect(materialNode.locator('.compact-node-label')).toHaveCount(0);
  }

  const compactImageToImageNode = page.locator('.react-flow__node[data-id="display-image-to-image-1"]');
  const beforeDrag = await compactImageToImageNode.boundingBox();
  expect(beforeDrag).not.toBeNull();
  await page.mouse.move(beforeDrag!.x + beforeDrag!.width / 2, beforeDrag!.y + beforeDrag!.height / 2);
  await page.mouse.down();
  await page.mouse.move(beforeDrag!.x + beforeDrag!.width / 2 + 100, beforeDrag!.y + beforeDrag!.height / 2 + 56, { steps: 8 });
  await page.mouse.up();
  const afterDrag = await compactImageToImageNode.boundingBox();
  expect(afterDrag).not.toBeNull();
  expect(Math.abs(afterDrag!.x - beforeDrag!.x) + Math.abs(afterDrag!.y - beforeDrag!.y)).toBeGreaterThan(80);
  await expect(compactImageToImageNode.locator('.compact-node-label')).toBeVisible();

  await page.getByRole('button', { name: '展开全部节点' }).click();
  await expect(compactNodes).toHaveCount(0);
  await expect(page.locator('.react-flow__node[data-id="display-text-1"] .node-card__body')).toBeVisible();
  await expect(page.locator('.react-flow__node[data-id="display-image-to-image-1"] .image-gen-view')).toBeVisible();

  await page.getByRole('button', { name: '收起全部节点' }).click();
  await expect(compactNodes).toHaveCount(1);

  await compactImageToImageNode.click();
  await expect(compactImageToImageNode.locator('.compact-node-label')).toHaveCount(0);
  await expect(compactImageToImageNode.locator('.image-gen-view')).toBeVisible();

  await compactImageToImageNode.getByRole('button', { name: '最小化节点' }).click();
  await expect(compactImageToImageNode.locator('.compact-node-label')).toBeVisible();
  await expect(page.locator('.media-node--compact')).toHaveCount(1);
});
