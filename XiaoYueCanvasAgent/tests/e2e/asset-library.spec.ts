import { expect, test, type Page } from '@playwright/test';
import { prepareCanvasApp } from './support/canvasAuth';

const projectId = 'asset-library-project';
const canvasId = 'asset-library-canvas';
const onePixelPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
);

const seedCanvas = async (page: Page) => {
  await page.goto('/?token=test-token&userId=test-user');
  await page.evaluate(async ({ projectId, canvasId }) => {
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
      schemaVersion: 2,
      id: projectId,
      title: '项目库测试',
      canvases: [canvasId],
      createdAt: now,
      updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1,
      id: canvasId,
      projectId,
      title: '项目库画布',
      nodes: [],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      createdAt: now,
      updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  }, { projectId, canvasId });
};

const mockCanvasApi = async (page: Page) => {
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace('/api/canvas', '');
    const method = request.method();
    const json = (body: unknown) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });

    if (path === '/projects' && method === 'GET') {
      return json([{ id: projectId, title: '项目库测试' }]);
    }
    if (path === `/projects/${projectId}` && method === 'GET') {
      return json({ id: projectId, title: '项目库测试' });
    }
    if (path === `/projects/${projectId}/canvases` && method === 'GET') {
      return json([{ id: canvasId, projectId, title: '项目库画布' }]);
    }
    if (path === `/canvases/${canvasId}/graph` && method === 'GET') {
      return json({
        graph: { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 }, generationRecords: [] },
        revision: 0,
      });
    }
    if (path === '/project-library' && method === 'GET') {
      const folderId = url.searchParams.get('folderId');
      if (folderId === 'project-folder') {
        return json({
          projectTree: {
            scope: 'PROJECT',
            ownerProjectId: projectId,
            folders: [{
              id: 'project-folder',
              parentId: null,
              name: '项目参考',
              scope: 'PROJECT',
              ownerProjectId: projectId,
              directAssetCount: 1,
              descendantAssetCount: 1,
              version: 1,
            }],
            unfiledAssetCount: 0,
          },
          globalTree: { scope: 'GLOBAL', ownerProjectId: null, folders: [], unfiledAssetCount: 0 },
          assets: [{
            id: 'folder-asset',
            scope: 'PROJECT',
            ownerProjectId: projectId,
            type: 'IMAGE',
            aiKind: null,
            name: '文件夹内素材',
            description: null,
            coverUrl: null,
            sourceType: 'canvas-upload',
            sourceId: null,
            status: 'ACTIVE',
          }],
          groups: [],
        });
      }
      return json({
        projectTree: {
          scope: 'PROJECT',
          ownerProjectId: projectId,
          folders: [{
            id: 'project-folder',
            parentId: null,
            name: '项目参考',
            scope: 'PROJECT',
            ownerProjectId: projectId,
            directAssetCount: 1,
            descendantAssetCount: 1,
            version: 1,
          }],
          unfiledAssetCount: 1,
        },
        globalTree: {
          scope: 'GLOBAL',
          ownerProjectId: null,
          folders: [{
            id: 'global-folder',
            parentId: null,
            name: '全局参考',
            scope: 'GLOBAL',
            ownerProjectId: null,
            directAssetCount: 1,
            descendantAssetCount: 1,
            version: 1,
          }],
          unfiledAssetCount: 1,
        },
        assets: [
          {
            id: 'canvas-asset',
            scope: 'PROJECT',
            ownerProjectId: projectId,
            type: 'IMAGE',
            aiKind: null,
            name: '项目画布成品',
            description: null,
            coverUrl: null,
            sourceType: 'canvas-generation',
            sourceId: null,
            status: 'ACTIVE',
          },
          {
            id: 'global-asset',
            scope: 'GLOBAL',
            ownerProjectId: null,
            type: 'VIDEO',
            aiKind: null,
            name: '全局共享素材',
            description: null,
            coverUrl: null,
            sourceType: 'upload',
            sourceId: null,
            status: 'ACTIVE',
          },
        ],
        groups: [{
          id: 'global-group',
          scope: 'GLOBAL',
          ownerProjectId: null,
          type: 'GROUP',
          aiKind: 'SCENE',
          name: '全局场景资产集',
          description: null,
          coverUrl: null,
          sourceType: null,
          sourceId: null,
          status: 'ACTIVE',
        }],
      });
    }
    if (path === '/project-library/import' && method === 'POST') {
      return json({
        id: 'canvas-canvas-asset',
        canvasId,
        type: 'image',
        mimeType: 'image/png',
        objectUrl: '/api/files/images/canvas-asset.png',
        libraryAssetId: 'canvas-asset',
        librarySyncState: 'ready',
      });
    }
    if (path === '/project-library/import-group' && method === 'POST') {
      return json([
        {
          id: 'canvas-group-image-1',
          canvasId,
          type: 'image',
          mimeType: 'image/png',
          objectUrl: '/api/files/images/group-image-1.png',
          libraryAssetId: 'group-image-1',
          librarySyncState: 'ready',
        },
        {
          id: 'canvas-group-image-2',
          canvasId,
          type: 'image',
          mimeType: 'image/png',
          objectUrl: '/api/files/images/group-image-2.png',
          libraryAssetId: 'group-image-2',
          librarySyncState: 'ready',
        },
      ]);
    }
    if (path.startsWith('/assets/') && method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'image/png',
        body: onePixelPng,
      });
    }
    if (method === 'PUT') {
      return json({ graph: JSON.parse(request.postData() ?? '{}').graph, revision: 1 });
    }
    if (method === 'PATCH' || method === 'DELETE') return json({ ok: true });
    return json({ code: 40401, message: `not mocked: ${method} ${path}` });
  });
};

