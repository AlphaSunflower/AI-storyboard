import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

/**
 * 节点标题改为卡片外的标题条：图标 + 可编辑标题，贴在卡片上方，
 * 并用 1/zoom 反向缩放，缩放画布时屏幕尺寸恒定。
 */
test('nodes show an external title that keeps its screen size on zoom', async ({ page }) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: 'title-project', title: '标题测试' }] });
    }
    if (path === '/projects' && request.method() === 'POST') {
      return route.fulfill({ json: { id: 'title-project', title: '标题测试' } });
    }
    if (path === '/projects/title-project') {
      return route.fulfill({ json: { id: 'title-project', title: '标题测试' } });
    }
    if (path === '/projects/title-project/canvases') {
      return route.fulfill({ json: [{ id: 'title-canvas', projectId: 'title-project', title: '默认画布' }] });
    }
    if (path === '/canvases/title-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/title-canvas' && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: 'title-canvas', title: '默认画布' } });
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
      schemaVersion: 2, id: 'title-project', title: '标题测试', canvases: ['title-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'title-canvas', projectId: 'title-project', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=title-project&canvas=title-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();

  // 通过右键菜单加一个「图片生成」节点
  const pane = page.locator('.react-flow__pane');
  await pane.click({ button: 'right', position: { x: 420, y: 300 } });
  const menu = page.getByRole('menu', { name: '画布右键菜单' });
  await menu.locator('.canvas-menu-item-wrap').filter({ hasText: '添加节点' }).hover();
  await menu.getByRole('menu').getByRole('button', { name: '图片生成', exact: true }).click();

  const node = page.locator('.react-flow__node').first();
  // 默认是紧凑显示模式：点一下节点会展开成完整卡片
  await node.click();
  const title = node.locator('.ai-node-external-title');
  const card = node.locator('.node-card');
  await expect(title).toBeVisible();
  await expect(title.locator('.ai-node-title')).toHaveText('图片生成');
  await expect(title.locator('.ai-node-icon svg')).toHaveCount(1);
  // 标题不再出现在卡片内部
  await expect(card.locator('.node-card__header-title')).toHaveCount(0);

  // 标题在卡片上方
  const [titleBox, cardBox] = await Promise.all([title.boundingBox(), card.boundingBox()]);
  expect(titleBox).not.toBeNull();
  expect(cardBox).not.toBeNull();
  expect(titleBox!.y + titleBox!.height).toBeLessThanOrEqual(cardBox!.y + 1);
  await page.screenshot({ path: 'test-results/node-external-title.png' });

  const scaleOfTitle = () => title.evaluate((element) => new DOMMatrixReadOnly(getComputedStyle(element).transform).a);
  const zoomOfCanvas = () => page.locator('.react-flow__viewport')
    .evaluate((element) => new DOMMatrixReadOnly(getComputedStyle(element).transform).a);

  // 反向缩放：标题 scale = 1/zoom
  const zoomAt1x = await zoomOfCanvas();
  await expect.poll(async () => Math.abs((await scaleOfTitle()) - 1 / zoomAt1x)).toBeLessThan(0.02);
  const screenHeightAt1x = titleBox!.height;

  await page.locator('.react-flow__controls-zoomin').click();
  await page.locator('.react-flow__controls-zoomin').click();
  await page.waitForTimeout(300);

  const zoomedIn = await zoomOfCanvas();
  expect(zoomedIn).toBeGreaterThan(zoomAt1x);
  await expect.poll(async () => Math.abs((await scaleOfTitle()) - 1 / zoomedIn)).toBeLessThan(0.02);
  const screenHeightZoomed = (await title.boundingBox())!.height;
  expect(Math.abs(screenHeightZoomed - screenHeightAt1x)).toBeLessThan(1.5);

  // 双击标题即可改节点名
  await title.locator('.ai-node-title').dblclick();
  const input = title.getByLabel('节点标题');
  await expect(input).toBeVisible();
  await input.fill('主视觉生图');
  await input.press('Enter');
  await expect(title.locator('.ai-node-title')).toHaveText('主视觉生图');
  await page.screenshot({ path: 'test-results/node-external-title-renamed.png' });
});
