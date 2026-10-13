import { expect, test, type BrowserContext, type Page, type Route } from '@playwright/test';
import { mockApiFallback } from './support/canvasAuth';

test.beforeEach(async ({ page }) => { await mockApiFallback(page); });

// 端到端验证浏览器画布信箱：canvas 服务（/api/canvas/bridge）投递命令，
// 浏览器轮询领取、在页面内执行、把结果回传。命令执行层仍在前端。

interface BridgeCommand {
  requestId: string;
  method: string;
  params: Record<string, unknown>;
  dryRun?: boolean;
  /** 只投给指定标签（模拟服务端的活跃标签选择），省略表示谁先轮询谁拿。 */
  forTab?: string;
}

interface BridgeMockState {
  registered: string[];
  unregistered: string[];
  results: Array<Record<string, unknown>>;
  commands: BridgeCommand[];
  healthFailures: number;
}

const emptyState = (): BridgeMockState => ({
  registered: [],
  unregistered: [],
  results: [],
  commands: [],
  healthFailures: 0,
});

const jsonBody = (route: Route) => route.request().postDataJSON() as Record<string, unknown>;
const query = (route: Route, key: string) => new URL(route.request().url()).searchParams.get(key) ?? '';

const mockBridge = async (page: Page, state: BridgeMockState) => {
  await page.route('**/api/canvas/bridge/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas/bridge', '');
    const method = request.method();

    if (path === '/health' && method === 'GET') {
      if (state.healthFailures > 0) {
        state.healthFailures -= 1;
        return route.fulfill({ status: 503, json: { message: 'not ready' } });
      }
      return route.fulfill({ json: { ok: true, version: 1, projects: [] } });
    }
    if (path === '/register' && method === 'POST') {
      state.registered.push(String(jsonBody(route).tabId));
      return route.fulfill({ json: { ok: true } });
    }
    if (path === '/unregister' && method === 'POST') {
      state.unregistered.push(String(jsonBody(route).tabId));
      return route.fulfill({ json: { ok: true } });
    }
    if (path === '/poll' && method === 'GET') {
      const tabId = query(route, 'tabId');
      const index = state.commands.findIndex((entry) => !entry.forTab || entry.forTab === tabId);
      if (index < 0) return route.fulfill({ json: {} });
      const [command] = state.commands.splice(index, 1);
      const { forTab: _forTab, ...payload } = command;
      return route.fulfill({ json: { command: payload } });
    }
    if (path === '/result' && method === 'POST') {
      state.results.push(jsonBody(route));
      return route.fulfill({ json: { ok: true } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${method} ${path}` } });
  });
};

const emptyGraph = {
  schemaVersion: 2,
  nodes: [],
  edges: [],
  viewport: { x: 0, y: 0, zoom: 1 },
  generationRecords: [],
  executions: [],
};

const mockCanvasApi = async (page: Page) => {
  await page.route('**/api/canvas/**', (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    const method = request.method();
    if (path === '/projects' && method === 'GET') return route.fulfill({ json: [] });
    if (path === '/projects' && method === 'POST') {
      return route.fulfill({ json: { id: 'e2e-project', title: 'Untitled' } });
    }
    if (/^\/projects\/[^/]+$/.test(path) && method === 'GET') {
      return route.fulfill({ json: { id: 'e2e-project', title: 'Untitled' } });
    }
    if (/^\/projects\/[^/]+$/.test(path) && method === 'PATCH') {
      return route.fulfill({ json: { id: 'e2e-project', title: 'Untitled' } });
    }
    if (/^\/projects\/[^/]+\/canvases$/.test(path) && method === 'GET') {
      return route.fulfill({ json: [] });
    }
    if (/^\/projects\/[^/]+\/canvases$/.test(path) && method === 'POST') {
      return route.fulfill({ json: { id: 'e2e-canvas', projectId: 'e2e-project', title: '画布' } });
    }
    if (/^\/canvases\/[^/]+$/.test(path) && method === 'PATCH') {
      return route.fulfill({ json: { id: 'e2e-canvas', projectId: 'e2e-project', title: '画布' } });
    }
    if (/^\/canvases\/[^/]+\/graph$/.test(path) && method === 'GET') {
      return route.fulfill({ json: { graph: emptyGraph, revision: 0 } });
    }
    if (/^\/canvases\/[^/]+\/graph$/.test(path) && method === 'PUT') {
      const body = request.postDataJSON() as { graph?: unknown };
      return route.fulfill({ json: { graph: body.graph, revision: 1 } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${method} ${path}` } });
  });
};

const mockAuth = async (page: Page) => {
  await page.route('**/api/auth/refresh', (route) => route.fulfill({
    json: { data: { accessToken: 'header.payload.signature', userId: 'test-user' } },
  }));
};

const openCanvas = async (page: Page) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click({ force: true });
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();
  await expect.poll(() => page.locator('.react-flow__node').count()).toBe(0);
};

const recordBridgeReadyEvents = async (page: Page) => {
  await page.addInitScript(() => {
    (window as unknown as { __bridgeReady: unknown[] }).__bridgeReady = [];
    window.addEventListener('morrow:canvas-bridge-ready', (event) => {
      (window as unknown as { __bridgeReady: unknown[] }).__bridgeReady.push(
        (event as CustomEvent).detail);
    });
  });
};

const bridgeReadyCount = (page: Page) =>
  page.evaluate(() => (window as unknown as { __bridgeReady: unknown[] }).__bridgeReady?.length ?? 0);

const setupPage = async (context: BrowserContext, state: BridgeMockState) => {
  const page = await context.newPage();
  await mockAuth(page);
  await mockCanvasApi(page);
  await mockBridge(page, state);
  return page;
};

test.beforeEach(async ({ page }) => {
  await mockAuth(page);
});

