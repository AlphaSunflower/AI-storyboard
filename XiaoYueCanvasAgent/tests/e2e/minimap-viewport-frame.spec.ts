import { expect, test, type Page } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

/**
 * 小地图要能看出「我现在能看到画布哪一块」——即当前视口的白色取景框。
 *
 * 回归背景：styles.css 里曾有一句
 *   `.react-flow__minimap.canvas-minimap .react-flow__minimap-mask { stroke-width: 1.25 }`
 * 小地图 SVG 的 viewBox 用的是流坐标，1 流单位在屏幕上只有 1/viewScale px（节点铺开几屏时
 * viewScale 能到 15）。React Flow 传下来的 --xy-minimap-mask-stroke-width-props 已经乘过
 * viewScale，是屏幕像素；那句裸的 1.25 会被当成流单位，实际渲染成 0.08px，取景框就消失了。
 *
 * 所以这里断言的是「换算到屏幕上的实际粗细」，不是 stroke-width 的数字本身——
 * 数字看着正常、实际看不见，正是这个 bug 的形态。
 */
test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

const seedCanvas = async (page: Page) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ json: { data: { accessToken: 'test-token', userId: 'test-user' } } }));
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') return route.fulfill({ json: [{ id: 'mm-project', title: '小地图' }] });
    if (path === '/projects/mm-project') return route.fulfill({ json: { id: 'mm-project', title: '小地图' } });
    if (path === '/projects/mm-project/canvases') return route.fulfill({ json: [{ id: 'mm-canvas', projectId: 'mm-project', title: '默认画布' }] });
    if (path === '/canvases/mm-canvas/graph') {
      const graph = { schemaVersion: 1, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };
      return route.fulfill({ json: request.method() === 'GET' ? { graph, revision: 1 } : { graph, revision: 2 } });
    }
    return route.fulfill({ json: {} });
  });

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
    transaction.objectStore('legacyProjects').put({ schemaVersion: 2, id: 'mm-project', title: '小地图', canvases: ['mm-canvas'], createdAt: now, updatedAt: now });
    // 节点铺得很开：流坐标跨度越大 viewScale 越大，取景框越容易被「按流单位算」的 stroke-width 吃掉。
    transaction.objectStore('canvases').put({
      schemaVersion: 1, id: 'mm-canvas', projectId: 'mm-project', title: '默认画布',
      nodes: [
        { id: 'm1', type: 'text', position: { x: 0, y: 0 }, width: 320, height: 240, data: { kind: 'text', label: 'A', prompt: 'A', status: 'idle', accent: '#d7ff54' } },
        { id: 'm2', type: 'text', position: { x: 2400, y: 1500 }, width: 320, height: 240, data: { kind: 'text', label: 'B', prompt: 'B', status: 'idle', accent: '#d7ff54' } },
      ],
      edges: [], viewport: { x: 0, y: 0, zoom: 1 }, createdAt: now, updatedAt: now,
    });
    await new Promise<void>((resolve, reject) => { transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error); });
    db.close();
  });
  await page.goto('/?token=test-token&userId=test-user#project=mm-project&canvas=mm-canvas');
  await page.locator('.canvas-workspace').waitFor();
  await page.waitForTimeout(500);
};

/** 打开小地图并读出取景框换算到屏幕上的实际粗细。 */
const frameMetrics = async (page: Page) => {
  await page.getByLabel('小地图').first().click();
  await page.locator('.react-flow__minimap-mask').waitFor();
  await page.waitForTimeout(400);
  return page.evaluate(() => {
    const svg = document.querySelector('.react-flow__minimap-svg') as SVGSVGElement | null;
    const mask = document.querySelector('.react-flow__minimap-mask') as SVGPathElement | null;
    const minimap = document.querySelector('.react-flow__minimap') as HTMLElement | null;
    if (!svg || !mask || !minimap) throw new Error('小地图元素缺失');
    const viewBoxWidth = Number(svg.getAttribute('viewBox')!.split(/\s+/)[2]);
    const box = minimap.getBoundingClientRect();
    const strokeWidthViewBoxUnits = parseFloat(getComputedStyle(mask).strokeWidth);
    return {
      // viewBox 是流单位：1 流单位 = 1/(viewBoxWidth/宽度) px
      strokeEffectivePx: strokeWidthViewBoxUnits / (viewBoxWidth / box.width),
      strokeColor: getComputedStyle(mask).stroke,
      // 取景框 = 掩码路径的第二个子路径（evenodd 挖出的洞）
      subpaths: (mask.getAttribute('d') ?? '').split('M').filter((part) => part.trim()).length,
    };
  });
};

test('小地图画出当前视口的取景框，且粗细在屏幕上看得见', async ({ page }) => {
  await seedCanvas(page);
  const metrics = await frameMetrics(page);

  // 掩码 = 外框 + 视口洞，两个子路径；只有一个说明取景框没画出来。
  expect(metrics.subpaths).toBe(2);
  expect(metrics.strokeColor).not.toBe('none');

  // 关键断言：换算到屏幕后的实际粗细。写死 viewBox 单位会得到 ~0.08px（看不见）。
  expect(metrics.strokeEffectivePx).toBeGreaterThan(0.75);
  expect(metrics.strokeEffectivePx).toBeLessThan(3);

  await page.locator('.react-flow__minimap').screenshot({ path: 'test-results/minimap-viewport-frame.png' });
});
