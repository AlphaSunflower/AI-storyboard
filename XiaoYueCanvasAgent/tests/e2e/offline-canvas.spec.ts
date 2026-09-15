import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('morrow-creative-canvas-v1', 4);
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

test('starts empty and exposes only honest local execution', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await expect(page.getByText('还没有本地项目')).toBeVisible();
  await expect(page.getByText(/精选|订阅|credits|Studio Mock/)).toHaveCount(0);
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();
  await expect(page.getByText('未配置执行服务', { exact: true })).toBeVisible();

  const png = await page.evaluate(async () => {
    const canvas = document.createElement('canvas'); canvas.width = 24; canvas.height = 18;
    const context = canvas.getContext('2d')!; context.fillStyle = '#ff765f'; context.fillRect(0, 0, 24, 18); context.fillStyle = '#d7ff54'; context.fillRect(4, 4, 10, 8);
    return [...new Uint8Array(await (await new Promise<Blob>((resolve) => canvas.toBlob((blob) => resolve(blob!), 'image/png'))).arrayBuffer())];
  });
  const input = page.locator('main.canvas-workspace input[type=file]');
  await input.setInputFiles({ name: 'tiny.png', mimeType: 'image/png', buffer: Buffer.from(png) });
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await expect(page.getByTitle('裁剪')).toBeEnabled();
  await expect(page.getByTitle(/多角度.*未配置执行服务/)).toBeDisabled();
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
  await expect(page.getByRole('textbox', { name: '重绘要求' })).toBeVisible();
  await expect(page.getByTitle('画笔')).toBeVisible();
  await expect(page.getByTitle('框选')).toBeVisible();
  await expect(page.getByTitle('橡皮擦')).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await page.getByRole('textbox', { name: '重绘要求' }).fill('将选区改成蓝色织物');
  await page.getByTitle('生成重绘结果').click();
  await expect(page.getByRole('status').filter({ hasText: '请先在图片上绘制蒙版' })).toBeVisible();

  const redrawOverlay = page.locator('.redraw-mask-overlay');
  const redrawBox = await redrawOverlay.boundingBox();
  expect(redrawBox).not.toBeNull();
  const brushTarget = { x: redrawBox!.x + redrawBox!.width * .2, y: redrawBox!.y + redrawBox!.height * .35 };
  await page.mouse.move(brushTarget.x, brushTarget.y);
  await expect(redrawOverlay).toHaveCSS('cursor', /url\("data:image\/svg\+xml/);
  await expect(page.locator('.redraw-brush-cursor')).toHaveCount(0);
  await page.mouse.down();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .55, redrawBox!.y + redrawBox!.height * .55, { steps: 5 });
  await page.mouse.up();
  await expect(page.getByText('已绘制 1 处蒙版')).toBeVisible();
  await expect(page.getByTitle('撤销')).toBeEnabled();
  await page.getByTitle('撤销').click();
  await expect(page.getByText('绘制蒙版以重绘')).toBeVisible();
  await expect(page.getByTitle('重做')).toBeEnabled();
  await page.getByTitle('重做').click();
  await page.getByTitle('框选').click();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .62, redrawBox!.y + redrawBox!.height * .18);
  await page.mouse.down();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .86, redrawBox!.y + redrawBox!.height * .42, { steps: 3 });
  await page.mouse.up();
  await expect(page.getByText('已绘制 2 处蒙版')).toBeVisible();
  await page.getByTitle('橡皮擦').click();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .4, redrawBox!.y + redrawBox!.height * .4);
  await page.mouse.down();
  await page.mouse.move(redrawBox!.x + redrawBox!.width * .5, redrawBox!.y + redrawBox!.height * .5, { steps: 3 });
  await page.mouse.up();
  await expect(page.getByText('已绘制 3 处蒙版')).toBeVisible();
  await page.getByTitle('生成数量').click();
  await expect(page.getByTitle('生成数量')).toHaveText('×2');
  await page.getByRole('button', { name: 'NORMAL', exact: true }).click();
  await expect(page.getByRole('button', { name: 'PRO', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '2K', exact: true })).toBeVisible();
  await page.getByTitle('生成重绘结果').click();
  await expect(page.getByRole('status').filter({ hasText: '未配置图像执行服务' })).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await expect(page.locator('.canvas-edge__line')).toHaveCount(0);
  await page.getByTitle('退出重绘').click();

  await page.getByTitle('裁剪').click();
  await page.getByRole('button', { name: '1 : 1', exact: true }).click();
  await page.getByRole('button', { name: '应用', exact: true }).click();
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
  await expect(page.locator('.canvas-edge__line')).toHaveCount(1);
  await page.locator('.canvas-edge__hit').dispatchEvent('contextmenu', { bubbles: true, clientX: 520, clientY: 420, button: 2 });
  await expect(page.getByRole('button', { name: '剪断连接' })).toBeVisible();
  await page.keyboard.press('Escape');
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
    const request = indexedDB.open('morrow-creative-canvas-v1', 4);
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onupgradeneeded = () => {
        const next = request.result;
        if (!next.objectStoreNames.contains('projects')) next.createObjectStore('projects', { keyPath: 'id' });
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
    const transaction = db.transaction(['projects', 'canvases'], 'readwrite');
    transaction.objectStore('projects').put({ schemaVersion: 2, id, title: '500 节点性能基准', canvases: [canvasId], createdAt: now, updatedAt: now });
    transaction.objectStore('canvases').put({ schemaVersion: 1, id: canvasId, projectId: id, title: '默认画布', nodes, edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now });
    await new Promise<void>((resolve, reject) => { transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error); });
    db.close();
  }, { id: projectId, canvasId });

  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(`/#project=${projectId}&canvas=${canvasId}`);
  await page.reload();
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('500 节点性能基准');
  await expect(page.locator('.project-copy small')).toHaveText('已保存到本地');
  await page.waitForTimeout(700);
  const readProjectUpdatedAt = async () => page.evaluate(async (id) => {
    const request = indexedDB.open('morrow-creative-canvas-v1', 4);
    const db = await new Promise<IDBDatabase>((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
    const record = await new Promise<{ updatedAt: number }>((resolve, reject) => {
      const get = db.transaction('projects').objectStore('projects').get(id);
      get.onsuccess = () => resolve(get.result);
      get.onerror = () => reject(get.error);
    });
    db.close();
    return record.updatedAt;
  }, projectId);
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
  await page.mouse.down();
  await page.mouse.move(panEnd.x, panEnd.y, { steps: 8 });
  await page.mouse.up();
  const interactionDuration = Date.now() - startedAt;
  expect(interactionDuration).toBeLessThan(1500);
  expect(await page.locator('.react-flow__node').count()).toBeLessThan(50);
  await page.waitForTimeout(700);
  await expect(page.locator('.project-copy small')).toHaveText('已保存到本地');
  expect(await readProjectUpdatedAt()).toBe(updatedAtBeforeMove);

  await page.getByRole('slider', { name: '画布缩放' }).fill('20');
  await expect(page.getByText('20%', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '小地图' }).click();
  const minimap = page.locator('.canvas-minimap-svg');
  const minimapBox = await minimap.boundingBox();
  expect(minimapBox).not.toBeNull();
  const viewBoxBefore = await minimap.getAttribute('viewBox');
  const parseViewBox = (value: string | null) => value!.split(/\s+/).map(Number);
  const viewportFitsMap = async () => minimap.evaluate((element) => {
    const [mapX, mapY, mapWidth, mapHeight] = element.getAttribute('viewBox')!.split(/\s+/).map(Number);
    const viewport = element.querySelector('.canvas-minimap-viewport')!;
    const x = Number(viewport.getAttribute('x')); const y = Number(viewport.getAttribute('y'));
    const width = Number(viewport.getAttribute('width')); const height = Number(viewport.getAttribute('height'));
    return x >= mapX - .01 && y >= mapY - .01 && x + width <= mapX + mapWidth + .01 && y + height <= mapY + mapHeight + .01;
  });
  expect(await viewportFitsMap()).toBe(true);
  const viewportBefore = await page.locator('.canvas-minimap-viewport').evaluate((element) => ({
    x: Number(element.getAttribute('x')),
    y: Number(element.getAttribute('y')),
  }));
  await page.mouse.move(minimapBox!.x + minimapBox!.width * .35, minimapBox!.y + minimapBox!.height * .45);
  await page.mouse.down();
  await page.mouse.move(minimapBox!.x + minimapBox!.width * .68, minimapBox!.y + minimapBox!.height * .62, { steps: 6 });
  await page.mouse.up();
  const viewportAfter = await page.locator('.canvas-minimap-viewport').evaluate((element) => ({
    x: Number(element.getAttribute('x')),
    y: Number(element.getAttribute('y')),
  }));
  expect(await minimap.getAttribute('viewBox')).toBe(viewBoxBefore);
  expect(Math.abs(viewportAfter.x - viewportBefore.x) + Math.abs(viewportAfter.y - viewportBefore.y)).toBeGreaterThan(1);
  expect(await viewportFitsMap()).toBe(true);

  const paneAfterMap = page.locator('.react-flow__pane');
  const paneAfterMapBox = await paneAfterMap.boundingBox();
  expect(paneAfterMapBox).not.toBeNull();
  await page.mouse.move(paneAfterMapBox!.x + paneAfterMapBox!.width * .72, paneAfterMapBox!.y + paneAfterMapBox!.height * .72);
  await page.mouse.down();
  await page.mouse.move(paneAfterMapBox!.x + paneAfterMapBox!.width * .25, paneAfterMapBox!.y + paneAfterMapBox!.height * .25, { steps: 8 });
  await page.mouse.up();
  await page.waitForTimeout(80);
  expect(await viewportFitsMap()).toBe(true);
  const expandedViewBox = parseViewBox(await minimap.getAttribute('viewBox'));
  const previousViewBox = parseViewBox(viewBoxBefore);
  expect(expandedViewBox[2]).toBeGreaterThanOrEqual(previousViewBox[2]);
  expect(expandedViewBox[3]).toBeGreaterThanOrEqual(previousViewBox[3]);
  expect(errors).toEqual([]);
});

