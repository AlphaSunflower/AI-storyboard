import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

/**
 * 项目切换列表：每行 ⋯ 菜单可重命名 / 删除项目，右键同样打开该菜单。
 */
test('renames and deletes projects from the switcher list', async ({ page }) => {
  const projects = [
    { id: 'p-1', title: '重生之我', canvasCount: 1, nodeCount: 12, assetCount: 3, updatedAt: Date.now() },
    { id: 'p-2', title: '咕咕嘎嘎', canvasCount: 1, nodeCount: 111, assetCount: 8, updatedAt: Date.now() },
  ];
  const patches: Array<{ id: string; body: string }> = [];
  const deletes: string[] = [];

  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: projects });
    }
    if (path.startsWith('/projects/') && request.method() === 'PATCH') {
      const id = path.slice('/projects/'.length);
      const body = request.postData() ?? '{}';
      patches.push({ id, body });
      projects.forEach((project) => {
        if (project.id === id) project.title = JSON.parse(body).title;
      });
      return route.fulfill({ json: { id, title: JSON.parse(body).title } });
    }
    if (path.startsWith('/projects/') && request.method() === 'DELETE') {
      const id = path.slice('/projects/'.length);
      deletes.push(id);
      const index = projects.findIndex((project) => project.id === id);
      if (index >= 0) projects.splice(index, 1);
      return route.fulfill({ json: { ok: true } });
    }
    if (path === '/projects/p-1') {
      return route.fulfill({ json: { id: 'p-1', title: '重生之我' } });
    }
    if (path === '/projects/p-1/canvases') {
      return route.fulfill({ json: [{ id: 'c-1', projectId: 'p-1', title: '默认画布' }] });
    }
    if (path === '/canvases/c-1/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/c-1' && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: 'c-1', title: '默认画布' } });
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
      schemaVersion: 2, id: 'p-1', title: '重生之我', canvases: ['c-1'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'c-1', projectId: 'p-1', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=p-1&canvas=c-1');
  await expect(page.locator('.canvas-workspace')).toBeVisible();

  await page.getByRole('button', { name: '搜索和切换项目' }).click();
  const list = page.getByRole('tree', { name: '本地项目' });
  await expect(list.getByRole('treeitem', { name: /咕咕嘎嘎/ })).toBeVisible();

  // ⋯ 菜单：重命名
  await page.getByRole('button', { name: '管理项目 咕咕嘎嘎' }).click();
  const menu = page.getByRole('menu', { name: '管理项目 咕咕嘎嘎' });
  await expect(menu.getByRole('menuitem', { name: '重命名' })).toBeVisible();
  await page.waitForTimeout(300); // 等菜单入场动画结束再截图
  await page.screenshot({ path: 'test-results/project-row-menu.png' });
  await menu.getByRole('menuitem', { name: '重命名' }).click();
  const renameDialog = page.getByRole('dialog').filter({ hasText: '重命名项目' });
  await renameDialog.getByLabel('项目名称').fill('咕咕嘎嘎 2')
  await renameDialog.getByRole('button', { name: '确认' }).click();
  await expect.poll(() => patches.length).toBe(1);
  expect(patches[0].id).toBe('p-2');
  expect(JSON.parse(patches[0].body).title).toBe('咕咕嘎嘎 2');

  // 选中行时会收起列表，重命名后重新打开可见新名字
  await page.getByRole('button', { name: '搜索和切换项目' }).click();
  await expect(list.getByRole('treeitem', { name: /咕咕嘎嘎 2/ })).toBeVisible();

  // 点面板内、菜单外的地方：只收菜单，列表留着
  await page.getByRole('button', { name: '管理项目 咕咕嘎嘎 2' }).click();
  await expect(page.getByRole('menu', { name: '管理项目 咕咕嘎嘎 2' })).toBeVisible();
  await page.getByLabel('搜索项目').click();
  await expect(page.getByRole('menu', { name: '管理项目 咕咕嘎嘎 2' })).toHaveCount(0);
  await expect(list).toBeVisible();

  // 右键同样打开该行菜单
  await list.getByRole('treeitem', { name: /咕咕嘎嘎 2/ }).click({ button: 'right' });
  const rowMenu = page.getByRole('menu', { name: '管理项目 咕咕嘎嘎 2' });
  await expect(rowMenu).toBeVisible();

  // 点画布空白：菜单和列表都要收起（点菜单外任何地方都不能留下悬空的菜单）
  await page.locator('.react-flow__pane').click({ position: { x: 640, y: 620 } });
  await expect(rowMenu).toHaveCount(0);
  await expect(list).toHaveCount(0);
  // 重新打开列表时也不能残留上一次的菜单
  await page.getByRole('button', { name: '搜索和切换项目' }).click();
  await expect(list).toBeVisible();
  await expect(page.getByRole('menu')).toHaveCount(0);

  // 删除项目
  await page.getByRole('button', { name: '管理项目 咕咕嘎嘎 2' }).click();
  await rowMenu.getByRole('menuitem', { name: '删除项目' }).click();
  const deleteDialog = page.getByRole('dialog').filter({ hasText: '删除项目「咕咕嘎嘎 2」？' });
  await deleteDialog.getByRole('button', { name: '确认删除' }).click();
  await expect.poll(() => deletes).toEqual(['p-2']);
  await expect(list.getByRole('treeitem', { name: /咕咕嘎嘎/ })).toHaveCount(0);

  // 画布列表的行菜单同样能被“点外部”收起
  await page.getByRole('button', { name: '切换画布' }).click();
  const canvasPanel = page.getByRole('dialog', { name: '切换画布' });
  await canvasPanel.getByRole('option').first().click({ button: 'right' });
  const canvasMenu = page.getByRole('menu', { name: /管理画布/ });
  await expect(canvasMenu).toBeVisible();
  await page.locator('.react-flow__pane').click({ position: { x: 640, y: 620 } });
  await expect(canvasMenu).toHaveCount(0);
  await expect(canvasPanel).toHaveCount(0);
});
