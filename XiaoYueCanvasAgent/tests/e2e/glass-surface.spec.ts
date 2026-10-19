import { expect, test, type Locator } from '@playwright/test';
import { prepareCanvasApp } from './support/canvasAuth';

/**
 * 玻璃态表层回归：
 * - 画布外壳（项目栏 / 工具岛 / 视图条 / 抽屉 / 菜单 / 对话框）必须是「半透明 + backdrop 模糊」的玻璃面；
 * - 画布节点内部（节点卡片、参数面板、节点上的浮动小钮）必须是不透明实底——节点会叠在点阵、
 *   连线和别的节点上，半透明会让里面的参数文字读不出来（2026-10-19 用户实测）；
 * - Moon 智能体面板（.chat-workspace）保持原来的实底、不做玻璃——这是产品约定。
 *
 * 只看计算样式，不看像素：以后再加一层主题覆盖时，如果把这些面又压回错误的状态，
 * 这条用例会直接挂，而不是等人肉发现。
 */

const glassStyle = (locator: Locator) => locator.evaluate((element) => {
  const style = getComputedStyle(element);
  const background = style.backgroundColor;
  const alpha = background.startsWith('rgba(')
    ? Number(background.slice(background.lastIndexOf(',') + 1, -1))
    : 1;
  return {
    background,
    alpha,
    blur: style.backdropFilter || (style as CSSStyleDeclaration & { webkitBackdropFilter?: string }).webkitBackdropFilter || 'none',
  };
});

const expectGlass = async (locator: Locator, label: string) => {
  await expect(locator).toBeVisible();
  const style = await glassStyle(locator);
  expect(style.blur, `${label} 没有 backdrop 模糊`).toContain('blur');
  expect(style.alpha, `${label} 不是半透明底（${style.background}）`).toBeLessThan(1);
};

/** 节点内部：不允许模糊，且必须是实底。 */
const expectOpaque = async (locator: Locator, label: string) => {
  await expect(locator).toBeVisible();
  const style = await glassStyle(locator);
  expect(style.blur, `${label} 不应再做玻璃化`).toBe('none');
  expect(style.alpha, `${label} 应为实底（${style.background}）`).toBe(1);
};

test.beforeEach(async ({ page }) => {
  await prepareCanvasApp(page);
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: 'glass-project', title: '玻璃态回归' }] });
    }
    if (path === '/projects' && request.method() === 'POST') {
      return route.fulfill({ json: { id: 'glass-project', title: '玻璃态回归' } });
    }
    if (path === '/projects/glass-project') {
      return route.fulfill({ json: { id: 'glass-project', title: '玻璃态回归' } });
    }
    if (path === '/projects/glass-project/canvases') {
      return route.fulfill({ json: [{ id: 'glass-canvas', projectId: 'glass-project', title: '默认画布' }] });
    }
    if (path === '/canvases/glass-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/project-library') {
      return route.fulfill({
        json: {
          projectTree: { scope: 'PROJECT', ownerProjectId: 'glass-project', folders: [], unfiledAssetCount: 0 },
          globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
          assets: [],
          groups: [],
        },
      });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });
});

test('canvas chrome is glass, node internals and Moon agent panel stay opaque', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/?token=test-token&userId=test-user');
  // 种一个本地项目 + 空画布（与其它画布用例相同的离线启动方式）
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
      schemaVersion: 2, id: 'glass-project', title: '玻璃态回归', canvases: ['glass-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'glass-canvas', projectId: 'glass-project', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=glass-project&canvas=glass-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await page.waitForTimeout(500);

  // 画布 chrome：项目栏、左栏工具岛、缩放控制条、空画布提示
  await expectGlass(page.locator('.canvas-project-bar'), '项目栏');
  await expectGlass(page.locator('.canvas-tool-island'), '左栏工具岛');
  await expectGlass(page.locator('.canvas-view-bar'), '缩放控制条');
  await expectGlass(page.locator('.canvas-empty-hint'), '空画布提示');

  // Moon 智能体面板保持原样：实底、无模糊（「除了 Moon 智能体」的产品约定）
  const moon = await glassStyle(page.locator('.chat-workspace'));
  expect(moon.blur, 'Moon 智能体面板不该被玻璃化').toBe('none');
  expect(moon.alpha, 'Moon 智能体面板应保持不透明底').toBe(1);

  // 节点卡片：改成实底，不再透出画布点阵
  await page.locator('.canvas-tool-island').getByRole('button', { name: '文字', exact: true }).click();
  await page.waitForTimeout(400);
  await expectOpaque(page.locator('.node-card').first(), '节点卡片');

  // 左栏浮层：项目库抽屉 / 生成记录 / 任务队列（三者共用左侧浮层位，逐个开）
  await page.getByRole('button', { name: '项目库' }).click();
  await page.waitForTimeout(400);
  await expectGlass(page.locator('.asset-drawer-panel'), '项目库抽屉');
  await page.screenshot({ path: 'test-results/glass-asset-drawer.png' });

  await page.getByRole('button', { name: '生成记录' }).click();
  await page.waitForTimeout(400);
  await expectGlass(page.locator('.generation-records-panel'), '生成记录面板');

  await page.getByRole('button', { name: '任务队列' }).click();
  await page.waitForTimeout(400);
  await expectGlass(page.locator('.task-queue-panel'), '任务队列面板');

  await page.getByRole('button', { name: '小地图' }).click();
  await page.waitForTimeout(300);
  await expectGlass(page.locator('.canvas-minimap'), '小地图');

  // 右键菜单
  await page.mouse.click(700, 500, { button: 'right' });
  await page.waitForTimeout(300);
  await expectGlass(page.locator('.canvas-context-menu'), '右键菜单');
  await page.keyboard.press('Escape');

  // 项目切换器
  await page.locator('.project-switcher-trigger').first().click();
  await page.waitForTimeout(300);
  await expectGlass(page.locator('.project-switcher'), '项目切换器');
  await page.keyboard.press('Escape');

  await page.screenshot({ path: 'test-results/glass-canvas.png' });
});
