import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

/**
 * 显示/隐藏动效：每个「开」都要有入场中段帧，每个「关」都要先切退场态再卸载。
 * 只断言 class / 属性是不够的（那样「设了状态但动画被浏览器吃掉」也会通过），
 * 所以这里在页面里按帧采样 opacity / translate，必须采到中间值。
 */

type FrameSample = { op: number; tr: string; sc: string };

/**
 * 触发动作的同时按帧采样指定元素。
 * 不能只给固定时长、也不能按「总帧数」收工：Playwright 等元素可点的那段延迟会吃掉窗口
 * （实测点击延迟可到 1.5s，元素还没出现采样就结束了）。这里以「元素首次出现」为起点，
 * 之后再采 700ms，足够覆盖 220ms 的进场。
 */
async function sampleDuring(
  page: import('@playwright/test').Page,
  selector: string,
  action: () => Promise<unknown>,
): Promise<FrameSample[]> {
  const sampling = page.evaluate(({ selector }) => new Promise<FrameSample[]>((resolve) => {
    const out: FrameSample[] = [];
    const start = performance.now();
    let appearedAt = 0;
    const tick = () => {
      const element = document.querySelector(selector);
      const now = performance.now();
      if (element) {
        if (!appearedAt) appearedAt = now;
        const style = getComputedStyle(element);
        out.push({ op: Number(style.opacity), tr: style.translate, sc: style.scale });
      }
      if (appearedAt && now - appearedAt > 700) resolve(out);
      else if (now - start > 5000) resolve(out);
      else requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }), { selector });
  await action();
  return sampling;
}

const inFlight = (frames: FrameSample[]) => frames.filter((frame) => frame.op > 0.02 && frame.op < 0.98);

/** 中段帧里必须出现位移，方向由调用方给（负号＝向上/向左） */
function expectMotion(frames: FrameSample[], direction: 'up' | 'left') {
  const moving = inFlight(frames);
  expect(moving.length).toBeGreaterThanOrEqual(3);
  const pattern = direction === 'up' ? /-\d/ : /^-\d/;
  expect(moving.some((frame) => pattern.test(frame.tr))).toBe(true);
}

async function bootstrapCanvas(page: import('@playwright/test').Page) {
  const projectId = 'motion-project';
  const canvasId = 'motion-canvas';
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const accessToken = `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;

  await page.route('**/api/auth/refresh', (route) => route.fulfill({
    json: { data: { accessToken, userId: 'test-user' } },
  }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    const json = (body: unknown) => route.fulfill({ json: body });

    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return json({ id: projectId, title: '动效测试' });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      // 三张画布：画布切换列表与项目中心的画布列表都要能看出错峰进场
      return json([
        { id: canvasId, projectId, title: '默认画布' },
        { id: `${canvasId}-b`, projectId, title: '第二画布' },
        { id: `${canvasId}-c`, projectId, title: '第三画布' },
      ]);
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, generationRecords: [] };
      return json({ graph, revision: 1 });
    }
    if (path === `/canvases/${canvasId}` && (request.method() === 'PATCH' || request.method() === 'DELETE')) {
      return json({ ok: true });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });
  await page.route('**/api/canvas/bridge/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/canvas/bridge', '');
    if (path === '/health') return route.fulfill({ json: { ok: true, version: 1, projects: [] } });
    if (path === '/poll') return route.fulfill({ json: {} });
    return route.fulfill({ json: { ok: true } });
  });

  await page.goto(`/#project=${projectId}&canvas=${canvasId}`);
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('动效测试');
}

test('画布覆盖层开合都带动效状态，且进场真的有位移', async ({ page }) => {
  await bootstrapCanvas(page);
  const blank = { x: 520, y: 640 };

  const queue = page.locator('.task-queue-panel');
  const records = page.locator('.generation-records-panel');
  const drawer = page.locator('.asset-drawer-panel');

  // 任务队列：从右上角按钮往下推入 → 点空白退场 → 卸载
  expectMotion(
    await sampleDuring(page, '.task-queue-panel', () => page.getByRole('button', { name: '任务队列' }).click()),
    'up',
  );
  await expect(queue).toHaveAttribute('data-present', 'true');
  expectMotion(
    await sampleDuring(page, '.task-queue-panel', async () => { await page.mouse.click(blank.x, blank.y); }),
    'up',
  );
  await expect(queue).toHaveCount(0);

  // 生成记录：右上角抽屉
  expectMotion(
    await sampleDuring(page, '.generation-records-panel', () => page.getByRole('button', { name: '生成记录' }).click()),
    'up',
  );
  await expect(records).toHaveAttribute('data-present', 'true');
  expectMotion(
    await sampleDuring(page, '.generation-records-panel', async () => { await page.mouse.click(blank.x, blank.y); }),
    'up',
  );
  await expect(records).toHaveCount(0);

  // 节点库抽屉已下架（节点直接铺在左栏），这里只剩项目库抽屉。

  // 项目库抽屉
  expectMotion(
    await sampleDuring(page, '.asset-drawer-panel', () => page.getByRole('button', { name: '项目库' }).click()),
    'left',
  );
  await expect(drawer).toHaveAttribute('data-present', 'true');
  await expect(drawer).toBeVisible();
  expectMotion(
    await sampleDuring(page, '.asset-drawer-panel', () => drawer.getByRole('button', { name: '关闭项目库' }).click()),
    'left',
  );
  await expect(drawer).toHaveCount(0);

  // 左上角画布列表：从项目栏往下推入
  expectMotion(
    await sampleDuring(page, '.canvas-switcher', () => page.getByRole('button', { name: '切换画布' }).click()),
    'up',
  );
  await page.mouse.click(blank.x, blank.y);
  await expect(page.locator('.canvas-switcher')).toHaveCount(0);
});

test('侧栏智能体与节点属性切换都是过渡而不是硬切', async ({ page }) => {
  await bootstrapCanvas(page);

  const shell = page.locator('.app-shell');
  const handle = page.locator('.panel-resize-handle');
  await expect(handle).toHaveAttribute('data-present', 'true');

  // 收起智能体：把手退场后卸载，右下角浮标进场
  await page.getByRole('button', { name: '收起面板' }).click();
  await expect(shell).toHaveClass(/side-is-closed/);
  const fab = page.getByRole('button', { name: '打开 Moon 智能体' });
  await expect(fab).toBeVisible();
  await expect(handle).toHaveCount(0);

  // 重新打开：浮标退场，把手回来
  await fab.click();
  await expect(handle).toHaveAttribute('data-present', 'true');

  // 标签页：两个视图都常驻，靠 data-active 交叉过渡
  const panes = page.locator('.chat-view');
  await expect(panes).toHaveCount(2);
  await expect(panes.nth(0)).toHaveAttribute('data-active', 'true');
  await expect(panes.nth(1)).toHaveAttribute('data-active', 'false');

  await page.getByRole('tab', { name: '节点属性' }).click();
  await expect(panes.nth(0)).toHaveAttribute('data-active', 'false');
  await expect(panes.nth(1)).toHaveAttribute('data-active', 'true');
});

test('reduced-motion 下显示/隐藏退化成瞬时切换', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await bootstrapCanvas(page);

  await page.getByRole('button', { name: '任务队列' }).click();
  const queue = page.locator('.task-queue-panel');
  await expect(queue).toHaveAttribute('data-present', 'true');

  // reduce 下过渡被关掉（只剩 .01ms 的全局兜底），开合都是瞬时
  const duration = await queue.evaluate((element) => getComputedStyle(element).transitionDuration);
  expect(Number.parseFloat(duration)).toBeLessThan(0.01);

  // 关闭时不再保留退场帧，直接卸载
  await page.mouse.click(520, 640);
  await expect(queue).toHaveCount(0);
});

