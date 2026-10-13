import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

test('node library scrolls and the context submenu survives the hover gap', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  // 画布启动依赖 /api/auth/refresh 换 token；这里自闭环，避免依赖本机 ApiGateway。
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: 'node-library-menu-project', title: '节点菜单测试' }] });
    }
    if (path === '/projects' && request.method() === 'POST') {
      return route.fulfill({ json: { id: 'node-library-menu-project', title: '节点菜单测试' } });
    }
    if (path === '/projects/node-library-menu-project') {
      return route.fulfill({ json: { id: 'node-library-menu-project', title: '节点菜单测试' } });
    }
    if (path === '/projects/node-library-menu-project/canvases') {
      return route.fulfill({
        json: [{ id: 'node-library-menu-canvas', projectId: 'node-library-menu-project', title: '默认画布' }],
      });
    }
    if (path === '/canvases/node-library-menu-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/node-library-menu-canvas' && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: 'node-library-menu-canvas', title: '默认画布' } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.setViewportSize({ width: 1024, height: 560 });
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
      schemaVersion: 2, id: 'node-library-menu-project', title: '节点菜单测试', canvases: ['node-library-menu-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'node-library-menu-canvas', projectId: 'node-library-menu-project', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=node-library-menu-project&canvas=node-library-menu-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await expect(page.locator('.react-flow__controls.canvas-controls')).toBeVisible();
  await page.getByRole('button', { name: '本地工作区菜单' }).click();
  await expect(page.getByRole('menu', { name: '本地工作区菜单' }).getByText('返回项目中心')).toBeVisible();
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '小地图' }).click();
  await expect(page.locator('.react-flow__minimap.canvas-minimap')).toBeVisible();

  await expect(page.getByRole('button', { name: '节点库' })).toHaveCount(0);
  await page.getByRole('button', { name: '添加节点' }).click();
  const library = page.locator('.node-library');
  const libraryBody = page.locator('.node-library-body');
  await expect(library).toBeVisible();
  await expect(libraryBody).toBeVisible();
  expect(await libraryBody.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await libraryBody.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  // 合并后视频/图片各只剩一个生成节点
  const presets = libraryBody.locator('.node-preset');
  await expect(presets.filter({ hasText: '视频生成' })).toHaveCount(1);
  await expect(presets.filter({ hasText: '图片生成' })).toHaveCount(1);
  await expect(libraryBody.getByText('参考物生视频', { exact: true })).toHaveCount(0);
  await expect(libraryBody.getByText('文生图', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '关闭节点库' }).click();

  const pane = page.locator('.react-flow__pane');
  await pane.click({ button: 'right', position: { x: 140, y: 130 } });
  const menu = page.getByRole('menu', { name: '画布右键菜单' });
  const addNodeItem = menu.locator('.canvas-menu-item-wrap').filter({ hasText: '添加节点' });
  await addNodeItem.hover();

  const submenu = menu.getByRole('menu');
  await expect(submenu).toBeVisible();
  const [itemBox, submenuBox] = await Promise.all([addNodeItem.boundingBox(), submenu.boundingBox()]);
  expect(itemBox).not.toBeNull();
  expect(submenuBox).not.toBeNull();

  const gapX = (itemBox!.x + itemBox!.width + submenuBox!.x) / 2;
  await page.mouse.move(gapX, itemBox!.y + itemBox!.height / 2);
  await page.waitForTimeout(250);
  await expect(submenu).toBeVisible();

  await submenu.getByRole('button', { name: '图片生成', exact: true }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await expect(menu).toHaveCount(0);
  await expect(page.locator('.react-flow__node.selected')).toHaveCount(1);
  await page.keyboard.press('Escape');

  await pane.click({ button: 'right', position: { x: 42, y: 84 } });
  await addNodeItem.hover();
  await submenu.getByRole('button', { name: '文字', exact: true }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
  const textNode = page.locator('.react-flow__node').filter({ hasText: '文字' });
  const textToImageNode = page.locator('.react-flow__node').filter({ hasText: '图片生成' });
  const sourceHandle = textNode.locator('.react-flow__handle.source');
  const targetHandle = textToImageNode.locator('.react-flow__handle.target').first();
  const [sourceBox, targetBox] = await Promise.all([sourceHandle.boundingBox(), targetHandle.boundingBox()]);
  expect(sourceBox).not.toBeNull();
  expect(targetBox).not.toBeNull();
  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + sourceBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(targetBox!.x + targetBox!.width / 2, targetBox!.y + targetBox!.height / 2, { steps: 8 });
  await page.mouse.up();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  expect(errors).toEqual([]);
});