test.beforeEach(async ({ page }) => {
  await prepareCanvasApp(page);
});

test('shows one project library containing folders, canvas assets, global assets, and asset sets', async ({ page }) => {
  await mockCanvasApi(page);
  await seedCanvas(page);
  await page.goto('/?token=test-token&userId=test-user');

  await page.locator('.canvas-home__grid article').first().getByRole('button').first().click();
  await page.getByRole('button', { name: '项目库', exact: true }).click();

  await expect(page.getByRole('heading', { name: '项目库', exact: true })).toBeVisible();
  await expect(page.getByText('项目参考')).toBeVisible();
  await expect(page.getByText('全局参考')).toBeVisible();
  await expect(page.getByText('项目画布成品')).toBeVisible();
  await expect(page.getByText('全局共享素材')).toBeVisible();
  await expect(page.getByText('全局场景资产集')).toBeVisible();

  await page.getByRole('button', { name: '项目画布素材' }).click();
  await expect(page.getByText('项目画布成品')).toBeVisible();
  await expect(page.getByText('全局共享素材')).toHaveCount(0);
  await expect(page.getByText('全局场景资产集')).toHaveCount(0);
});

test('canvas project library drawer opens folders and groups without leaving the canvas', async ({ page }) => {
  await mockCanvasApi(page);
  await seedCanvas(page);
  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);

  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await page.getByRole('button', { name: '项目库' }).click();
  await expect(page.getByLabel('项目库抽屉')).toBeVisible();
  await expect(page.getByText('项目参考')).toBeVisible();
  await expect(page.getByText('全局参考')).toBeVisible();
  await expect(page.getByText('项目画布成品')).toBeVisible();
  await expect(page.getByText('全局场景资产集')).toBeVisible();

  await page.getByRole('button', { name: '资产集', exact: true }).click();
  await expect(page.getByText('全局场景资产集')).toBeVisible();
  await expect(page.getByText('项目画布成品')).toHaveCount(0);
  await expect(page.getByTitle('双击打开文件夹')).toHaveCount(2);

  await page.getByRole('button', { name: '全部', exact: true }).click();
  await page.getByTitle('双击打开文件夹').first().dblclick();
  await expect(page.getByText('文件夹内素材')).toBeVisible();

  await page.getByRole('button', { name: '关闭项目库' }).click();
  await expect(page.getByLabel('项目库抽屉')).toHaveCount(0);
});

test('canvas accepts image and asset set drags from the project library drawer', async ({ page }) => {
  await mockCanvasApi(page);
  await seedCanvas(page);
  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);

  await page.getByRole('button', { name: '项目库' }).click();
  await expect(page.getByText('项目画布成品')).toBeVisible();
  const dragAccepted = async (label: string) => page.evaluate((sourceLabel) => {
    const source = [...document.querySelectorAll<HTMLElement>('[draggable="true"]')]
      .find((element) => element.textContent?.includes(sourceLabel));
    const workspace = document.querySelector<HTMLElement>('.canvas-workspace');
    if (!source || !workspace) return { found: false, accepted: false };
    const transfer = new DataTransfer();
    source.dispatchEvent(new DragEvent('dragstart', {
      bubbles: true,
      cancelable: true,
      dataTransfer: transfer,
    }));
    const dragover = new DragEvent('dragover', {
      bubbles: true,
      cancelable: true,
      dataTransfer: transfer,
    });
    workspace.dispatchEvent(dragover);
    return { found: true, accepted: dragover.defaultPrevented };
  }, label);

  expect(await dragAccepted('项目画布成品')).toEqual({ found: true, accepted: true });
  expect(await dragAccepted('全局共享素材')).toEqual({ found: true, accepted: true });

  await page.getByRole('button', { name: '资产集', exact: true }).click();
  expect(await dragAccepted('全局场景资产集')).toEqual({ found: true, accepted: true });
});

test('dropping an image or asset set creates one canvas node per imported file', async ({ page }) => {
  await mockCanvasApi(page);
  await seedCanvas(page);
  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);

  await page.getByRole('button', { name: '项目库' }).click();
  await expect(page.getByText('项目画布成品')).toBeVisible();
  const dropLibraryItem = async (label: string) => page.evaluate((sourceLabel) => {
    const source = [...document.querySelectorAll<HTMLElement>('[draggable="true"]')]
      .find((element) => element.textContent?.includes(sourceLabel));
    const workspace = document.querySelector<HTMLElement>('.canvas-workspace');
    if (!source || !workspace) return false;
    const transfer = new DataTransfer();
    source.dispatchEvent(new DragEvent('dragstart', {
      bubbles: true,
      cancelable: true,
      dataTransfer: transfer,
    }));
    const rect = workspace.getBoundingClientRect();
    const drop = new DragEvent('drop', {
      bubbles: true,
      cancelable: true,
      clientX: rect.left + 520,
      clientY: rect.top + 360,
      dataTransfer: transfer,
    });
    workspace.dispatchEvent(drop);
    return drop.defaultPrevented;
  }, label);

  expect(await dropLibraryItem('项目画布成品')).toBe(true);
  await expect(page.locator('.react-flow__node')).toHaveCount(1);

  await page.getByRole('button', { name: '资产集', exact: true }).click();
  expect(await dropLibraryItem('全局场景资产集')).toBe(true);
  await expect(page.locator('.react-flow__node')).toHaveCount(3);
});