test('浏览器向服务端注册标签页并回传命令执行结果', async ({ page }) => {
  const state = emptyState();
  state.commands.push({
    requestId: 'req-1',
    method: 'canvas.get_summary',
    params: {},
    dryRun: false,
  });
  await mockCanvasApi(page);
  await mockBridge(page, state);

  await page.goto('/');
  await openCanvas(page);

  await expect.poll(() => state.registered.length, { timeout: 15_000 }).toBeGreaterThan(0);
  await expect.poll(() => state.results.length, { timeout: 15_000 }).toBeGreaterThan(0);

  const result = state.results[0] as { requestId?: string; response?: Record<string, unknown> };
  expect(result.requestId).toBe('req-1');
  expect(result.response).toMatchObject({ ok: true, id: 'req-1' });
  expect((result.response?.result as { nodeCount?: number }).nodeCount).toBe(0);
});

test('信箱投递的写命令真的落到画布上（可见结果 + 回传 revision）', async ({ page }) => {
  const state = emptyState();
  state.commands.push({
    requestId: 'req-create',
    method: 'canvas.node.create_text',
    params: { label: '信箱创建的文字', position: { x: 420, y: 300 } },
  });
  await mockCanvasApi(page);
  await mockBridge(page, state);

  await page.goto('/');
  await openCanvas(page);

  await expect(page.locator('.react-flow__node')).toHaveCount(1, { timeout: 15_000 });
  await expect(page.getByText('信箱创建的文字')).toBeVisible();
  await expect.poll(() => state.results.length, { timeout: 15_000 }).toBeGreaterThan(0);
  expect((state.results[0] as { response?: Record<string, unknown> }).response)
    .toMatchObject({ ok: true, status: 'applied' });
});

test('执行类命令被服务端强制预览：页面不产生落库变更', async ({ page }) => {
  const state = emptyState();
  state.commands.push({
    requestId: 'req-preview',
    method: 'canvas.graph.execute',
    // 服务端对执行/删除类命令强制 dryRun，轮询载荷里带的就是 true
    params: {},
    dryRun: true,
  });
  await mockCanvasApi(page);
  await mockBridge(page, state);

  await page.goto('/');
  await openCanvas(page);

  await expect.poll(() => state.results.length, { timeout: 15_000 }).toBeGreaterThan(0);
  const result = state.results[0] as { response?: Record<string, unknown> };
  expect(result.response).toMatchObject({ ok: true, status: 'preview' });
  await expect(page.locator('.react-flow__node')).toHaveCount(0);
});

test('同画布多标签：命令只由被投递的那个标签执行', async ({ browser }) => {
  const state = emptyState();
  const context = await browser.newContext();
  try {
    const first = await setupPage(context, state);
    await first.goto('/');
    await openCanvas(first);
    await expect.poll(() => state.registered.length, { timeout: 15_000 }).toBe(1);

    const second = await setupPage(context, state);
    await second.goto('/');
    await openCanvas(second);
    await expect.poll(() => state.registered.length, { timeout: 15_000 }).toBe(2);

    const activeTab = state.registered[1];
    state.commands.push({
      requestId: 'req-tab-2',
      method: 'canvas.node.create_text',
      params: { label: '只给活跃标签' },
      forTab: activeTab,
    });

    await expect(second.locator('.react-flow__node')).toHaveCount(1, { timeout: 15_000 });
    await expect(first.locator('.react-flow__node')).toHaveCount(0);
    expect((state.results[0] as { response?: Record<string, unknown> }).response)
      .toMatchObject({ ok: true });
  } finally {
    await context.close();
  }
});

test('跨用户隔离：一个用户的命令不会驱动另一个用户的画布', async ({ browser }) => {
  const stateA = emptyState();
  const stateB = emptyState();
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  try {
    const pageA = await setupPage(contextA, stateA);
    const pageB = await setupPage(contextB, stateB);
    await pageA.goto('/');
    await pageB.goto('/');
    await openCanvas(pageA);
    await openCanvas(pageB);
    await expect.poll(() => stateA.registered.length, { timeout: 15_000 }).toBe(1);
    await expect.poll(() => stateB.registered.length, { timeout: 15_000 }).toBe(1);

    stateA.commands.push({
      requestId: 'req-user-a',
      method: 'canvas.node.create_text',
      params: { label: 'A 的节点' },
    });

    await expect(pageA.locator('.react-flow__node')).toHaveCount(1, { timeout: 15_000 });
    await expect(pageB.locator('.react-flow__node')).toHaveCount(0);
    expect(stateB.commands).toHaveLength(0);
    expect(stateB.results).toHaveLength(0);
  } finally {
    await contextA.close();
    await contextB.close();
  }
});

test('就绪后派发 canvas-bridge-ready，供外部命令层等待画布可用', async ({ page }) => {
  const state = emptyState();
  await recordBridgeReadyEvents(page);
  await mockCanvasApi(page);
  await mockBridge(page, state);

  await page.goto('/');
  await openCanvas(page);

  await expect.poll(() => bridgeReadyCount(page), { timeout: 15_000 }).toBeGreaterThan(0);
  const detail = await page.evaluate(() => (window as unknown as {
    __bridgeReady: Array<{ projectId?: string; canvasId?: string }>;
  }).__bridgeReady[0]);
  expect(detail.projectId).toBeTruthy();
  expect(detail.canvasId).toBeTruthy();
});

test('画布服务未就绪时不注册，恢复后自动重连', async ({ page }) => {
  const state = emptyState();
  state.healthFailures = 2;
  await mockCanvasApi(page);
  await mockBridge(page, state);

  await page.goto('/');
  await openCanvas(page);

  await expect.poll(() => state.registered.length, { timeout: 20_000 }).toBeGreaterThan(0);
});