test('matches the canvas, image, text, and edge context menu behavior', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  const pane = page.locator('.react-flow__pane');
  const paneBox = await pane.boundingBox();
  expect(paneBox).not.toBeNull();

  const revisionBeforeMenu = await page.evaluate(() => Number(document.querySelector('.project-copy small')?.textContent?.length ?? 0));
  await pane.click({ button: 'right', position: { x: paneBox!.width - 10, y: paneBox!.height - 10 } });
  const menu = page.getByRole('menu', { name: '画布右键菜单' });
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '上传' })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '添加资产' })).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: '添加辅助工具' })).toBeDisabled();
  await expect(menu.getByRole('menuitem', { name: /撤销/ })).toBeDisabled();
  await expect(menu.getByRole('menuitem', { name: /粘贴/ })).toBeDisabled();
  const menuBox = await menu.boundingBox();
  expect(menuBox!.x + menuBox!.width).toBeLessThanOrEqual((await page.evaluate(() => innerWidth)) - 7);
  expect(menuBox!.y + menuBox!.height).toBeLessThanOrEqual((await page.evaluate(() => innerHeight)) - 7);

  await menu.getByRole('menuitem', { name: '添加节点' }).hover();
  await menu.getByRole('menuitem', { name: '文字节点' }).click();
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

  await page.locator('.react-flow__node').first().press('Control+d');
  await expect(page.locator('.react-flow__node')).toHaveCount(3);

  expect(await page.evaluate((before) => Number(document.querySelector('.project-copy small')?.textContent?.length ?? 0) >= before, revisionBeforeMenu)).toBe(true);
});

test('keeps video controls and screen chrome inside responsive viewports', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await page.getByRole('button', { name: '添加节点' }).click();
  await page.getByRole('button', { name: /视频.*导入真实视频/ }).click();
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
  await expect(page.locator('.chat-workspace')).toHaveCSS('width', '0px');
});
