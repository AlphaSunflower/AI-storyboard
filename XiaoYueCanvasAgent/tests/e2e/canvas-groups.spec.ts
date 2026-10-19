import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

test('creates, renames, drags and dissolves a canvas frame', async ({ page }) => {
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
    localStorage.setItem('morrow_job_token', `header.${payload}.signature`);
  });
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const path = url.pathname.replace('/api/canvas', '');
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

    if (path === '/projects' && method === 'GET') return json([{ id: 'project-1', title: '分组验收' }]);
    if (path === '/projects/project-1' && method === 'GET') return json({ id: 'project-1', title: '分组验收' });
    if (path === '/projects/project-1/canvases' && method === 'GET') return json([{ id: 'canvas-1', projectId: 'project-1', title: '画布 1' }]);
    if (path === '/canvases/canvas-1/graph' && method === 'GET') {
      return json({
        graph: {
          schemaVersion: 2,
          nodes: [
            { id: 'source', type: 'image', position: { x: 120, y: 180 }, width: 230, height: 190, data: { kind: 'image', label: '参考图片', prompt: '', status: 'success', accent: '#ff775f', assetId: 'asset-1' } },
            { id: 'producer', type: 'imageGeneration', position: { x: 520, y: 180 }, width: 480, height: 560, data: { kind: 'imageGeneration', label: '图片生成', prompt: '', status: 'running', accent: '#9d9d9d', assetId: 'producer-asset', isGenerating: true, progressPercent: 42 } },
          ],
          edges: [{ id: 'edge-1', source: 'source', sourceHandle: 'image', target: 'producer', targetHandle: 'reference', type: 'canvas' }],
          viewport: { x: 0, y: 0, zoom: 1 },
          generationRecords: [],
        },
        revision: 1,
      });
    }
    if (method === 'PUT') return json({ graph: JSON.parse(route.request().postData() ?? '{}').graph, revision: 2 });
    if (method === 'PATCH' || method === 'DELETE') return json({ ok: true });
    return json({ code: 40401, message: `not mocked: ${path}` });
  });

  await page.goto('/#project=project-1&canvas=canvas-1');
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('分组验收');

  await page.locator('.react-flow__node[data-id="source"]').click({ position: { x: 12, y: 12 } });
  await page.locator('.react-flow__node[data-id="producer"]').click({ position: { x: 18, y: 16 }, modifiers: ['Control'] });
  await expect(page.getByRole('toolbar', { name: '多选节点操作' })).toBeVisible();
  // 连线要等节点完成测量后才画出来；先等它出现，后面的折叠断言才有意义。
  await expect(page.locator('.react-flow__edge')).toHaveCount(1, { timeout: 10_000 });
  await page.getByRole('button', { name: '创建分组' }).click();

  const frame = page.locator('.canvas-frame');
  await expect(frame).toHaveCount(1);
  await expect(frame).toContainText('分组 1');
  await expect(frame).toContainText('2 个节点');
  await expect(frame.getByText('生成中 1/1')).toBeVisible();
  await expect(frame.locator('.canvas-frame__result')).toHaveCount(2);

  // 画布缩小时标题条会反向放大：注入 --inverse-zoom=2，执行按钮应该明显变大。
  const runButton = frame.getByRole('button', { name: '执行分组 分组 1' });
  const normalRunBox = await runButton.boundingBox();
  await page.evaluate(() => (document.querySelector('.canvas-workspace') as HTMLElement | null)?.style.setProperty('--inverse-zoom', '2'));
  const scaledRunBox = await runButton.boundingBox();
  expect(scaledRunBox!.height).toBeGreaterThan(normalRunBox!.height * 1.5);
  await page.evaluate(() => (document.querySelector('.canvas-workspace') as HTMLElement | null)?.style.setProperty('--inverse-zoom', '1'));

  await page.getByRole('button', { name: '折叠分组 分组 1' }).click();
  await expect(frame).toHaveClass(/is-collapsed/);
  await expect(page.locator('.react-flow__node[data-id="source"]')).toBeHidden();
  await expect(page.locator('.react-flow__node[data-id="producer"]')).toBeHidden();
  await expect(page.locator('.react-flow__edge')).toHaveCount(0);
  await page.getByRole('button', { name: '展开分组 分组 1' }).click();
  await expect(frame).not.toHaveClass(/is-collapsed/);
  await expect(page.locator('.react-flow__node[data-id="source"]')).toBeVisible();
  await expect(page.locator('.react-flow__node[data-id="producer"]')).toBeVisible();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);

  await page.locator('.canvas-frame__title').dblclick();
  const rename = page.getByRole('textbox', { name: '分组名称' });
  await rename.fill('第一场');
  await rename.press('Enter');
  await expect(frame).toContainText('第一场');

  // 标题条上的「执行」：整组按依赖顺序串行开始
  await page.getByRole('button', { name: '执行分组 第一场' }).click();
  await expect(page.getByText(/已按节点顺序开始执行「第一场」/)).toBeVisible();

  const before = await page.locator('.react-flow__node[data-id="source"]').boundingBox();
  const header = await page.locator('.canvas-frame__head').boundingBox();
  await page.mouse.move(header!.x + 40, header!.y + 18);
  await page.mouse.down();
  await page.mouse.move(header!.x + 140, header!.y + 98, { steps: 8 });
  await page.mouse.up();
  const after = await page.locator('.react-flow__node[data-id="source"]').boundingBox();
  expect(after!.x - before!.x).toBeGreaterThan(60);
  expect(after!.y - before!.y).toBeGreaterThan(40);

  // 右键标题条出菜单：执行 / 重命名 / 解除分组 / 删除。
  await page.locator('.canvas-frame__head').click({ button: 'right' });
  const frameMenu = page.getByRole('menu', { name: '分组菜单 第一场' });
  await expect(frameMenu.getByRole('menuitem', { name: '执行' })).toBeVisible();
  await expect(frameMenu.getByRole('menuitem', { name: '重命名' })).toBeVisible();
  await frameMenu.getByRole('menuitem', { name: '解除分组' }).click();
  await expect(page.locator('.canvas-frame')).toHaveCount(0);
  await expect(page.locator('.react-flow__node[data-id="source"]')).toBeVisible();
  await expect(page.locator('.react-flow__node[data-id="producer"]')).toBeVisible();
});

