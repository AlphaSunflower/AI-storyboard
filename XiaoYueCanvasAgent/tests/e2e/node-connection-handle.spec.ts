import { expect, test, type Page } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

/**
 * 连接点：单侧只画一个可见的即梦式「+」，同侧其余端口仍留在 DOM 里做连线锚定。
 *
 * 这些断言在 jsdom 里做不了（需要真实的 CSS 层叠与布局），所以放在 e2e。
 * 其中「Handle 自身不能有不透明底色」是关键回归：
 * styles.css 里曾有 6 处 .node-handle 覆盖块互相打架，其中一处
 * `background: #1a1a1a !important` 靠着更靠后的源码顺序压过新样式，
 * 在放大画布时画出一个随缩放变大的深色圆盘，而反向缩放的圆钮又小又错位。
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
    if (path === '/projects' && request.method() === 'GET') return route.fulfill({ json: [{ id: 'handle-project', title: '连接点' }] });
    if (path === '/projects/handle-project') return route.fulfill({ json: { id: 'handle-project', title: '连接点' } });
    if (path === '/projects/handle-project/canvases') return route.fulfill({ json: [{ id: 'handle-canvas', projectId: 'handle-project', title: '默认画布' }] });
    if (path === '/canvases/handle-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/handle-canvas' && request.method() === 'PATCH') return route.fulfill({ json: { id: 'handle-canvas', title: '默认画布' } });
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
      schemaVersion: 2, id: 'handle-project', title: '连接点', canvases: ['handle-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'handle-canvas', projectId: 'handle-project', title: '默认画布',
      // 「视频生成」有 3 个输入端口 + 1 个输出端口，用来验证「多端口、每侧一个圆钮」
      nodes: [
        { id: 'n-vg', type: 'videoGeneration', position: { x: 260, y: 240 }, width: 400, height: 340, data: { kind: 'videoGeneration', label: '视频生成', prompt: '', status: 'idle', accent: '#8f9cff' } },
      ],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=handle-project&canvas=handle-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await page.waitForTimeout(400);
};

const computed = (page: Page, selector: string, property: string) =>
  page.locator(selector).first().evaluate(
    (element, name) => getComputedStyle(element).getPropertyValue(name),
    property,
  );

test('keeps every port anchored but paints a single + on each side', async ({ page }) => {
  await seedCanvas(page);
  const node = page.locator('.react-flow__node').first();
  const handles = node.locator('.node-handle');

  // 3 输入 + 1 输出：端口一个都不能少，否则已有连线会失去锚点。
  await expect(handles).toHaveCount(4);
  // 每侧只留一个可点击/可见的连接点。
  await expect(node.locator('.node-handle:not(.node-handle--passive)')).toHaveCount(2);
  await expect(node.locator('.node-handle--passive')).toHaveCount(2);
  await expect(node.locator('.node-handle__plus')).toHaveCount(2);
});

test('handle itself paints nothing, so no stray disc can appear over the card', async ({ page }) => {
  await seedCanvas(page);

  // 回归点：Handle 只是命中区/锚点，可见的圆钮在子元素上。
  // 一旦又冒出带底色的 .node-handle 覆盖块，这里就会红。
  await expect.poll(() => computed(page, '.node-handle', 'background-color')).toBe('rgba(0, 0, 0, 0)');
  await expect.poll(() => computed(page, '.node-handle', 'border-top-width')).toBe('0px');
  await expect.poll(() => computed(page, '.node-handle', 'width')).toBe('36px');
});

test('stays hidden at rest, and reveals an offset + that clears the card on hover', async ({ page }) => {
  await seedCanvas(page);
  const node = page.locator('.react-flow__node').first();

  // 静止（未选中、未悬浮）：连接点整体不可见
  await page.mouse.move(1300, 830);
  await page.waitForTimeout(250);
  expect(Number(await computed(page, '.node-handle', 'opacity'))).toBe(0);

  // 悬浮：显示出来
  await node.hover();
  await page.waitForTimeout(250);
  expect(Number(await computed(page, '.node-handle', 'opacity'))).toBe(1);

  // 圆钮完全落在卡片外侧，不压住节点内容（图片/视频）
  const [nodeBox, plusBox] = await Promise.all([
    node.boundingBox(),
    node.locator('.node-handle__plus').first().boundingBox(),
  ]);
  expect(nodeBox).not.toBeNull();
  expect(plusBox).not.toBeNull();
  expect(plusBox!.x + plusBox!.width).toBeLessThanOrEqual(nodeBox!.x + 1);

  await page.screenshot({ path: 'test-results/node-connection-handle.png' });
});
