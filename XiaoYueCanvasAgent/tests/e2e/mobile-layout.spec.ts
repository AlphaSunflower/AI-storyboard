import { expect, test, type Page } from '@playwright/test';

const assertInsideViewport = async (page: Page, selector: string) => {
  const box = await page.locator(selector).last().boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(-1);
  expect(box!.y).toBeGreaterThanOrEqual(-1);
  expect(box!.x + box!.width).toBeLessThanOrEqual(391);
  expect(box!.y + box!.height).toBeLessThanOrEqual(845);
};

const assertNoHorizontalOverflow = async (page: Page) => {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
};

test('adapts every canvas page to a phone viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
    localStorage.setItem('morrow_job_token', `header.${payload}.signature`);
  });
  await page.route('**/api/canvas/**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const path = url.pathname.replace('/api/canvas', '');
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });

    if (path === '/projects' && method === 'GET') return json([{ id: 'mobile-project', title: '移动端项目' }]);
    if (path === '/projects/mobile-project' && method === 'GET') return json({ id: 'mobile-project', title: '移动端项目' });
    if (path === '/projects/mobile-project/canvases' && method === 'GET') {
      return json([{ id: 'mobile-canvas', projectId: 'mobile-project', title: '移动画布' }]);
    }
    if (path === '/projects/mobile-project/canvases' && method === 'POST') {
      return json({ id: 'mobile-canvas', projectId: 'mobile-project', title: '移动画布' });
    }
    if (path === '/canvases/mobile-canvas/graph' && method === 'GET') {
      return json({ graph: { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 }, generationRecords: [] }, revision: 0 });
    }
    if (path === '/project-library' && method === 'GET') {
      return json({
        projectTree: {
          scope: 'PROJECT',
          ownerProjectId: 'mobile-project',
          folders: [],
          unfiledAssetCount: 0,
        },
        globalTree: {
          scope: 'GLOBAL',
          ownerProjectId: null,
          folders: [],
          unfiledAssetCount: 0,
        },
        assets: [],
        groups: [],
      });
    }
    if (method === 'PUT') return json({ graph: JSON.parse(route.request().postData() ?? '{}').graph, revision: 1 });
    if (method === 'PATCH' || method === 'DELETE') return json({ ok: true });
    return json({ code: 40401, message: `not mocked: ${path}` });
  });

  await page.goto('/');
  await expect(page.getByText('最近项目')).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await assertInsideViewport(page, '.canvas-home__hero');

  await page.locator('.canvas-home__grid article').first().getByRole('button').first().click();
  await expect(page.getByRole('heading', { name: '移动端项目' })).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await assertInsideViewport(page, '.project-home__header');

  await page.getByRole('button', { name: '新建画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await assertInsideViewport(page, '.canvas-project-bar');
  await assertInsideViewport(page, '.canvas-tool-island');
  await page.getByRole('button', { name: '创建文字' }).click();
  const createdNode = page.locator('.react-flow__node').first();
  const nodeBeforeHover = await createdNode.boundingBox();
  await createdNode.hover();
  const nodeAfterHover = await createdNode.boundingBox();
  expect(nodeBeforeHover).not.toBeNull();
  expect(nodeAfterHover).not.toBeNull();
  expect(Math.abs(nodeAfterHover!.width - nodeBeforeHover!.width)).toBeLessThan(1);
  expect(Math.abs(nodeAfterHover!.height - nodeBeforeHover!.height)).toBeLessThan(1);
  const handleBox = await page.locator('.node-handle').first().boundingBox();
  expect(handleBox).not.toBeNull();
  expect(handleBox!.width).toBeGreaterThanOrEqual(17);
  expect(handleBox!.height).toBeGreaterThanOrEqual(17);

  await page.getByRole('button', { name: '打开执行服务' }).click();
  await expect(page.locator('.chat-workspace')).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await assertInsideViewport(page, '.chat-workspace');
  await page.getByRole('button', { name: '收起面板' }).click();

  await page.getByRole('button', { name: '项目库' }).click();
  await expect(page.getByLabel('项目库抽屉')).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await assertInsideViewport(page, '.asset-drawer-panel');
  await page.getByRole('button', { name: '关闭项目库' }).click();

  await page.getByRole('button', { name: '生成记录' }).click();
  await expect(page.getByRole('heading', { name: '生成记录' })).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await assertInsideViewport(page, '.generation-records-panel');
});

test('fits the login page inside a phone viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await assertInsideViewport(page, '.login-card');
});
