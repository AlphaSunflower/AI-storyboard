import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

// 1x1 PNG：用来验证画布卡片真的把封面图渲染出来了。
const COVER_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

test('shows the first canvas image as the canvas list cover', async ({ page }) => {
  const projectId = 'cover-project';
  const projects = [{ id: projectId, title: '咕咕嘎嘎', canvasCount: 2, nodeCount: 4, assetCount: 2 }];
  const canvases = [
    { id: 'canvas-with-cover', projectId, title: '画布 17', nodeCount: 2, assetCount: 1, coverAssetId: 'cover-asset-1' },
    { id: 'canvas-without-cover', projectId, title: '空画布', nodeCount: 0, assetCount: 0, coverAssetId: null },
  ];

  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: projects });
    }
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '咕咕嘎嘎' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: canvases });
    }
    if (path === '/assets/cover-asset-1' && request.method() === 'GET') {
      return route.fulfill({ body: COVER_PNG, contentType: 'image/png' });
    }
    return route.fulfill({ json: {} });
  });
  await page.route('**/api/auth/refresh', (route) => route.fulfill({
    json: { data: { accessToken: 'cover-token', userId: 'cover-user' } },
  }));
  // 画布命令信箱已并入 canvas 服务，走同源 /api/canvas/bridge/**
  await page.route('**/api/canvas/bridge/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/canvas/bridge', '');
    if (path === '/health') return route.fulfill({ json: { ok: true, version: 1, projects: [] } });
    if (path === '/poll') return route.fulfill({ json: {} });
    return route.fulfill({ json: { ok: true } });
  });

  await page.goto('/');
  await page.getByText('咕咕嘎嘎').click();
  await expect(page.getByRole('heading', { name: '咕咕嘎嘎' })).toBeVisible();

  const covers = page.locator('.project-home__cover-image');
  await expect(covers).toHaveCount(1);
  await expect(covers.first()).toHaveJSProperty('naturalWidth', 1);
  await expect(page.locator('.project-home__cover')).toHaveCount(2);
  await expect(page.getByText('空画布')).toBeVisible();
});
