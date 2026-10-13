import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

/**
 * 画布底纹点阵：可见，且随缩放同步放大 / 拉疏（React Flow 的 pattern 尺寸乘 zoom）。
 */
test('canvas keeps a visible dot grid that grows with zoom', async ({ page }) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: 'grid-project', title: '点阵测试' }] });
    }
    if (path === '/projects' && request.method() === 'POST') {
      return route.fulfill({ json: { id: 'grid-project', title: '点阵测试' } });
    }
    if (path === '/projects/grid-project') {
      return route.fulfill({ json: { id: 'grid-project', title: '点阵测试' } });
    }
    if (path === '/projects/grid-project/canvases') {
      return route.fulfill({ json: [{ id: 'grid-canvas', projectId: 'grid-project', title: '默认画布' }] });
    }
    if (path === '/canvases/grid-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/grid-canvas' && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: 'grid-canvas', title: '默认画布' } });
    }
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
      schemaVersion: 2, id: 'grid-project', title: '点阵测试', canvases: ['grid-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'grid-canvas', projectId: 'grid-project', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=grid-project&canvas=grid-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();

  const pattern = page.locator('.react-flow__background-pattern');
  await expect(pattern).toHaveCount(1);
  // 点本身要可见：填充不透明且不是黑
  const fill = await pattern.evaluate((element) => getComputedStyle(element).fill);
  expect(fill).not.toBe('none');
  expect(fill).not.toBe('rgb(0, 0, 0)');

  const dotRadius = async () => Number(await pattern.getAttribute('r'));
  const patternGap = async () => Number(await page.locator('.react-flow__background pattern').getAttribute('width'));
  const radiusAt1x = await dotRadius();
  const gapAt1x = await patternGap();
  expect(radiusAt1x).toBeGreaterThan(0);
  expect(gapAt1x).toBeGreaterThan(0);
  await page.screenshot({ path: 'test-results/canvas-dot-grid-zoom1.png' });

  await page.locator('.react-flow__controls-zoomin').click();
  await page.locator('.react-flow__controls-zoomin').click();

  await expect.poll(dotRadius).toBeGreaterThan(radiusAt1x);
  await expect.poll(patternGap).toBeGreaterThan(gapAt1x);

  await page.screenshot({ path: 'test-results/canvas-dot-grid.png' });
});
