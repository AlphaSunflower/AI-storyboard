import { expect, test } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

test('keeps project, canvas, and project-library lists scrollable without page scrolling', async ({ page }) => {
  const projectId = 'scroll-project';
  const projects = Array.from({ length: 24 }, (_, index) => ({
    id: index === 0 ? projectId : `scroll-project-${index}`,
    title: `滚动项目 ${String(index + 1).padStart(2, '0')}`,
    canvasCount: 1,
    nodeCount: index,
    assetCount: index,
  }));
  const canvases = Array.from({ length: 24 }, (_, index) => ({
    id: `scroll-canvas-${index}`,
    projectId,
    title: `滚动画布 ${String(index + 1).padStart(2, '0')}`,
    nodeCount: index,
    assetCount: index,
  }));
  const libraryAssets = Array.from({ length: 24 }, (_, index) => ({
    id: `scroll-library-asset-${index}`,
    scope: 'PROJECT',
    ownerProjectId: projectId,
    type: 'IMAGE',
    aiKind: null,
    name: `项目库素材 ${String(index + 1).padStart(2, '0')}`,
    description: null,
    coverUrl: null,
    sourceType: 'canvas-generation',
    sourceId: null,
    status: 'READY',
  }));

  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: projects });
    }
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '滚动项目 01' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: canvases });
    }
    if (path === '/project-library' && request.method() === 'GET') {
      return route.fulfill({
        json: {
          projectTree: { scope: 'PROJECT', ownerProjectId: projectId, folders: [], unfiledAssetCount: libraryAssets.length },
          globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
          assets: libraryAssets,
          groups: [],
        },
      });
    }
    return route.fulfill({ json: {} });
  });
  await page.route('**/api/auth/refresh', (route) => route.fulfill({
    json: { data: { accessToken: 'scroll-token', userId: 'scroll-user' } },
  }));
  // 画布命令信箱已并入 canvas 服务，走同源 /api/canvas/bridge/**
  await page.route('**/api/canvas/bridge/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/canvas/bridge', '');
    if (path === '/health') return route.fulfill({ json: { ok: true, version: 1, projects: [] } });
    if (path === '/poll') return route.fulfill({ json: {} });
    return route.fulfill({ json: { ok: true } });
  });

  await page.goto('/');

  await expect(page.getByText('滚动项目 01')).toBeVisible();
  const projectListMetrics = await page.locator('.canvas-home__projects').evaluate((element) => ({
    overflowY: getComputedStyle(element).overflowY,
    scrollbarWidth: getComputedStyle(element, '::-webkit-scrollbar').width,
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
  }));
  expect(projectListMetrics.overflowY).toBe('auto');
  expect(projectListMetrics.scrollbarWidth).toBe('10px');
  expect(projectListMetrics.scrollHeight).toBeGreaterThan(projectListMetrics.clientHeight);
  expect(await page.locator('.canvas-home__projects').evaluate((element) => {
    element.scrollTop = 160;
    return element.scrollTop;
  })).toBeGreaterThan(0);
  expect(await page.locator('.canvas-home').evaluate((element) => getComputedStyle(element).overflowY)).toBe('hidden');

  await page.getByText('滚动项目 01').click();
  await expect(page.getByRole('heading', { name: '滚动项目 01' })).toBeVisible();

  const canvasListMetrics = await page.locator('.project-home__content').evaluate((element) => ({
    overflowY: getComputedStyle(element).overflowY,
    scrollbarWidth: getComputedStyle(element, '::-webkit-scrollbar').width,
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
  }));
  expect(canvasListMetrics.overflowY).toBe('auto');
  expect(canvasListMetrics.scrollbarWidth).toBe('10px');
  expect(canvasListMetrics.scrollHeight).toBeGreaterThan(canvasListMetrics.clientHeight);
  expect(await page.locator('.project-home__content').evaluate((element) => {
    element.scrollTop = 160;
    return element.scrollTop;
  })).toBeGreaterThan(0);
  expect(await page.locator('.project-home').evaluate((element) => getComputedStyle(element).overflowY)).toBe('hidden');

  await page.getByRole('button', { name: '项目库' }).click();
  await expect(page.getByRole('heading', { name: '项目库' })).toBeVisible();
  const libraryListMetrics = await page.locator('.project-home__content').evaluate((element) => ({
    overflowY: getComputedStyle(element).overflowY,
    scrollbarWidth: getComputedStyle(element, '::-webkit-scrollbar').width,
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
  }));
  expect(libraryListMetrics.overflowY).toBe('auto');
  expect(libraryListMetrics.scrollbarWidth).toBe('10px');
  expect(libraryListMetrics.scrollHeight).toBeGreaterThan(libraryListMetrics.clientHeight);
  expect(await page.locator('.project-home__content').evaluate((element) => {
    element.scrollTop = 160;
    return element.scrollTop;
  })).toBeGreaterThan(0);
});
