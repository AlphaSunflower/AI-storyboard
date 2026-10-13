import { expect, test, type Page } from '@playwright/test';
import { showAllNodes } from './node-display';
import { prepareCanvasApp } from './support/canvasAuth';

const projectId = 'redraw-region-project';
const canvasId = 'redraw-region-canvas';

async function seedCanvas(page: Page, options: { seedream?: boolean } = {}) {
  let uploadSequence = 0;
  await prepareCanvasApp(page);
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/health') return route.fulfill({ json: { label: 'Test Executor' } });
    if (path === '/capabilities') {
      return route.fulfill({ json: [
        { id: 'image.redraw', label: '重绘', category: 'remote', available: true },
        { id: 'image.generate', label: '生图', category: 'remote', available: true },
      ] });
    }
    if (path === '/models') {
      if (options.seedream) {
        return route.fulfill({ json: [{
          value: 'doubao-seedream-4-5-251128',
          label: 'Seedream 4.5',
          default: 'true',
          params: JSON.stringify({
            sizes: ['1K', '2K', '4K'],
            sizeDefault: '2K',
            n: { min: 1, max: 15, default: 1 },
            spec: { modes: ['image-to-image'], request: [] },
          }),
        }] });
      }
      return route.fulfill({ status: 503, json: { message: 'gateway unavailable' } });
    }
    if (path === '/assets/upload' && request.method() === 'POST') {
      uploadSequence += 1;
      return route.fulfill({ json: {
        id: `asset-${uploadSequence}`,
        canvasId,
        type: 'image',
        mimeType: 'image/png',
        objectUrl: '/api/files/images/redraw-mask.png',
      } });
    }
    if (path === '/jobs' && request.method() === 'POST') {
      return route.fulfill({ json: { jobId: 'job-redraw-1' } });
    }
    if (path === '/jobs/job-redraw-1/events') {
      return route.fulfill({ headers: { 'content-type': 'text/event-stream' }, body: '' });
    }
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: '区域重绘测试' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : { schemaVersion: 2, nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 } };
      return route.fulfill({ json: { graph, revision: 1 } });
    }
    if (path === `/canvases/${canvasId}/assets` && request.method() === 'GET') {
      return route.fulfill({ json: [] });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.goto('/');
  await page.evaluate(async ({ projectId, canvasId }) => {
    const now = Date.now();
    const request = indexedDB.open('morrow-creative-canvas-v1', 5);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('legacyProjects')) db.createObjectStore('legacyProjects', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('canvases')) db.createObjectStore('canvases', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assets')) db.createObjectStore('assets', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('libraryEntries')) db.createObjectStore('libraryEntries', { keyPath: 'assetId' });
      if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings');
      if (!db.objectStoreNames.contains('assetMeta')) db.createObjectStore('assetMeta', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('assetVariants')) db.createObjectStore('assetVariants', { keyPath: ['assetId', 'variant'] });
      if (!db.objectStoreNames.contains('generationQueue')) db.createObjectStore('generationQueue', { keyPath: 'id' });
    };
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const transaction = db.transaction(['legacyProjects', 'canvases'], 'readwrite');
    transaction.objectStore('legacyProjects').put({ schemaVersion: 2, id: projectId, title: '区域重绘测试', canvases: [canvasId], createdAt: now, updatedAt: now });
    transaction.objectStore('canvases').put({ schemaVersion: 1, id: canvasId, projectId, title: '默认画布', nodes: [], edges: [], viewport: { x: 40, y: 40, zoom: 1 }, createdAt: now, updatedAt: now });
    await new Promise<void>((resolve, reject) => { transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error); });
    db.close();
  }, { projectId, canvasId });
  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);
}

