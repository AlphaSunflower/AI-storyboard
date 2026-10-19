import { expect, test, type Page } from '@playwright/test';
import { showAllNodes } from './node-display';
import { prepareCanvasApp } from './support/canvasAuth';

test.beforeEach(async ({ page }) => {
  await prepareCanvasApp(page);
  await page.goto('/');
  await page.evaluate(async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('morrow-creative-canvas-v1', 5);
      request.onsuccess = () => {
        const db = request.result;
        const stores = [...db.objectStoreNames];
        if (!stores.length) { db.close(); resolve(); return; }
        const transaction = db.transaction(stores, 'readwrite');
        stores.forEach((store) => transaction.objectStore(store).clear());
        transaction.oncomplete = () => { db.close(); resolve(); };
        transaction.onerror = () => reject(transaction.error);
      };
      request.onerror = () => reject(request.error);
    });
    localStorage.clear(); sessionStorage.clear();
  });
  await page.reload();
});

async function seedTextCanvas(page: Page) {
  const projectId = 'canvas-selection-project';
  const canvasId = 'canvas-selection-canvas';
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '画布选择测试' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: { graph, revision: 1 } });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });
  await page.evaluate(async ({ projectId, canvasId }) => {
    const now = Date.now();
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('legacyProjects')) db.createObjectStore('legacyProjects', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('libraryEntries')) db.createObjectStore('libraryEntries', { keyPath: 'assetId' });
      if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
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
      schemaVersion: 2,
      id: projectId,
      title: '画布选择测试',
      canvases: [canvasId],
      createdAt: now,
      updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1,
      id: canvasId,
      projectId,
      title: '默认画布',
      nodes: [
        {
          id: 'selection-text-a',
          type: 'media',
          position: { x: 180, y: 180 },
          width: 280,
          height: 180,
          data: { kind: 'text', label: '节点 A', prompt: '', status: 'idle', accent: '#d7ff54' },
        },
        {
          id: 'selection-text-b',
          type: 'textToImage',
          position: { x: 420, y: 360 },
          width: 360,
          height: 300,
          data: { kind: 'textToImage', label: '节点 B', prompt: '', status: 'idle', accent: '#8f9cff' },
        },
      ],
      edges: [{
        id: 'selection-edge-a-b',
        source: 'selection-text-a',
        sourceHandle: 'text',
        target: 'selection-text-b',
        targetHandle: 'prompt',
        type: 'canvas',
      }],
      viewport: { x: 40, y: 40, zoom: 1 },
      createdAt: now,
      updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId, canvasId });
  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);
  await showAllNodes(page);
}

async function selectAllNodesByMarquee(page: Page) {
  const pane = page.locator('.react-flow__pane');
  const nodes = page.locator('.react-flow__node');
  await expect(nodes).toHaveCount(2);
  const paneBox = await pane.boundingBox();
  const nodeBoxes = await nodes.evaluateAll((elements) => elements.map((element) => {
    const rect = element.getBoundingClientRect();
    return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
  }));
  expect(paneBox).not.toBeNull();
  expect(nodeBoxes).toHaveLength(2);

  const selectionStart = {
    x: Math.max(paneBox!.x + 8, Math.min(...nodeBoxes.map((box) => box.x)) - 28),
    y: Math.max(paneBox!.y + 8, Math.min(...nodeBoxes.map((box) => box.y)) - 28),
  };
  const selectionEnd = {
    x: Math.min(paneBox!.x + paneBox!.width - 8, Math.max(...nodeBoxes.map((box) => box.x + box.width)) + 28),
    y: Math.min(paneBox!.y + paneBox!.height - 8, Math.max(...nodeBoxes.map((box) => box.y + box.height)) + 28),
  };
  await page.mouse.move(selectionStart.x, selectionStart.y);
  await page.mouse.down({ button: 'left' });
  await page.mouse.move(selectionEnd.x, selectionEnd.y, { steps: 8 });
  await page.mouse.up({ button: 'left' });

  await expect(page.locator('.react-flow__node.selected')).toHaveCount(2);
}

