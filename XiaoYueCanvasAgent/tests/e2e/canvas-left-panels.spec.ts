import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

/**
 * 左侧工具岛只有一块浮层位置：节点库与项目库抽屉不能同时显示，
 * 否则两个 floating-drawer 会叠在同一处（用户可见的重叠 bug）。
 */
test('node library and asset drawer never overlap', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: 'left-panels-project', title: '左栏互斥测试' }] });
    }
    if (path === '/projects' && request.method() === 'POST') {
      return route.fulfill({ json: { id: 'left-panels-project', title: '左栏互斥测试' } });
    }
    if (path === '/projects/left-panels-project') {
      return route.fulfill({ json: { id: 'left-panels-project', title: '左栏互斥测试' } });
    }
    if (path === '/projects/left-panels-project/canvases') {
      return route.fulfill({
        json: [{ id: 'left-panels-canvas', projectId: 'left-panels-project', title: '默认画布' }],
      });
    }
    if (path === '/canvases/left-panels-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/left-panels-canvas' && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: 'left-panels-canvas', title: '默认画布' } });
    }
    if (path === '/project-library') {
      return route.fulfill({
        json: {
          projectTree: { scope: 'PROJECT', ownerProjectId: 'left-panels-project', folders: [], unfiledAssetCount: 0 },
          globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
          assets: [],
          groups: [],
        },
      });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/?token=test-token&userId=test-user');
  await page.evaluate(async () => {
    const now = Date.now();
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('legacyProjects')) db.createObjectStore('legacyProjects', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assetMeta')) db.createObjectStore('assetMeta', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assetVariants')) db.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
      if (!db.objectStoreNames.contains('generationQueue')) db.createObjectStore('generationQueue', { keyPath: 'id' });
    };
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const transaction = db.transaction(['legacyProjects', 'canvases'], 'readwrite');
    transaction.objectStore('legacyProjects').put({
      schemaVersion: 2, id: 'left-panels-project', title: '左栏互斥测试', canvases: ['left-panels-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'left-panels-canvas', projectId: 'left-panels-project', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=left-panels-project&canvas=left-panels-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();

  const library = page.locator('.node-library');
  const drawer = page.locator('.asset-drawer-panel');

  await page.getByRole('button', { name: '添加节点' }).click();
  await expect(library).toBeVisible();
  await expect(drawer).toHaveCount(0);
  await page.screenshot({ path: 'test-results/left-panels-1-node-library.png' });

  // 打开项目库时节点库必须让位
  await page.getByRole('button', { name: '项目库' }).click();
  await expect(drawer).toBeVisible();
  await expect(library).toHaveCount(0);
  await page.screenshot({ path: 'test-results/left-panels-2-asset-drawer.png' });

  // 反向也成立：再点添加节点，项目库抽屉收起
  await page.getByRole('button', { name: '添加节点' }).click();
  await expect(library).toBeVisible();
  await expect(drawer).toHaveCount(0);

  // 生成记录与项目库同样互斥
  await page.getByRole('button', { name: '项目库' }).click();
  await expect(drawer).toBeVisible();
  await page.getByRole('button', { name: '生成记录' }).click();
  await expect(page.locator('.generation-records-panel')).toBeVisible();
  await expect(drawer).toHaveCount(0);

  expect(errors).toEqual([]);
});