test('creates an image-to-image node with a combined prompt and mask asset', async ({ page }) => {
  const requests: Array<{ url: string; method: string; body?: unknown }> = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/canvas/')) {
      requests.push({
        url: request.url(),
        method: request.method(),
        body: request.method() === 'POST' && request.headers()['content-type']?.includes('application/json')
          ? request.postDataJSON()
          : request.postData() ?? undefined,
      });
    }
  });
  await seedCanvas(page);
  await showAllNodes(page);
  const png = await page.evaluate(async () => {
    const canvas = document.createElement('canvas');
    canvas.width = 48;
    canvas.height = 36;
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#456';
    context.fillRect(0, 0, 48, 36);
    return [...new Uint8Array(await (await new Promise<Blob>((resolve) => canvas.toBlob((blob) => resolve(blob!), 'image/png'))).arrayBuffer())];
  });
  await page.locator('main.canvas-workspace input[type=file]').setInputFiles({
    name: 'room.png',
    mimeType: 'image/png',
    buffer: Buffer.from(png),
  });

  await page.getByTitle('重绘', { exact: true }).click();
  await expect(page.getByTitle('涂鸦')).toBeVisible();
  await expect(page.getByTitle('橡皮擦')).toHaveCount(0);
  const overlay = page.locator('.redraw-mask-overlay');
  const box = await overlay.boundingBox();
  expect(box).not.toBeNull();
  await page.getByTitle('涂鸦').click();
  await page.mouse.move(box!.x + box!.width * .18, box!.y + box!.height * .28);
  await page.mouse.down();
  await page.mouse.move(box!.x + box!.width * .48, box!.y + box!.height * .56, { steps: 5 });
  await page.mouse.up();
  await page.getByRole('textbox', { name: '修改 1 的意图' }).fill('放一个花盆');
  await page.getByTitle('确认修改意图').click();

  await page.getByTitle('框选').click();
  await page.mouse.move(box!.x + box!.width * .6, box!.y + box!.height * .16);
  await page.mouse.down();
  await page.mouse.move(box!.x + box!.width * .88, box!.y + box!.height * .44, { steps: 4 });
  await page.mouse.up();
  await page.getByRole('textbox', { name: '修改 2 的意图' }).fill('放一个沙发');
  await page.getByRole('button', { name: '颜色 #59d8ff' }).click();
  await page.getByTitle('确认修改意图').click();
  await expect(page.getByText('2 个区域')).toBeVisible();
  await page.getByTitle('选择区域').click();
  const secondRegion = page.locator('.redraw-region-mark').nth(1);
  await secondRegion.hover();
  await expect(secondRegion).toHaveClass(/is-hovered/);
  await secondRegion.click();
  await expect(page.getByRole('textbox', { name: '修改 2 的意图' })).toHaveValue('放一个沙发');
  await page.getByTitle('关闭').click();

  await page.getByTitle('执行重绘').click();
  const dialog = page.getByRole('dialog', { name: '执行重绘' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('combobox', { name: '模型' })).toHaveValue('gpt-image-2');
  await expect(dialog.getByText(/模型列表不可用/)).toHaveCount(0);
  await expect(dialog.getByText(/红色涂抹位置一放一个花盆/)).toBeVisible();
  await expect(dialog.getByText(/蓝色框选位置二放一个沙发/)).toBeVisible();
  await dialog.getByRole('button', { name: '执行重绘', exact: true }).click();
  await expect.poll(() => requests.some((request) => request.url.endsWith('/api/canvas/jobs') && request.method === 'POST')).toBe(true);
  const payload = requests.find((request) => request.url.endsWith('/api/canvas/jobs') && request.method === 'POST')!.body as {
    capabilityId: string;
    params: Record<string, unknown>;
  };

  expect(payload.capabilityId).toBe('image.redraw');
  expect(payload.params.redrawMaskAssetId).toBe('asset-2');
  expect(payload.params.prompt).not.toContain('@image#1');
  expect(payload.params.prompt).not.toContain('[参考图1]');
  expect(payload.params.prompt).toContain('红色涂抹位置一放一个花盆');
  expect(payload.params.prompt).toContain('蓝色框选位置二放一个沙发');
  expect(payload.params.prompt).not.toContain('x=');
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
  await expect(page.locator('.canvas-edge__line')).toHaveCount(1);
  await expect(page.getByText('room · 重绘')).toBeVisible();
  // 派生节点上的「参考图预览卡」当前不再渲染（多角度/重绘预览以对话框为准），
  // 先不在这里断言它，等重绘能力接入生图模型后再回来补。
  await expect(page.locator('.react-flow__node.selected')).toContainText('room');
});

test('creates a fixed-parameter Seedream multi-angle node', async ({ page }) => {
  const requests: Array<{ url: string; method: string; body?: unknown }> = [];
  page.on('request', (request) => {
    if (!request.url().includes('/api/canvas/') || request.method() !== 'POST') return;
    requests.push({
      url: request.url(),
      method: request.method(),
      body: request.headers()['content-type']?.includes('application/json')
        ? request.postDataJSON()
        : request.postData() ?? undefined,
    });
  });
  await seedCanvas(page, { seedream: true });
  await showAllNodes(page);
  const png = await page.evaluate(async () => {
    const canvas = document.createElement('canvas');
    canvas.width = 48;
    canvas.height = 36;
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#456';
    context.fillRect(0, 0, 48, 36);
    return [...new Uint8Array(await (await new Promise<Blob>((resolve) => canvas.toBlob((blob) => resolve(blob!), 'image/png'))).arrayBuffer())];
  });
  await page.locator('main.canvas-workspace input[type=file]').setInputFiles({
    name: 'character.png',
    mimeType: 'image/png',
    buffer: Buffer.from(png),
  });

  await page.getByTitle('多角度', { exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '多角度' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('Seedream 4.5')).toBeVisible();
  await expect(dialog.getByText('2K')).toBeVisible();
  await dialog.getByRole('button', { name: /多图/ }).click();
  await expect(dialog.getByText('4 张', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '生成多角度' }).click();

  await expect.poll(() => requests.some((request) => request.url.endsWith('/api/canvas/jobs'))).toBe(true);
  const payload = requests.find((request) => request.url.endsWith('/api/canvas/jobs'))!.body as {
    capabilityId: string;
    params: Record<string, unknown>;
  };

  expect(payload.capabilityId).toBe('image.redraw');
  expect(payload.params).toMatchObject({
    model: 'doubao-seedream-4-5-251128',
    size: '2K',
    n: 4,
    multiAngleMode: 'multi',
    mode: 'image-to-image',
  });
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
  await expect(page.locator('.canvas-edge__line')).toHaveCount(1);
  await expect(page.getByText('character · 多角度')).toBeVisible();
  // 节点上的内联多角度配置面板已取消：模型 / 尺寸 / 张数在对话框里已断言（见上），
  // 提交载荷里的 model/n/multiAngleMode 也已断言，这里不再重复。
});