test('starts empty and exposes only honest local execution', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await expect(page.getByText('还没有本地项目')).toBeVisible();
  await expect(page.getByText(/精选|订阅|credits|Studio Mock/)).toHaveCount(0);
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();

  const png = await page.evaluate(async () => {
    const canvas = document.createElement('canvas'); canvas.width = 24; canvas.height = 18;
    const context = canvas.getContext('2d')!; context.fillStyle = '#ff765f'; context.fillRect(0, 0, 24, 18); context.fillStyle = '#d7ff54'; context.fillRect(4, 4, 10, 8);
    return [...new Uint8Array(await (await new Promise<Blob>((resolve) => canvas.toBlob((blob) => resolve(blob!), 'image/png'))).arrayBuffer())];
  });
  const input = page.locator('main.canvas-workspace input[type=file]');
  await input.setInputFiles({ name: 'tiny.png', mimeType: 'image/png', buffer: Buffer.from(png) });
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await expect(page.getByTitle('裁剪')).toBeEnabled();
  await expect(page.getByTitle('多角度', { exact: true })).toBeEnabled();
  await expect(page.getByTitle('重绘', { exact: true })).toBeEnabled();
  await expect(page.getByTitle('标注', { exact: true })).toHaveCount(0);

  await page.getByAltText('tiny.png').click({ button: 'right' });
  const imageContextMenu = page.getByRole('menu', { name: '画布右键菜单' });
  await expect(imageContextMenu.getByRole('menuitem', { name: '保存到素材库' })).toBeEnabled();
  await expect(imageContextMenu.getByRole('menuitem', { name: '应用所有历史' })).toBeDisabled();
  await expect(imageContextMenu.getByRole('menuitem', { name: '下载' })).toBeEnabled();
  await expect(imageContextMenu.getByRole('menuitem', { name: '复制到剪贴板' })).toBeEnabled();
  await page.keyboard.press('Escape');

  await page.getByTitle('重绘', { exact: true }).click();
  await expect(page.getByTitle('选择区域')).toBeVisible();
  await expect(page.getByTitle('框选')).toBeVisible();
  await expect(page.getByTitle('涂鸦')).toBeVisible();
  await expect(page.getByTitle('橡皮擦')).toHaveCount(0);
  await expect(page.getByText('先标记区域')).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(1);

  const redrawOverlay = page.locator('.redraw-mask-overlay');
  const redrawBox = await redrawOverlay.boundingBox();
  expect(redrawBox).not.toBeNull();
  const rectangleTarget = { x: redrawBox!.x + redrawBox!.width * .2, y: redrawBox!.y + redrawBox!.height * .35 };
  await page.mouse.move(rectangleTarget.x, rectangleTarget.y);
  await expect(redrawOverlay).toHaveCSS('cursor', 'crosshair');
  await page.mouse.down();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .55, redrawBox!.y + redrawBox!.height * .55, { steps: 5 });
  await page.mouse.up();
  await page.getByRole('textbox', { name: '修改 1 的意图' }).fill('将选区改成蓝色织物');
  await page.getByTitle('确认修改意图').click();
  await expect(page.getByText('1 个区域')).toBeVisible();
  await page.getByTitle('撤销').click();
  await expect(page.getByText('先标记区域')).toBeVisible();
  await expect(page.getByTitle('重做')).toBeEnabled();
  await page.getByTitle('重做').click();
  await expect(page.getByText('1 个区域')).toBeVisible();
  await page.getByTitle('框选').click();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .62, redrawBox!.y + redrawBox!.height * .18);
  await page.mouse.down();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .86, redrawBox!.y + redrawBox!.height * .42, { steps: 3 });
  await page.mouse.up();
  await page.getByRole('textbox', { name: '修改 2 的意图' }).fill('把墙面调暖');
  await page.getByRole('button', { name: '颜色 #59d8ff' }).click();
  await page.getByTitle('确认修改意图').click();
  await expect(page.getByText('2 个区域')).toBeVisible();
  await page.getByTitle('选择区域').click();
  const rectangle = page.locator('.redraw-region-mark').nth(1);
  await rectangle.hover();
  await expect(rectangle).toHaveClass(/is-hovered/);
  await rectangle.click();
  await expect(page.getByRole('textbox', { name: '修改 2 的意图' })).toHaveValue('把墙面调暖');
  await page.getByTitle('关闭').click();

  await page.getByTitle('执行重绘').click();
  await expect(page.getByRole('dialog', { name: '执行重绘' })).toBeVisible();
  // 对话框给出给模型的整段重绘提示词：两个选区各自的意图都要出现在里面
  await expect(page.locator('.redraw-execution-prompt')).toContainText('将选区改成蓝色织物');
  await expect(page.locator('.redraw-execution-prompt')).toContainText('把墙面调暖');
  await page.getByRole('dialog', { name: '执行重绘' }).getByRole('button', { name: '执行重绘', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '执行重绘' })
    .getByRole('status').filter({ hasText: '未配置图像执行服务' })).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await expect(page.locator('.canvas-edge__line')).toHaveCount(0);
  await page.getByRole('button', { name: '关闭执行重绘' }).click();
  await page.getByTitle('退出重绘').click();

  await page.getByTitle('裁剪').click();
  await page.getByRole('button', { name: '1 : 1', exact: true }).click();
  await page.getByRole('button', { name: '应用', exact: true }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
  await expect(page.locator('.canvas-edge__line')).toHaveCount(1);
  // 选中连线后出现悬浮工具条（当前交互不是右键菜单）
  await page.locator('.react-flow__edge-interaction').first()
    .dispatchEvent('click', { bubbles: true, button: 0 });
  await expect(page.getByRole('button', { name: '剪断连接' })).toBeVisible();
  await page.locator('.react-flow__pane').click({ position: { x: 12, y: 12 } });
  await expect(page.getByRole('button', { name: '剪断连接' })).toHaveCount(0);

  await expect(page.getByAltText('tiny.png')).toBeVisible();
  await page.getByAltText('tiny.png').click();
  await page.getByTitle('浏览全部').click();
  await expect(page.getByRole('button', { name: /扩图/ })).toBeDisabled();
  await expect(page.getByText('服务未连接').first()).toBeVisible();
  await page.getByRole('button', { name: '›', exact: true }).click();
  await page.getByRole('button', { name: '切分为 3×4', exact: true }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(14);
  await expect(page.locator('.canvas-edge__line')).toHaveCount(13);
  await expect(page.locator('.project-copy small')).toHaveText('已保存到本地');
  await page.reload();
  await expect(page.locator('.react-flow__node')).toHaveCount(14);

  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出项目', exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.morrow$/);
  expect(errors).toEqual([]);
});

