import { expect, test, type Page } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

/**
 * 画布控件栏与左下角面板的取舍：
 * - 去掉画布交互锁（Toggle Interactivity）、网格开关、帮助
 * - 网格改为常显（没有开关也必须有点阵）
 * - 小地图靠左，不靠右
 */
test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

const openCanvas = async (page: Page) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') return route.fulfill({ json: [{ id: 'ctrl-project', title: '控件' }] });
    if (path === '/projects/ctrl-project') return route.fulfill({ json: { id: 'ctrl-project', title: '控件' } });
    if (path === '/projects/ctrl-project/canvases') return route.fulfill({ json: [{ id: 'ctrl-canvas', projectId: 'ctrl-project', title: '默认画布' }] });
    if (path === '/canvases/ctrl-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/ctrl-canvas' && request.method() === 'PATCH') return route.fulfill({ json: { id: 'ctrl-canvas', title: '默认画布' } });
    return route.fulfill({ json: {} });
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
      schemaVersion: 2, id: 'ctrl-project', title: '控件', canvases: ['ctrl-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'ctrl-canvas', projectId: 'ctrl-project', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=ctrl-project&canvas=ctrl-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await page.waitForTimeout(400);
};

test('control bar drops the lock, grid and help buttons', async ({ page }) => {
  await openCanvas(page);
  const controls = page.locator('.canvas-view-bar');

  await expect(controls.getByRole('button', { name: 'Toggle Interactivity' })).toHaveCount(0);
  await expect(controls.getByRole('button', { name: '网格' })).toHaveCount(0);
  await expect(controls.getByRole('button', { name: '帮助' })).toHaveCount(0);

  // 保留的按钮还在
  await expect(controls.getByRole('button', { name: '小地图' })).toHaveCount(1);
  await expect(controls.getByRole('button', { name: '整理编排' })).toHaveCount(1);
});

test('grid dots render even though the toggle is gone', async ({ page }) => {
  await openCanvas(page);

  // Background 常驻：没有开关也必须有点阵
  await expect(page.locator('.react-flow__background')).toHaveCount(1);
});

test('minimap opens on the left, not the right', async ({ page }) => {
  await openCanvas(page);
  await page.locator('.canvas-view-bar').getByRole('button', { name: '小地图' }).click();

  const minimap = page.locator('.react-flow__minimap');
  await expect(minimap).toBeVisible();

  const box = await minimap.boundingBox();
  expect(box).not.toBeNull();
  // 视口中线：小地图必须整体落在左半边
  expect(box!.x + box!.width).toBeLessThan(1440 / 2);

  await page.screenshot({ path: 'test-results/canvas-controls.png' });
});
