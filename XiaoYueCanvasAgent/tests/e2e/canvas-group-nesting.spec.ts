import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

test('nests one canvas frame inside another and promotes it back out', async ({ page }) => {
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
    const image = (id: string, label: string, x: number, y: number) => ({
      id,
      type: 'image',
      position: { x, y },
      width: 250,
      height: 250,
      data: { kind: 'image', label, prompt: '', status: 'idle', accent: '#929292' },
    });

    if (path === '/projects' && method === 'GET') return json([{ id: 'project-1', title: '嵌套验收' }]);
    if (path === '/projects/project-1' && method === 'GET') return json({ id: 'project-1', title: '嵌套验收' });
    if (path === '/projects/project-1/canvases' && method === 'GET') return json([{ id: 'canvas-1', projectId: 'project-1', title: '画布 1' }]);
    if (path === '/canvases/canvas-1/graph' && method === 'GET') {
      return json({
        graph: {
          schemaVersion: 2,
          nodes: [
            image('a1', 'A1', 100, 300),
            image('a2', 'A2', 420, 300),
            image('b1', 'B1', 100, 720),
            image('b2', 'B2', 420, 720),
          ],
          edges: [],
          viewport: { x: 0, y: 0, zoom: .6 },
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
  await expect(page.getByRole('textbox', { name: '画布名称' })).toHaveValue('嵌套验收');

  const selectPair = async (first: string, second: string) => {
    await page.locator(`.react-flow__node[data-id="${first}"]`).click();
    await page.locator(`.react-flow__node[data-id="${second}"]`).click({ modifiers: ['Control'] });
    await expect(page.getByRole('toolbar', { name: '多选节点操作' })).toBeVisible();
    await page.getByRole('button', { name: '创建分组' }).click();
  };

  await selectPair('a1', 'a2');
  await selectPair('b1', 'b2');
  await expect(page.locator('.canvas-frame')).toHaveCount(2);

  await page.getByRole('button', { name: '分组操作 分组 2' }).click();
  await page.getByRole('menuitem', { name: '移入「分组 1」' }).click();

  const childFrame = page.locator('.canvas-frame', { hasText: '分组 2' });
  const parentFrame = page.locator('.canvas-frame', { hasText: '分组 1' });
  await expect(childFrame).toHaveAttribute('data-group-depth', '1');
  await expect(parentFrame).toHaveAttribute('data-group-depth', '0');

  const parentBox = await parentFrame.boundingBox();
  const childBox = await childFrame.boundingBox();
  expect(parentBox).not.toBeNull();
  expect(childBox).not.toBeNull();
  expect(childBox!.x).toBeGreaterThanOrEqual(parentBox!.x - 1);
  expect(childBox!.y).toBeGreaterThanOrEqual(parentBox!.y - 1);
  expect(childBox!.x + childBox!.width).toBeLessThanOrEqual(parentBox!.x + parentBox!.width + 1);
  expect(childBox!.y + childBox!.height).toBeLessThanOrEqual(parentBox!.y + parentBox!.height + 1);

  await page.getByRole('button', { name: '折叠分组 分组 1' }).click();
  await expect(page.locator('.canvas-frame')).toHaveCount(1);
  await expect(page.locator('.react-flow__node[data-id="b1"]')).toBeHidden();
  await page.getByRole('button', { name: '展开分组 分组 1' }).click();
  await expect(page.locator('.canvas-frame')).toHaveCount(2);
  await expect(page.locator('.react-flow__node[data-id="b1"]')).toBeVisible();

  await page.getByRole('button', { name: '分组操作 分组 2' }).click();
  await page.getByRole('menuitem', { name: '移出「分组 1」' }).click();
  await expect(childFrame).toHaveAttribute('data-group-depth', '0');

  // 右键菜单删除分组：连同组内节点一起删掉，剩下的分组不受影响。
  await childFrame.locator('.canvas-frame__head').click({ button: 'right' });
  await page.getByRole('menu', { name: '分组菜单 分组 2' }).getByRole('menuitem', { name: /删除分组/ }).click();
  await expect(page.locator('.react-flow__node[data-id="b1"]')).toHaveCount(0);
  await expect(page.locator('.react-flow__node[data-id="b2"]')).toHaveCount(0);
  await expect(page.locator('.canvas-frame')).toHaveCount(1);
  await expect(page.locator('.react-flow__node[data-id="a1"]')).toBeVisible();
});