test('marquee-selects nodes and drags the selected group', async ({ page }) => {
  await seedTextCanvas(page);

  const nodes = page.locator('.react-flow__node');
  await expect(nodes).toHaveCount(2);
  const viewportBeforeSelection = await page.locator('.react-flow__viewport').getAttribute('style');
  await selectAllNodesByMarquee(page);
  expect(await page.locator('.react-flow__viewport').getAttribute('style')).toBe(viewportBeforeSelection);

  const selectedNodes = page.locator('.react-flow__node.selected');
  const beforeDrag = await selectedNodes.evaluateAll((elements) => elements.map((element) => {
    const rect = element.getBoundingClientRect();
    return { x: rect.x, y: rect.y };
  }));
  const firstHandle = selectedNodes.first().locator('.node-drag-handle');
  const handleBox = await firstHandle.boundingBox();
  expect(handleBox).not.toBeNull();

  await page.mouse.move(handleBox!.x + 12, handleBox!.y + handleBox!.height / 2);
  await page.mouse.down({ button: 'left' });
  await page.mouse.move(handleBox!.x + 112, handleBox!.y + handleBox!.height / 2 + 64, { steps: 8 });
  await page.mouse.up({ button: 'left' });

  const afterDrag = await selectedNodes.evaluateAll((elements) => elements.map((element) => {
    const rect = element.getBoundingClientRect();
    return { x: rect.x, y: rect.y };
  }));
  expect(afterDrag).toHaveLength(2);
  expect(afterDrag.every((box, index) => Math.abs(box.x - beforeDrag[index].x) > 70 && Math.abs(box.y - beforeDrag[index].y) > 40)).toBe(true);
});

