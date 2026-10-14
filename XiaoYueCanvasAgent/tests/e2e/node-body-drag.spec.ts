import { expect, test, type Locator, type Page } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

/**
 * 节点正文拖动。
 *
 * 回归背景：CanvasWorkspace 曾经给每个节点强加 `dragHandle: '.node-drag-handle'`，
 * 而带这个类的只有卡片上方那条外置标题。于是「按下图片/视频/文字节点的正文拖动」
 * 全部失效——只有标题条能挪节点。这几条用例就是钉住这个行为：
 *
 * - image / video / text 恒为展开态（ALWAYS_EXPANDED_NODE_KINDS），走的一定是完整渲染器；
 * - 生成节点默认折叠，要先点一下展开，再拖它的预览框（.gen-preview 曾挂 .nodrag，
 *   而 .media-node.has-surface .gen-node 内边距为 0，导致生成节点整块正文都拖不动）。
 *
 * 这些断言在 jsdom 里做不了：需要真实布局 + 真实指针事件序列。
 */
test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

const seedCanvas = async (page: Page) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/auth/stream-ticket', (route) =>
    route.fulfill({ json: { data: { ticket: 'test-ticket' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') return route.fulfill({ json: [{ id: 'drag-project', title: '拖动' }] });
    if (path === '/projects/drag-project') return route.fulfill({ json: { id: 'drag-project', title: '拖动' } });
    if (path === '/projects/drag-project/canvases') return route.fulfill({ json: [{ id: 'drag-canvas', projectId: 'drag-project', title: '默认画布' }] });
    if (path === '/canvases/drag-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    if (path === '/canvases/drag-canvas' && request.method() === 'PATCH') return route.fulfill({ json: { id: 'drag-canvas', title: '默认画布' } });
    return route.fulfill({ json: {} });
  });

  // 视口放宽到 1920：Morrow 聊天抽屉是 62vw 的右侧浮层，1440 时它盖住 x>547 的画布，
  // 正好压在这些用例要按下的节点上（按下会被 iframe 吃掉，拖动自然不生效）。
  await page.setViewportSize({ width: 1920, height: 900 });
  await page.goto('/?token=test-token&userId=test-user');
  await page.evaluate(async () => {
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
      schemaVersion: 2, id: 'drag-project', title: '拖动', canvases: ['drag-canvas'], createdAt: now, updatedAt: now,
    });
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'drag-canvas', projectId: 'drag-project', title: '默认画布',
      // 全部节点落在 x < 730 的可见画布区域内（1920 视口下聊天抽屉从 x≈730 起）。
      nodes: [
        { id: 'n-image', type: 'image', position: { x: 40, y: 60 }, width: 320, height: 240, data: { kind: 'image', label: '图片', status: 'idle', accent: '#8f9cff' } },
        { id: 'n-video', type: 'video', position: { x: 40, y: 340 }, width: 320, height: 240, data: { kind: 'video', label: '视频', status: 'idle', accent: '#8f9cff' } },
        { id: 'n-text', type: 'text', position: { x: 40, y: 620 }, width: 320, height: 240, data: { kind: 'text', label: '文字', prompt: '初始正文', status: 'idle', accent: '#d7ff54' } },
        { id: 'n-gen', type: 'imageGeneration', position: { x: 400, y: 60 }, width: 330, height: 300, data: { kind: 'imageGeneration', label: '图片生成', prompt: '', status: 'idle', accent: '#8f9cff' } },
      ],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=drag-project&canvas=drag-canvas');
  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await page.waitForTimeout(400);
};

/** 在目标元素中心按下、移动、松手——即用户所说的「长按节点拖动」。 */
const pressDrag = async (page: Page, target: Locator, dx: number, dy: number) => {
  const box = await target.boundingBox();
  if (!box) throw new Error('目标不可见，无法拖动');
  const startX = box.x + box.width / 2;
  const startY = box.y + box.height / 2;

  // 按住前先确认这个点真的落在目标上。画布上叠着聊天抽屉等浮层，
  // 一旦按到浮层上，拖动会静默失效、看起来像「拖动功能坏了」——必须报错而不是放过去。
  const covered = await target.evaluate(
    (el, point) => {
      const hit = document.elementFromPoint(point.x, point.y);
      return !hit || !(hit === el || el.contains(hit));
    },
    { x: startX, y: startY },
  );
  if (covered) throw new Error(`按下点 (${Math.round(startX)}, ${Math.round(startY)}) 被其它浮层挡住`);

  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX + dx, startY + dy, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(120);
};

const nodeBox = async (page: Page, id: string) => {
  const box = await page.locator(`.react-flow__node[data-id="${id}"]`).boundingBox();
  if (!box) throw new Error(`节点 ${id} 不可见`);
  return box;
};

/** 拖完断言「确实按预期方向挪了」，而不是笼统地「变了」。 */
const expectMovedBy = (before: { x: number; y: number }, after: { x: number; y: number }, dx: number, dy: number) => {
  expect(after.x - before.x).toBeGreaterThan(dx * 0.6);
  expect(after.x - before.x).toBeLessThan(dx * 1.4);
  if (dy === 0) expect(Math.abs(after.y - before.y)).toBeLessThan(12);
};

test('按下图片节点正文就能拖动', async ({ page }) => {
  await seedCanvas(page);
  const before = await nodeBox(page, 'n-image');

  await pressDrag(page, page.locator('.react-flow__node[data-id="n-image"] .media-node-view'), 120, 0);

  expectMovedBy(before, await nodeBox(page, 'n-image'), 120, 0);
});

test('按下视频节点正文就能拖动', async ({ page }) => {
  await seedCanvas(page);
  const before = await nodeBox(page, 'n-video');

  await pressDrag(page, page.locator('.react-flow__node[data-id="n-video"] .video-node-card'), 120, 0);

  expectMovedBy(before, await nodeBox(page, 'n-video'), 120, 0);
});

test('按下文字节点正文就能拖动', async ({ page }) => {
  await seedCanvas(page);
  const before = await nodeBox(page, 'n-text');

  await pressDrag(page, page.locator('.react-flow__node[data-id="n-text"] .text-node-preview'), 120, 0);

  expectMovedBy(before, await nodeBox(page, 'n-text'), 120, 0);
});

test('文字节点默认是静态预览面，双击才进编辑并写回正文', async ({ page }) => {
  await seedCanvas(page);
  const node = page.locator('.react-flow__node[data-id="n-text"]');

  // 默认不是输入框——否则整块正文都会因为 .nodrag 失去拖动能力。
  await expect(node.locator('.text-node-preview')).toHaveText('初始正文');
  await expect(node.locator('.text-node-editor')).toHaveCount(0);

  await node.locator('.text-node-preview').dblclick();
  const editor = node.locator('.text-node-editor');
  await expect(editor).toBeVisible();

  await editor.fill('改过的正文');
  // 点画布空白处失焦，退出编辑（同样避开聊天抽屉覆盖的右半屏）
  await page.mouse.click(620, 830);
  await expect(node.locator('.text-node-preview')).toHaveText('改过的正文');
});

test('按下生成节点预览框就能拖动（预览面不再挂 .nodrag）', async ({ page }) => {
  await seedCanvas(page);
  const node = page.locator('.react-flow__node[data-id="n-gen"]');

  // 生成节点默认折叠，点一下展开成完整渲染器。
  await node.click();
  const preview = node.locator('.gen-preview');
  await expect(preview).toBeVisible();
  await expect(preview).not.toHaveClass(/nodrag/);

  const before = await nodeBox(page, 'n-gen');
  await pressDrag(page, preview, 120, 0);

  expectMovedBy(before, await nodeBox(page, 'n-gen'), 120, 0);
});
