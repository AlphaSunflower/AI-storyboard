import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

/** 左栏节点库不再收在「+」抽屉里，节点全部平铺成图标；浮层位置只剩项目库与生成记录两块。 */
test('tool rail lays out every node kind and keeps the remaining panels mutually exclusive', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: 'left-panels-project', title: '左栏互斥测试' }] });
    }
    if (path === '/projects' && request.method() === 'POST') {
      return route.fulfill({ json: { id: 'left-panels-project', title: '左栏互斥测试' } });
    }
    if (path === '/projects/left-panels-project') {
      return route.fulfill({ json: { id: 'left-panels-project', title: '左栏互斥测试' } });
    }
    if (path === '/projects/left-panels-project/canvases') {
      return route.fulfill({
        json: [{ id: 'left-panels-canvas', projectId: 'left-panels-project', title: '默认画布' }],
      });
    }
    if (path === '/canvases/left-panels-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/left-panels-canvas' && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: 'left-panels-canvas', title: '默认画布' } });
    }
    if (path === '/project-library') {
      return route.fulfill({
        json: {
          projectTree: { scope: 'PROJECT', ownerProjectId: 'left-panels-project', folders: [], unfiledAssetCount: 0 },
          globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
          assets: [],
          groups: [],
        },
      });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
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
      schemaVersion: 2, id: 'left-panels-project', title: '左栏互斥测试', canvases: ['left-panels-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'left-panels-canvas', projectId: 'left-panels-project', title: '默认画布',
      nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=left-panels-project&canvas=left-panels-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();

  const rail = page.locator('.canvas-tool-island');
  const drawer = page.locator('.asset-drawer-panel');

  // 节点全部铺出来 + 上传 / 项目库，旧的「+ 添加节点」按钮与节点库抽屉都不再存在。
  const railLabels = await rail.getByRole('button').evaluateAll(
    (buttons) => buttons.map((button) => button.getAttribute('aria-label')),
  );
  expect(railLabels).toEqual(['文字', '图片', '视频', '分镜拆解', '镜头', '图片生成', '视频生成', '视频合并', '上传', '项目库']);
  await expect(page.getByRole('button', { name: '添加节点' })).toHaveCount(0);
  await expect(page.locator('.node-library')).toHaveCount(0);

  // 悬停前名称被裁掉且透明；悬停后整条栏变宽到能装下名称，名称也浮出来（即梦那种手感）。
  const label = rail.locator('.tool-rail-label').first();
  expect(await label.evaluate((element) => getComputedStyle(element).opacity)).toBe('0');
  const collapsed = await rail.boundingBox();

  // 展开必须是一次真的宽度过渡，而不是一帧跳到位。
  // 用户报的「悬停出现字，但没动画」就是这条：宽度瞬间到位，看不到丝滑展开。
  const railTransition = await rail.evaluate((element) => {
    const style = getComputedStyle(element);
    return { property: style.transitionProperty, duration: style.transitionDuration };
  });
  expect(railTransition.property, `左栏没有对 width 声明过渡（实际：${JSON.stringify(railTransition)}）`).toContain('width');
  expect(Number.parseFloat(railTransition.duration), `左栏 width 过渡时长被压成 0（实际：${JSON.stringify(railTransition)}）`).toBeGreaterThan(0.05);

  await rail.hover();
  await expect.poll(() => label.evaluate((element) => getComputedStyle(element).opacity)).toBe('1');
  const expanded = await rail.boundingBox();
  expect(expanded!.width).toBeGreaterThan(collapsed!.width);

  // 每个名称都必须真的落在栏内，而不是被 overflow 裁掉——展开宽度按最长名称算，
  // 少算一点这里就会挂。只用第一个名称断言会漏掉「分镜拆解」这种最长的。
  const labelBoxes = await rail.locator('.tool-rail-label').evaluateAll((labels) =>
    labels.map((element) => {
      const rect = element.getBoundingClientRect();
      return { text: element.textContent, right: rect.right };
    }));
  expect(labelBoxes).toHaveLength(10);
  const railRight = expanded!.x + expanded!.width;
  for (const box of labelBoxes) {
    expect(box.right, `名称「${box.text}」被左栏裁掉了`).toBeLessThanOrEqual(railRight + 1);
  }
  // 「有没有动画」的真正判据：宽度必须经过中间值。只断言 transition 声明存在，
  // 查不出「声明了但没跑」——那才是用户报的「出现字，但没动画」。
  await page.mouse.move(4, 4);
  await expect.poll(async () => Math.round((await rail.boundingBox())!.width)).toBe(Math.round(collapsed!.width));
  // 页内逐帧采样（不经 Playwright 往返）＋真鼠标悬停，量出被眼睛看到的那段位移分布。
  // 只断言「经过中间值」不够——先快后慢也是经过中间值，但看起来就是一帧跳完。
  await page.evaluate(() => {
    const element = document.querySelector('.canvas-tool-island') as HTMLElement;
    const frames: Array<[number, number]> = [];
    (window as unknown as { __railFrames: Array<[number, number]> }).__railFrames = frames;
    const start = performance.now();
    const tick = () => {
      frames.push([Math.round(performance.now() - start), Math.round(parseFloat(getComputedStyle(element).width))]);
      if (performance.now() - start < 420) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
  await page.mouse.move(collapsed!.x + 20, collapsed!.y + 40);
  await page.waitForTimeout(500);
  const profile = await page.evaluate(
    () => (window as unknown as { __railFrames: Array<[number, number]> }).__railFrames,
  );
  const from = profile[0][1];
  const to = profile[profile.length - 1][1];
  const travel = to - from;
  const midway = profile.filter(([, width]) => width > from + 1 && width < to - 1);
  expect(midway.length, `宽度没经过中间值，等于没有过渡：${JSON.stringify(profile.slice(0, 12))}`).toBeGreaterThan(0);

  // 「看起来像瞬间跳变」的判据：位移不能挤在前半段。曲线太陡时（例如 cubic-bezier(.2,.8,.3,1)）
  // 一半时长就走完 92%，剩下半程只挪几像素——技术上有过渡，眼睛看到的是啪一下跳开。
  const declaredMs = Number.parseFloat(railTransition.duration)
    * (railTransition.duration.endsWith('ms') ? 1 : 1000);
  const atHalf = profile.find(([ms]) => ms >= declaredMs / 2)?.[1] ?? to;
  const progressAtHalf = (atHalf - from) / travel;
  expect(
    progressAtHalf,
    `一半时长（${declaredMs / 2}ms）已走完 ${Math.round(progressAtHalf * 100)}% 的位移，看起来是瞬间跳变而不是展开：${JSON.stringify(profile.slice(0, 16))}`,
  ).toBeLessThan(0.85);

  await page.screenshot({ path: 'test-results/left-panels-1-tool-rail.png' });

  // 点图标就在画布上落一个对应 kind 的节点，不需要再开抽屉。
  await page.getByRole('button', { name: '镜头' }).click();
  await expect(page.locator('.media-node--shot')).toHaveCount(1);
  await page.getByRole('button', { name: '分镜拆解' }).click();
  await expect(page.locator('.media-node--storyboardPlanner')).toHaveCount(1);

  // 项目库抽屉与生成记录面板共用左侧浮层位置，必须互斥。
  await page.getByRole('button', { name: '项目库' }).click();
  await expect(drawer).toBeVisible();
  await expect(page.locator('.generation-records-panel')).toHaveCount(0);
  await page.screenshot({ path: 'test-results/left-panels-2-asset-drawer.png' });

  await page.getByRole('button', { name: '生成记录' }).click();
  await expect(page.locator('.generation-records-panel')).toBeVisible();
  await expect(drawer).toHaveCount(0);

  expect(errors).toEqual([]);
});