test('uses one group toolbar and context menu for a multi-node selection', async ({ page }) => {
  await seedTextCanvas(page);
  await selectAllNodesByMarquee(page);

  const nodes = page.locator('.react-flow__node');
  await expect(page.locator('.react-flow__node-toolbar.node-selected-toolbar')).toHaveCount(0);
  await expect(page.locator('.react-flow__edge.selected')).toHaveCount(1);
  await expect(page.locator('.react-flow__edge-toolbar.canvas-edge-toolbar')).toBeHidden();
  await expect(page.locator('.react-flow__resize-control').first()).toBeHidden();

  const toolbar = page.getByRole('toolbar', { name: '多选节点操作' });
  await expect(toolbar).toBeVisible();
  await expect(toolbar.getByRole('button', { name: '复制' })).toHaveAttribute('title', '复制 Ctrl+C');
  await expect(toolbar.getByRole('button', { name: '剪切' })).toHaveAttribute('title', '剪切 Ctrl+X');
  await expect(toolbar.getByRole('button', { name: '删除' })).toHaveAttribute('title', '删除 Ctrl+D');

  await toolbar.getByRole('button', { name: '复制' }).click();
  await page.keyboard.press('Control+v');
  await expect(nodes).toHaveCount(4);
  await page.keyboard.press('Control+z');
  await expect(nodes).toHaveCount(2);

  const selectionBox = page.locator('.react-flow__nodesselection-rect');
  await expect(selectionBox).toBeVisible();
  await selectionBox.click({ button: 'right' });
  const menu = page.getByRole('menu', { name: '画布右键菜单' });
  await expect(menu.getByRole('menuitem', { name: /复制/ })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: /剪切/ })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '整理编排' })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: /删除/ })).toBeVisible();
  await page.keyboard.press('Escape');

  await page.keyboard.press('Control+x');
  await expect(nodes).toHaveCount(0);
  await page.keyboard.press('Control+v');
  await expect(nodes).toHaveCount(2);
  await page.keyboard.press('Control+d');
  await expect(nodes).toHaveCount(0);
});

test('runs ELK layout from the toolbar and context menu without changing the viewport', async ({ page }) => {
  await seedTextCanvas(page);
  const nodes = page.locator('.react-flow__node');
  await expect(nodes).toHaveCount(2);
  const viewportBefore = await page.locator('.react-flow__viewport').getAttribute('style');
  const positionsBefore = await nodes.evaluateAll((elements) => elements.map((element) => {
    const rect = element.getBoundingClientRect();
    return { x: Math.round(rect.x), y: Math.round(rect.y) };
  }));

  await page.getByRole('button', { name: '整理编排' }).click();
  await expect(page.locator('.canvas-status-toast').first()).toContainText(/已整理|最优/);
  await expect.poll(async () => nodes.evaluateAll((elements) => elements.map((element) => {
    const rect = element.getBoundingClientRect();
    return { x: Math.round(rect.x), y: Math.round(rect.y) };
  }))).not.toEqual(positionsBefore);
  expect(await page.locator('.react-flow__viewport').getAttribute('style')).toBe(viewportBefore);

  await nodes.first().click({ button: 'right' });
  const nodeMenu = page.getByRole('menu', { name: '画布右键菜单' });
  await expect(nodeMenu.getByRole('menuitem', { name: '整理编排' })).toBeVisible();
  await nodeMenu.getByRole('menuitem', { name: '整理编排' }).click();

  const pane = page.locator('.react-flow__pane');
  const paneBox = await pane.boundingBox();
  expect(paneBox).not.toBeNull();
  await pane.click({ button: 'right', position: { x: paneBox!.width - 12, y: paneBox!.height - 12 } });
  const paneMenu = page.getByRole('menu', { name: '画布右键菜单' });
  await expect(paneMenu.getByRole('menuitem', { name: '整理编排' })).toBeVisible();
  await paneMenu.getByRole('menuitem', { name: '整理编排' }).click();
});

