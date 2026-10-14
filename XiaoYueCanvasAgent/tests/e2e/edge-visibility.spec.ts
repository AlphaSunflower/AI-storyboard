import { expect, test, type Page } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

/**
 * 左下角「显示连线 / 隐藏连线」开关。
 *
 * 隐藏连线模式下所有连线都不画；点某个节点时，只把它自己的连线露出来，
 * 其余节点的连线依旧不显示（跟随选中，多选取并集）。
 *
 * 这些断言在 jsdom 里做不了：隐藏是靠 React Flow 的 edge.hidden 在渲染层跳过的，
 * 要真的把图画出来数 .react-flow__edge 才算数。
 */
test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

const seedCanvas = async (page: Page) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') return route.fulfill({ json: [{ id: 'edge-project', title: '连线' }] });
    if (path === '/projects/edge-project') return route.fulfill({ json: { id: 'edge-project', title: '连线' } });
    if (path === '/projects/edge-project/canvases') return route.fulfill({ json: [{ id: 'edge-canvas', projectId: 'edge-project', title: '默认画布' }] });
    if (path === '/canvases/edge-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/edge-canvas' && request.method() === 'PATCH') return route.fulfill({ json: { id: 'edge-canvas', title: '默认画布' } });
    return route.fulfill({ json: {} });
  });

  await page.setViewportSize({ width: 1920, height: 900 });
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
    transaction.objectStore('legacyProjects').put({ schemaVersion: 2, id: 'edge-project', title: '连线', canvases: ['edge-canvas'], createdAt: now, updatedAt: now });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'edge-canvas', projectId: 'edge-project', title: '默认画布',
      // 文字节点只有输出口 text；文生图节点收 prompt —— 这对端口能真的画出连线。
      // 全部落在 x<730 的可见区（1920 视口下聊天抽屉从 x≈730 起）。
      nodes: [
        { id: 'n-a', type: 'text', position: { x: 60, y: 120 }, width: 320, height: 240, data: { kind: 'text', label: '文字A', prompt: '甲', status: 'idle', accent: '#cacaca' } },
        { id: 'n-b', type: 'textToImage', position: { x: 460, y: 120 }, width: 320, height: 240, data: { kind: 'textToImage', label: '文生图B', prompt: '', status: 'idle', accent: '#8f9cff' } },
      ],
      edges: [
        { id: 'e-ab', source: 'n-a', sourceHandle: 'text', target: 'n-b', targetHandle: 'prompt', type: 'canvas' },
      ],
      viewport: { x: 0, y: 0, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => { transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error); });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=edge-project&canvas=edge-canvas');
  await page.locator('.canvas-workspace').waitFor();
  await page.waitForTimeout(500);
};

const edgeCount = (page: Page) => page.locator('.react-flow__edge').count();

test('隐藏连线后全隐藏，点节点只露它自己的连线', async ({ page }) => {
  await seedCanvas(page);

  // 默认是「显示连线」：连线画出来，按钮此刻表示「点下去会隐藏」。
  const hideButton = page.getByLabel('隐藏连线');
  await expect(hideButton).toBeVisible();
  // 图标真的画出来了（不是空按钮）：显示态给 EyeOff，隐藏态给 Eye。
  await expect(hideButton.locator('svg')).toHaveClass(/lucide-eye-off/);
  expect(await edgeCount(page)).toBe(1);

  await hideButton.click();

  // 全隐藏
  expect(await edgeCount(page)).toBe(0);
  const showButton = page.getByLabel('显示连线');
  await expect(showButton).toBeVisible();
  await expect(showButton.locator('svg')).toHaveClass(/lucide-eye\b/);

  // 点文字节点 A：它那条边露出来
  await page.locator('.react-flow__node[data-id="n-a"]').click();
  expect(await edgeCount(page)).toBe(1);

  // 点另一个节点 B：换 B 的连线（还是同一条），A 的收回
  await page.locator('.react-flow__node[data-id="n-b"]').click();
  expect(await edgeCount(page)).toBe(1);

  // 点空白处取消选中：连线全部收回
  await page.mouse.click(620, 830);
  expect(await edgeCount(page)).toBe(0);

  await page.locator('.canvas-view-bar').screenshot({ path: 'test-results/edge-visibility-bar.png' });
});

test('再点一次恢复显示连线，连线全部回来', async ({ page }) => {
  await seedCanvas(page);

  await page.getByLabel('隐藏连线').click();
  expect(await edgeCount(page)).toBe(0);

  await page.getByLabel('显示连线').click();

  expect(await edgeCount(page)).toBe(1);
  await expect(page.getByLabel('隐藏连线')).toBeVisible();
});

test('隐藏连线不会动到画布数据：恢复后连线仍在', async ({ page }) => {
  await seedCanvas(page);

  await page.getByLabel('隐藏连线').click();
  expect(await edgeCount(page)).toBe(0);
  await page.getByLabel('显示连线').click();

  // 只是视图层隐藏，store 里的边没被删——切回来还得在。
  expect(await edgeCount(page)).toBe(1);
  await page.locator('.react-flow__node[data-id="n-a"]').click();
  expect(await edgeCount(page)).toBe(1);
});