test('项目列表 → 画布列表 → 进入画布 一路都有视图过渡', async ({ page }) => {
  await bootstrapCanvas(page);

  // 退回项目中心：视图壳淡入（back 方向）
  await page.getByRole('button', { name: '本地工作区菜单' }).click();
  const backFrames = await sampleDuring(page, '.view-shell', () => page.getByRole('menuitem', { name: '返回项目中心' }).click());
  await expect(page.locator('html')).toHaveAttribute('data-view-motion', 'back');
  // 视图壳只淡入 + 轻微缩放（不做横向位移：位移会让 #root 变成可滚动的容器）
  expect(inFlight(backFrames).length).toBeGreaterThanOrEqual(3);
  expect(backFrames.some((frame) => Number(frame.sc) < 0.999)).toBe(true);

  // 画布列表卡片依次进场
  const delays = await page.locator('.project-home__grid article').evaluateAll(
    (elements) => elements.map((element) => getComputedStyle(element).animationDelay),
  );
  expect(delays.length).toBeGreaterThanOrEqual(2);
  expect(new Set(delays).size).toBeGreaterThanOrEqual(2);

  // 进入画布：画布壳不缩放（会改变 React Flow 的命中几何），进场落在画布 chrome 上
  const enterFrames = await sampleDuring(page, '.canvas-project-bar', () => page.locator('.project-home__grid article').first().getByRole('button').first().click());
  await expect(page.locator('html')).toHaveAttribute('data-view-motion', 'forward');
  expect(inFlight(enterFrames).length).toBeGreaterThanOrEqual(3);
  await expect(page.locator('.canvas-workspace')).toBeVisible();
});