test('pans the canvas with the middle mouse button', async ({ page }) => {
  await seedTextCanvas(page);

  const pane = page.locator('.react-flow__pane');
  const paneBox = await pane.boundingBox();
  expect(paneBox).not.toBeNull();
  const blankPoint = await page.evaluate(({ x, y, width, height }) => {
    for (let row = 8; row >= 1; row -= 1) {
      for (let column = 8; column >= 1; column -= 1) {
        const point = { x: x + width * column / 10, y: y + height * row / 10 };
        if (document.elementFromPoint(point.x, point.y)?.classList.contains('react-flow__pane')) return point;
      }
    }
    throw new Error('没有找到可用于中键平移的画布空白点');
  }, paneBox!);
  const viewportBefore = await page.locator('.react-flow__viewport').getAttribute('style');

  await expect(pane).toHaveCSS('cursor', 'crosshair');
  await page.mouse.move(blankPoint.x, blankPoint.y);
  await page.mouse.down({ button: 'middle' });
  await page.mouse.move(blankPoint.x - 110, blankPoint.y - 70, { steps: 8 });
  await expect(pane).toHaveCSS('cursor', 'grabbing');
  await page.mouse.up({ button: 'middle' });

  expect(await page.locator('.react-flow__viewport').getAttribute('style')).not.toBe(viewportBefore);
});

