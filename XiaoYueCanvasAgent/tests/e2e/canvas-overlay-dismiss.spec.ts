import { expect, test } from '@playwright/test';

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
  await page.route('http://127.0.0.1:8787/**', (route) => {
    return route.fulfill({ json: { ok: true } });
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

  await page.getByRole('button', { name: '帮助' }).click();
  await expect(page.getByText('双击空白处创建文字节点；拖入图片可直接导入')).toHaveCount(1);

  const agentButton = page.getByRole('button', { name: '执行服务', exact: true });
  await expect(agentButton).toHaveAttribute('aria-pressed', 'true');
  await agentButton.click();
  await expect(agentButton).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByRole('button', { name: '打开执行服务' })).toBeVisible();
  await agentButton.click();
  await expect(agentButton).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: '打开执行服务' })).toHaveCount(0);
});
