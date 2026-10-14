import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

test('dismisses canvas overlays outside and toggles the execution panel', async ({ page }) => {
  const projectId = 'overlay-project';
  const canvasId = 'overlay-canvas';
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const accessToken = `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;

  await page.route('**/api/auth/refresh', (route) => {
    return route.fulfill({ json: { data: { accessToken, userId: 'test-user' } } });
  });
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    const json = (body: unknown) => route.fulfill({ json: body });

    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return json({ id: projectId, title: '浮层交互测试' });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return json([{ id: canvasId, projectId, title: '默认画布' }]);
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
  // 画布命令信箱已并入 canvas 服务，走同源 /api/canvas/bridge/**
  await page.route('**/api/canvas/bridge/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/canvas/bridge', '');
    if (path === '/health') return route.fulfill({ json: { ok: true, version: 1, projects: [] } });
    if (path === '/poll') return route.fulfill({ json: {} });
    return route.fulfill({ json: { ok: true } });
  });

  await page.goto(`/#project=${projectId}&canvas=${canvasId}`);
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('浮层交互测试');

  const blankCanvasPoint = { x: 520, y: 640 };

  await page.getByRole('button', { name: '任务队列' }).click();
  await expect(page.getByRole('dialog', { name: '任务队列' })).toBeVisible();
  await page.mouse.click(blankCanvasPoint.x, blankCanvasPoint.y);
  await expect(page.getByRole('dialog', { name: '任务队列' })).toHaveCount(0);

  await page.getByRole('button', { name: '切换画布' }).click();
  await expect(page.getByRole('dialog', { name: '切换画布' })).toBeVisible();
  await page.mouse.click(blankCanvasPoint.x, blankCanvasPoint.y);
  await expect(page.getByRole('dialog', { name: '切换画布' })).toHaveCount(0);

  // 生成记录抽屉：点空白处收起
  await page.getByRole('button', { name: '生成记录' }).click();
  await expect(page.getByRole('heading', { name: '生成记录' })).toBeVisible();
  await page.mouse.click(blankCanvasPoint.x, blankCanvasPoint.y);
  await expect(page.locator('.generation-records-panel')).toHaveCount(0);

  // 再打开后点右上角「任务队列」：两个入口并排，抽屉必须让位给队列
  await page.getByRole('button', { name: '生成记录' }).click();
  await expect(page.locator('.generation-records-panel')).toBeVisible();
  await page.getByRole('button', { name: '任务队列' }).click();
  await expect(page.locator('.generation-records-panel')).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: '任务队列' })).toBeVisible();

  // 「帮助」按钮与左侧工具岛的「执行服务」按钮已下架，原先针对这两个按钮的断言随之移除；
  // 保留下来的右下角入口是「打开执行服务」，其存在性由 canvas-controls.spec.ts 覆盖。
});