test('project home lists canvases and navigates between levels', async ({ page }) => {
  // 新建项目 → 进项目菜单（空画布列表，手动建画布）
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await expect(page.getByText('还没有画布')).toBeVisible();

  // 新建第一个画布 → 进画布
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();

  // 返回项目菜单
  await page.getByRole('button', { name: '本地工作区菜单' }).click();
  await page.getByRole('menuitem', { name: '返回项目中心' }).click();
  await expect(page.getByText('画布 1')).toBeVisible();

  // 新建第二个画布 → 进画布 → 返回
  await page.getByRole('button', { name: '新建画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '本地工作区菜单' }).click();
  await page.getByRole('menuitem', { name: '返回项目中心' }).click();
  await expect(page.getByText('画布 2')).toBeVisible();

  // 返回主页
  await page.getByRole('button', { name: '返回主页' }).click();
  await expect(page.getByText('最近项目')).toBeVisible();
});

test('keeps a 500 node canvas bounded while moving and zooming', async ({ page }) => {
  const projectId = 'performance-500';
  const canvasId = 'performance-500-canvas';
  await page.evaluate(async ({ id, canvasId }) => {
    const now = Date.now();
    const nodes = Array.from({ length: 500 }, (_, index) => ({
      id: `perf-${index}`,
      type: 'media',
      position: { x: (index % 25) * 460, y: Math.floor(index / 25) * 360 },
      width: 200,
      height: 160,
      data: { kind: 'image', label: `性能节点 ${index + 1}`, prompt: '', status: 'idle', accent: '#d7ff54' },
    }));
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onupgradeneeded = () => {
        const next = request.result;
        if (!next.objectStoreNames.contains('legacyProjects')) next.createObjectStore('legacyProjects', { keyPath: 'id' });
        if (!next.objectStoreNames.contains('canvases')) next.createObjectStore('canvases', { keyPath: 'id' });
        if (!next.objectStoreNames.contains('assets')) next.createObjectStore('assets', { keyPath: 'id' });
        if (!next.objectStoreNames.contains('libraryEntries')) next.createObjectStore('libraryEntries', { keyPath: 'assetId' });
        if (!next.objectStoreNames.contains('settings')) next.createObjectStore('settings');
        if (!next.objectStoreNames.contains('assetMeta')) next.createObjectStore('assetMeta', { keyPath: 'id' });
        if (!next.objectStoreNames.contains('assetVariants')) next.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const transaction = db.transaction(['legacyProjects', 'canvases'], 'readwrite');
    transaction.objectStore('legacyProjects').put({ schemaVersion: 2, id, title: '500 节点性能基准', canvases: [canvasId], createdAt: now, updatedAt: now });
    transaction.objectStore('canvases').put({ schemaVersion: 1, id: canvasId, projectId: id, title: '默认画布', nodes, edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now });
    await new Promise<void>((resolve, reject) => { transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error); });
    db.close();
  }, { id: projectId, canvasId });

  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  // 本地旧项目按 id 直接开画布走不通了（loadProject 只认后端记录），
  // 改成从首页进入：App 会把旧本地项目懒迁移到后端并保留标题，再进画布。
  await page.goto('/');
  await expect(page.getByText('最近项目')).toBeVisible();
  await page.getByText('500 节点性能基准').first().click();
  await page.getByText('默认画布').first().click();
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('500 节点性能基准');
  await expect(page.locator('.project-copy small')).toHaveText('已保存到本地');
  await page.waitForTimeout(700);
  // 迁移后项目是后端记录（本地 legacyProjects 里的旧记录会被删除），所以从后端读 updatedAt
  const readProjectUpdatedAt = async () => page.evaluate(async () => {
    const id = new URLSearchParams(window.location.hash.slice(1)).get('project');
    const response = await fetch(`/api/canvas/projects/${id}`);
    const body = await response.json() as { updatedAt?: string };
    return body.updatedAt;
  });
  const updatedAtBeforeMove = await readProjectUpdatedAt();

  const mountedAtStart = await page.locator('.react-flow__node').count();
  expect(mountedAtStart).toBeLessThan(40);
  await expect(page.locator('.media-asset-frame img')).toHaveCount(0);

  const pane = page.locator('.react-flow__pane');
  const box = await pane.boundingBox();
  expect(box).not.toBeNull();
  const panStart = await page.evaluate(({ x, y, width, height }) => {
    for (let row = 1; row < 10; row += 1) {
      for (let column = 1; column < 10; column += 1) {
        const point = { x: x + width * column / 10, y: y + height * row / 10 };
        if (document.elementFromPoint(point.x, point.y)?.classList.contains('react-flow__pane')) return point;
      }
    }
    throw new Error('没有找到可用于平移测试的画布空白点');
  }, box!);
  const panEnd = {
    x: panStart.x + (panStart.x < box!.x + box!.width / 2 ? 90 : -90),
    y: panStart.y + (panStart.y < box!.y + box!.height / 2 ? 70 : -70),
  };
  const startedAt = Date.now();
  await page.mouse.move(panStart.x, panStart.y);
  await page.mouse.down({ button: 'middle' });
  await page.mouse.move(panEnd.x, panEnd.y, { steps: 8 });
  await page.mouse.up({ button: 'middle' });
  const interactionDuration = Date.now() - startedAt;
  expect(interactionDuration).toBeLessThan(1500);
  expect(await page.locator('.react-flow__node').count()).toBeLessThan(50);
  await page.waitForTimeout(700);
  await expect(page.locator('.project-copy small')).toHaveText('已保存到本地');
  expect(await readProjectUpdatedAt()).toBe(updatedAtBeforeMove);

  // 视图栏现在是 React Flow Controls 的放大/缩小按钮，没有百分比读数与缩放滑杆
  const zoomOut = page.locator('.react-flow__controls-zoomout');
  for (let step = 0; step < 8; step += 1) await zoomOut.click({ force: true });
  await page.waitForTimeout(200);

  await page.getByRole('button', { name: '小地图' }).click();
  // React Flow 自带 MiniMap：svg 是 .react-flow__minimap-svg，
  // 视口指示现在是遮罩路径 .react-flow__minimap-mask（d 里带 x/y/宽/高）
  const minimap = page.locator('.react-flow__minimap-svg');
  const minimapBox = await minimap.boundingBox();
  expect(minimapBox).not.toBeNull();
  const viewBoxBefore = await minimap.getAttribute('viewBox');
  const parseViewBox = (value: string | null) => value!.split(/\s+/).map(Number);
  // 遮罩路径有两段子路径：第一段是地图可视范围，第二段是当前视口矩形（各 4 个数）
  const readViewport = async () => page.locator('.react-flow__minimap-mask').evaluate((element) => {
    const path = element.getAttribute('d') ?? '';
    // 每段子路径形如 M<x>,<y>h<width>v<height>h-<width>z
    const rects = [...path.matchAll(/M(-?[\d.]+),(-?[\d.]+)h(-?[\d.]+)v(-?[\d.]+)/g)].map((match) => ({
      x: Number(match[1]),
      y: Number(match[2]),
      width: Math.abs(Number(match[3])),
      height: Math.abs(Number(match[4])),
    }));
    return { path, rects, viewport: rects[rects.length - 1] };
  });
  // 视口矩形必须存在且是有限的矩形数据（几何换算交给 React Flow，这里只保证可视指示有效）
  const minimapHasViewportRect = async () => {
    const { rects, viewport } = await readViewport();
    return rects.length >= 2 && Boolean(viewport)
      && [viewport.width, viewport.height].every((value) => Number.isFinite(value) && value > 0);
  };
  expect(await minimapHasViewportRect()).toBe(true);
  const viewportBefore = (await readViewport()).viewport;
  await page.mouse.move(minimapBox!.x + minimapBox!.width * .35, minimapBox!.y + minimapBox!.height * .45);
  await page.mouse.down();
  await page.mouse.move(minimapBox!.x + minimapBox!.width * .68, minimapBox!.y + minimapBox!.height * .62, { steps: 6 });
  await page.mouse.up();
  const viewportAfter = (await readViewport()).viewport;
  // 小地图范围是「节点包围盒 ∪ 当前视口」，所以拖动后可能变大；这里只要求仍是合法视框
  const [, , widthAfter, heightAfter] = parseViewBox(await minimap.getAttribute('viewBox'));
  expect(widthAfter).toBeGreaterThan(0);
  expect(heightAfter).toBeGreaterThan(0);
  expect(viewportAfter).not.toEqual(viewportBefore);
  expect(await minimapHasViewportRect()).toBe(true);

  const paneAfterMap = page.locator('.react-flow__pane');
  const paneAfterMapBox = await paneAfterMap.boundingBox();
  expect(paneAfterMapBox).not.toBeNull();
  await page.mouse.move(paneAfterMapBox!.x + paneAfterMapBox!.width * .72, paneAfterMapBox!.y + paneAfterMapBox!.height * .72);
  await page.mouse.down({ button: 'middle' });
  await page.mouse.move(paneAfterMapBox!.x + paneAfterMapBox!.width * .25, paneAfterMapBox!.y + paneAfterMapBox!.height * .25, { steps: 8 });
  await page.mouse.up({ button: 'middle' });
  await page.waitForTimeout(80);
  expect(await minimapHasViewportRect()).toBe(true);
  const expandedViewBox = parseViewBox(await minimap.getAttribute('viewBox'));
  // 中键平移后小地图仍要给出有效的视框与视口矩形（范围随「节点 ∪ 视口」变化，不再断言单调变大）
  expect(expandedViewBox[2]).toBeGreaterThan(0);
  expect(expandedViewBox[3]).toBeGreaterThan(0);
  expect(await minimapHasViewportRect()).toBe(true);
  expect(errors).toEqual([]);
});

test('matches the canvas, image, text, and edge context menu behavior', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  const pane = page.locator('.react-flow__pane');
  const paneBox = await pane.boundingBox();
  expect(paneBox).not.toBeNull();

  const revisionText = () => page.locator('.project-copy small').textContent();
  const revisionBeforeMenu = await revisionText();
  await pane.click({ button: 'right', position: { x: paneBox!.width - 10, y: paneBox!.height - 10 } });
  const menu = page.getByRole('menu', { name: '画布右键菜单' });
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '上传本地文件' })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '上传资产' })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '添加辅助工具' })).toBeDisabled();
  await expect(menu.getByRole('menuitem', { name: /撤销/ })).toBeDisabled();
  await expect(menu.getByRole('menuitem', { name: /粘贴/ })).toBeDisabled();
  const menuBox = await menu.boundingBox();
  expect(menuBox!.x + menuBox!.width).toBeLessThanOrEqual((await page.evaluate(() => innerWidth)) - 7);
  expect(menuBox!.y + menuBox!.height).toBeLessThanOrEqual((await page.evaluate(() => innerHeight)) - 7);

  // 节点落在右键位置；贴边建点会让节点中心跑到视口外，所以把菜单挪到画布中间再建节点。
  await page.keyboard.press('Escape');
  await pane.click({ button: 'right', position: { x: paneBox!.width * .72, y: paneBox!.height * .25 } });
  await expect(menu).toBeVisible();

  await menu.getByRole('menuitem', { name: '添加节点' }).hover();
  // 分组子菜单里的条目是普通 button，不是 menuitem 角色
  await menu.getByRole('button', { name: '文字', exact: true }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await page.locator('.react-flow__node').click({ button: 'right' });
  await expect(menu.getByRole('menuitem', { name: /复制/ })).toBeEnabled();
  await expect(menu.getByRole('menuitem', { name: '保存到素材库' })).toHaveCount(0);
  await expect(menu.getByRole('menuitem', { name: '反馈问题' })).toBeDisabled();
  await menu.getByRole('menuitem', { name: /复制/ }).click();

  await pane.click({ button: 'right', position: { x: paneBox!.width * .7, y: paneBox!.height * .45 } });
  await expect(menu.getByRole('menuitem', { name: /粘贴/ })).toBeEnabled();
  await menu.getByRole('menuitem', { name: /粘贴/ }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
  await page.keyboard.press('Control+z');
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await page.keyboard.press('Control+Shift+z');
  await expect(page.locator('.react-flow__node')).toHaveCount(2);

  // 当前快捷键表里 Ctrl+D 是删除，复制走右键菜单的「副本」
  await page.locator('.react-flow__node').first().click({ button: 'right' });
  await page.getByRole('menu', { name: '画布右键菜单' })
    .getByRole('menuitem', { name: '副本', exact: true }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(3);

  // 复制/粘贴/撤销都进历史后，revision 指示必须前进（比较内容而不是长度：v9 → v10 长度会变小）。
  await expect.poll(revisionText).not.toBe(revisionBeforeMenu);
});

test('keeps video controls and screen chrome inside responsive viewports', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  // 节点库抽屉已下架，视频节点直接从左侧工具栏落下来。
  // name 默认是「不区分大小写的子串匹配」，不加 exact 会同时命中「视频生成」「视频合并」。
  await page.getByRole('button', { name: '视频', exact: true }).click();
  await expect(page.locator('.video-editor-panel')).toHaveCount(0);
  await expect(page.locator('.node-upload')).toBeVisible();

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1024, height: 768 },
    { width: 768, height: 600 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await page.waitForTimeout(80);
    const projectBar = await page.locator('.canvas-project-bar').boundingBox();
    const toolIsland = await page.locator('.canvas-tool-island').boundingBox();
    expect(projectBar).not.toBeNull();
    expect(toolIsland).not.toBeNull();
    for (const box of [projectBar!, toolIsland!]) {
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.y).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
      expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
    }

    const overlapPairs = await page.evaluate(() => {
      const selectors = ['.canvas-project-bar', '.canvas-project-actions', '.canvas-tool-island', '.canvas-view-bar', '.canvas-minimap'];
      const boxes = selectors.flatMap((selector) => [...document.querySelectorAll<HTMLElement>(selector)].map((element) => {
        const rect = element.getBoundingClientRect();
        return { selector, left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height };
      })).filter((rect) => rect.width > 0 && rect.height > 0);
      const overlaps: string[][] = [];
      for (let first = 0; first < boxes.length; first += 1) {
        for (let second = first + 1; second < boxes.length; second += 1) {
          const a = boxes[first]; const b = boxes[second];
          if (Math.min(a.right, b.right) > Math.max(a.left, b.left) + 1 && Math.min(a.bottom, b.bottom) > Math.max(a.top, b.top) + 1) {
            overlaps.push([a.selector, b.selector]);
          }
        }
      }
      return overlaps;
    });
    expect(overlapPairs).toEqual([]);
  }
  // 手机端「侧栏收起后执行服务面板宽度为 0」这条先不做：
  // 手机端布局后面稳定了再回来补（用户：先不动手机端）。
});
