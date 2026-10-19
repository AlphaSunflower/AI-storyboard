import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

const record = {
  id: 'record-redraw',
  producerNodeId: 'producer',
  actionId: 'generateImage',
  attempt: 1,
  jobId: 'job-redraw',
  status: 'running',
  progress: 0.42,
  queueStatus: 'running',
  materializationStatus: 'pending',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [{ nodeId: 'source', assetId: 'source-asset', role: 'REFERENCE', order: 0 }],
  prompt: '把猫变成蓝色',
  effectivePrompt: '把猫变成蓝色',
  params: { capabilityId: 'image.redraw', model: 'gpt-image-2', size: '1024x1024' },
  model: 'gpt-image-2',
  startedAt: Date.now() - 5_000,
};

test('keeps active work in the queue and exposes provenance in generation records', async ({ page }) => {
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
    localStorage.setItem('morrow_job_token', `header.${payload}.signature`);
  });

  // 画布启动依赖 /api/auth/refresh 换 token；这里自闭环，避免依赖本机 ApiGateway。
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const path = url.pathname.replace('/api/canvas', '');
    const json = (body: unknown) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });

    if (path === '/projects' && method === 'GET') return json([{ id: 'project-1', title: '生成记录验收' }]);
    if (path === '/projects/project-1' && method === 'GET') return json({ id: 'project-1', title: '生成记录验收' });
    if (path === '/projects/project-1/canvases' && method === 'GET') {
      return json([{ id: 'canvas-1', projectId: 'project-1', title: '画布 1' }]);
    }
    if (path === '/canvases/canvas-1/graph' && method === 'GET') {
      return json({
        graph: {
          schemaVersion: 2,
          nodes: [
            { id: 'source', type: 'image', position: { x: 80, y: 140 }, width: 230, height: 190, data: { kind: 'image', label: '参考图片', prompt: '', status: 'success', accent: '#ff775f', assetId: 'source-asset' } },
            { id: 'producer', type: 'imageToImage', position: { x: 440, y: 140 }, width: 520, height: 420, data: { kind: 'imageToImage', label: '图生图节点', prompt: '把猫变成蓝色', status: 'running', accent: '#b48cff', isGenerating: true, progressPercent: 42 } },
          ],
          edges: [{ id: 'edge-1', source: 'source', sourceHandle: 'image', target: 'producer', targetHandle: 'source', type: 'canvas' }],
          viewport: { x: 0, y: 0, zoom: 1 },
          generationRecords: [record],
        },
        revision: 1,
      });
    }
    // 恢复流程会用 GET /jobs/{id} 校准未完成任务：这里保持 running，避免被判为中断
    if (/^\/jobs\/[^/]+$/.test(path) && method === 'GET') return json({ status: 'running', progress: 0.42 });
    // 事件流保持空流（不发终态），未完成任务才有资格留在任务队列
    if (/^\/jobs\/[^/]+\/events$/.test(path)) {
      return route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body: ': keep-alive\n\n' });
    }
    if (method === 'PUT') return json({ graph: JSON.parse(route.request().postData() ?? '{}').graph, revision: 2 });
    if (method === 'PATCH' || method === 'DELETE') return json({ ok: true });
    return json({ code: 40401, message: `not mocked: ${path}` });
  });

  // 运行中的任务必然带一条本地队列记录：不 seed 会被恢复流程判定为「执行中断」
  await page.goto('/');
  await page.evaluate(async ({ now }) => {
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('legacyProjects')) db.createObjectStore('legacyProjects', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('generationQueue')) {
        const store = db.createObjectStore('generationQueue', { keyPath: 'id' });
        store.createIndex('by-projectId', 'projectId');
        store.createIndex('by-canvasId', 'canvasId');
        store.createIndex('by-project-canvas', ['projectId', 'canvasId']);
        store.createIndex('by-status-updatedAt', ['status', 'updatedAt']);
        store.createIndex('by-updatedAt', 'updatedAt');
      }
    };
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    if (!db.objectStoreNames.contains('generationQueue')) {
      db.close();
      return;
    }
    const transaction = db.transaction(['generationQueue'], 'readwrite');
    transaction.objectStore('generationQueue').put({
      id: 'queue-redraw',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      producerNodeId: 'producer',
      generationRecordId: 'record-redraw',
      requestSnapshot: { capabilityId: 'image.redraw', sourceAssetIds: ['source-asset'], params: {} },
      status: 'running',
      jobId: 'job-redraw',
      attempt: 1,
      createdAt: now - 5_000,
      updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { now: Date.now() });

  await page.goto('/#project=project-1&canvas=canvas-1');
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('生成记录验收');

  await page.getByRole('button', { name: '任务队列' }).click();
  await expect(page.getByText('图生图节点 · 图片重绘')).toBeVisible();
  await expect(page.getByText('生成中', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '关闭任务队列' }).click();

  await page.getByRole('button', { name: '生成记录' }).click();
  await expect(page.getByRole('heading', { name: '生成记录' })).toBeVisible();
  // 入口在右上角（与任务队列并排）：抽屉必须挂在入口正下方、右边缘对齐，不能跑到画布左边
  const anchorBox = await page.locator('.canvas-quick-actions').boundingBox();
  const desktopPanelBox = await page.locator('.generation-records-panel').boundingBox();
  expect(anchorBox).not.toBeNull();
  expect(desktopPanelBox).not.toBeNull();
  expect(Math.abs((desktopPanelBox!.x + desktopPanelBox!.width) - (anchorBox!.x + anchorBox!.width)))
    .toBeLessThanOrEqual(8);
  // 抽屉挂在入口正下方：留 2px 容差，避免边框/亚像素导致的 1px 抖动。
  expect(desktopPanelBox!.y).toBeGreaterThanOrEqual(anchorBox!.y + anchorBox!.height - 2);
  expect(desktopPanelBox!.x).toBeGreaterThan(anchorBox!.x - desktopPanelBox!.width);
  await page.getByRole('button', { name: /生成图片/ }).click();
  const detailDialog = page.getByRole('dialog', { name: /生成记录详情/ });
  await expect(detailDialog).toBeVisible();
  await expect(detailDialog.getByText('生成链路')).toBeVisible();
  await expect(detailDialog.getByText('来源素材 1')).toBeVisible();
  await expect(detailDialog.getByText('由「图生图节点」提交', { exact: false })).toBeVisible();
  await expect(page.locator('.generation-record-row__head > strong')).toHaveCSS('color', 'rgb(244, 244, 245)');
  // 详情走弹窗：抽屉仍是列表宽度，不再为详情让出一半宽度
  const drawerWithDetail = await page.locator('.generation-records-panel').boundingBox();
  expect(drawerWithDetail).not.toBeNull();
  expect(drawerWithDetail!.width).toBeLessThanOrEqual(470);

  await page.setViewportSize({ width: 390, height: 844 });
  const panelBox = await page.locator('.generation-records-panel').boundingBox();
  expect(panelBox).not.toBeNull();
  expect(panelBox!.x).toBeGreaterThanOrEqual(0);
  expect(panelBox!.y).toBeGreaterThanOrEqual(0);
  expect(panelBox!.x + panelBox!.width).toBeLessThanOrEqual(391);
  expect(panelBox!.y + panelBox!.height).toBeLessThanOrEqual(845);

  const dialogBox = await detailDialog.boundingBox();
  expect(dialogBox).not.toBeNull();
  expect(dialogBox!.x).toBeGreaterThanOrEqual(0);
  expect(dialogBox!.y).toBeGreaterThanOrEqual(0);
  expect(dialogBox!.x + dialogBox!.width).toBeLessThanOrEqual(391);
  expect(dialogBox!.y + dialogBox!.height).toBeLessThanOrEqual(845);

  await detailDialog.getByRole('button', { name: '关闭生成记录详情' }).click();
  await expect(detailDialog).toBeHidden();
  await expect(page.getByRole('button', { name: /生成图片/ })).toBeVisible();
});
