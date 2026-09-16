import { spawn, type ChildProcess } from 'node:child_process';
import { expect, test } from '@playwright/test';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';

// 端到端验证 CLI/MCP 通过 Bridge 读写浏览器画布。
// 测试自管 Bridge 生命周期：beforeAll 启动，afterAll 关闭。

const BRIDGE_PORT = 8787;
const BRIDGE_URL = `http://127.0.0.1:${BRIDGE_PORT}`;

let bridge: ChildProcess | null = null;

const waitForBridge = async () => {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const response = await fetch(`${BRIDGE_URL}/health`);
      if (response.ok) return;
    } catch {
      // 继续重试
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('Bridge 未在超时时间内启动');
};

const sendCommand = (projectId: string, method: string, params: Record<string, unknown>, canvasId?: string) =>
  fetch(`${BRIDGE_URL}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ version: 1, id: crypto.randomUUID(), source: 'cli', projectId, canvasId, method, params }),
  }).then((response) => response.json());

test.beforeAll(async () => {
  bridge = spawn(process.execPath, ['bridge/server.ts'], {
    cwd: process.cwd(),
    stdio: 'ignore',
    env: { ...process.env, MORROW_BRIDGE_PORT: String(BRIDGE_PORT) },
  });
  await waitForBridge();
});

test.afterAll(async () => {
  if (!bridge?.pid) return;
  const pid = bridge.pid;
  try { bridge.kill(); } catch { /* ignore */ }
  // Windows 上 child.kill() 可能留孤儿进程，用 taskkill 兜底强杀。
  if (process.platform === 'win32') {
    await new Promise<void>((resolve) => {
      const killer = spawn('taskkill', ['/F', '/T', '/PID', String(pid)], { stdio: 'ignore' });
      killer.on('exit', () => resolve());
      killer.on('error', () => resolve());
    });
  }
});

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await page.evaluate(async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('morrow-creative-canvas-v1', 4);
      request.onsuccess = () => {
        const db = request.result;
        const stores = Array.from(db.objectStoreNames);
        if (!stores.length) { db.close(); resolve(); return; }
        const transaction = db.transaction(stores, 'readwrite');
        stores.forEach((store) => transaction.objectStore(store).clear());
        transaction.oncomplete = () => { db.close(); resolve(); };
        transaction.onerror = () => reject(transaction.error);
      };
      request.onerror = () => reject(request.error);
    });
    localStorage.clear(); sessionStorage.clear();
  });
  await page.reload();
});

test('CLI 通过 Bridge 读取并写入浏览器画布', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();

  const projectId = await page.evaluate(() => new URLSearchParams(location.hash.slice(1)).get('project'));
  const canvasId = await page.evaluate(() => new URLSearchParams(location.hash.slice(1)).get('canvas'));
  expect(projectId).toBeTruthy();
  expect(canvasId).toBeTruthy();

  // 等待浏览器标签页注册到 Bridge。
  await expect.poll(async () => fetch(`${BRIDGE_URL}/health`).then((r) => r.json()), { timeout: 5000 }).toMatchObject({
    projects: expect.arrayContaining([expect.objectContaining({
      projectId,
      projectTitle: 'Untitled',
      canvasId,
      canvasTitle: '画布 1',
    })]),
  });

  // 读：summary
  const summary = await sendCommand(projectId!, 'canvas.get_summary', {}, canvasId!);
  expect(summary.ok).toBe(true);
  expect(summary.result.nodeCount).toBe(0);

  // 写：创建文字节点
  const created = await sendCommand(projectId!, 'canvas.node.create_text', { label: 'CLI 创建的文字', position: { x: 420, y: 300 } }, canvasId!);
  expect(created.ok).toBe(true);
  expect(created.status).toBe('applied');

  await expect(page.locator('.react-flow__node')).toHaveCount(1);
  await expect(page.getByRole('textbox', { name: '请输入标题' })).toHaveValue('CLI 创建的文字');

  // 再读：节点数应变为 1
  const afterCreate = await sendCommand(projectId!, 'canvas.get_summary', {}, canvasId!);
  expect(afterCreate.result.nodeCount).toBe(1);

  // 未打开的项目应返回 PROJECT_NOT_OPEN
  const missing = await sendCommand('missing-project', 'canvas.get_summary', {});
  expect(missing.ok).toBe(false);
  expect(missing.error.code).toBe('PROJECT_NOT_OPEN');
});

test('MCP 通过 Streamable HTTP 创建节点并连接浏览器画布', async ({ page }) => {
  const projectId = 'mcp-bridge-project';
  const canvasId = 'mcp-bridge-canvas';
  await page.route('**/api/canvas/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    if (path === '/projects' && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: projectId, title: 'MCP 联调' }] });
    }
    if (path === `/projects/${projectId}` && request.method() === 'GET') {
      return route.fulfill({ json: { id: projectId, title: 'MCP 联调' } });
    }
    if (path === `/projects/${projectId}/canvases` && request.method() === 'GET') {
      return route.fulfill({ json: [{ id: canvasId, projectId, title: '默认画布' }] });
    }
    if (path === `/canvases/${canvasId}/graph`) {
      const graph = request.method() === 'PUT'
        ? (request.postDataJSON() as { graph: unknown }).graph
        : {
            schemaVersion: 2,
            nodes: [{ id: 'mcp-placeholder', type: 'text', position: { x: 80, y: 80 }, data: { kind: 'text', label: '占位', prompt: '', status: 'idle', accent: '#fff' } }],
            edges: [],
            viewport: { x: 40, y: 40, zoom: 1 },
            generationRecords: [],
            executions: [],
          };
      return route.fulfill({ json: { graph, revision: 1 } });
    }
    if (path === `/canvases/${canvasId}` && request.method() === 'PATCH') {
      return route.fulfill({ json: { id: canvasId, projectId, title: '默认画布' } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${request.method()} ${path}` } });
  });

  await page.goto(`/?token=test-token&userId=test-user#project=${projectId}&canvas=${canvasId}`);
  await expect(page.locator('.canvas-workspace')).toBeVisible();
  await expect.poll(async () => fetch(`${BRIDGE_URL}/health`).then((r) => r.json()), { timeout: 5000 }).toMatchObject({
    projects: expect.arrayContaining([expect.objectContaining({
      projectId,
      projectTitle: 'MCP 联调',
      canvasId,
      canvasTitle: '默认画布',
    })]),
  });

  const client = new Client({ name: 'bridge-e2e', version: '1.0.0' });
  await client.connect(new StreamableHTTPClientTransport(new URL(`${BRIDGE_URL}/mcp`)));
  try {
    const tools = await client.listTools();
    expect(tools.tools.map((tool) => tool.name)).toEqual(expect.arrayContaining([
      'canvas_get_context',
      'canvas_create_node',
      'canvas_create_edge',
      'canvas_execute_graph',
    ]));

    const context = await client.callTool({ name: 'canvas_get_context', arguments: {} });
    const contextText = context.content.find((item) => item.type === 'text')?.text ?? '';
    expect(JSON.parse(contextText)).toMatchObject({
      projectId,
      projectTitle: 'MCP 联调',
      canvasId,
      canvasTitle: '默认画布',
    });
    const browserContext = await client.callTool({ name: 'canvas_get_browser_context', arguments: {} });
    expect(JSON.parse(browserContext.content.find((item) => item.type === 'text')?.text ?? '{}')).toMatchObject({ projectId, canvasId });

    const script = await client.callTool({
      name: 'canvas_create_node',
      arguments: { projectId, canvasId, kind: 'script', label: 'MCP 剧本', data: { scriptText: '第一幕。' } },
    });
    expect(script.isError, script.content.find((item) => item.type === 'text')?.text).not.toBe(true);
    const scriptId = JSON.parse(script.content.find((item) => item.type === 'text')?.text ?? '{}').nodeId;
    expect(scriptId).toBeTruthy();

    const planner = await client.callTool({
      name: 'canvas_create_node',
      arguments: { projectId, canvasId, kind: 'storyboardPlanner', label: 'MCP 分镜拆解' },
    });
    const plannerId = JSON.parse(planner.content.find((item) => item.type === 'text')?.text ?? '{}').nodeId;
    await client.callTool({
      name: 'canvas_create_edge',
      arguments: { projectId, canvasId, source: scriptId, target: plannerId },
    });

    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  } finally {
    await client.close();
  }
});

test('语义操作落成因果链：裁剪→切分，边带标签，节点可展开血缘', async ({ page }) => {
  await page.getByRole('button', { name: '新建项目', exact: true }).click();
  await page.getByRole('button', { name: '新建第一个画布' }).click();
  await expect(page.getByText('空画布', { exact: true })).toBeVisible();
  const projectId = await page.evaluate(() => new URLSearchParams(location.hash.slice(1)).get('project'));
  const canvasId = await page.evaluate(() => new URLSearchParams(location.hash.slice(1)).get('canvas'));
  expect(projectId).toBeTruthy();
  await expect.poll(async () => fetch(`${BRIDGE_URL}/health`).then((r) => r.json()), { timeout: 5000 }).toMatchObject({
    projects: expect.arrayContaining([expect.objectContaining({ projectId, canvasId })]),
  });

  // 上传一张真实 PNG
  const png = await page.evaluate(async () => {
    const canvas = document.createElement('canvas'); canvas.width = 40; canvas.height = 40;
    const ctx = canvas.getContext('2d')!; ctx.fillStyle = '#ff775f'; ctx.fillRect(0, 0, 40, 40);
    ctx.fillStyle = '#d7ff54'; ctx.fillRect(4, 4, 12, 8);
    return [...new Uint8Array(await (await new Promise<Blob>((resolve) => canvas.toBlob((blob) => resolve(blob!), 'image/png'))).arrayBuffer())];
  });
  await page.locator('main.canvas-workspace input[type=file]').setInputFiles({ name: 'src.png', mimeType: 'image/png', buffer: Buffer.from(png) });
  await expect(page.locator('.react-flow__node')).toHaveCount(1);

  const list = await sendCommand(projectId!, 'canvas.list_nodes', {}, canvasId!);
  const sourceNodeId = list.result[0].id;

  // 裁剪 → 1 条"裁剪"边
  const crop = await sendCommand(projectId!, 'canvas.image.crop', { nodeId: sourceNodeId, crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 } }, canvasId!);
  expect(crop.ok).toBe(true);
  await expect(page.locator('.react-flow__node')).toHaveCount(2);
  await expect(page.locator('.canvas-edge__label')).toHaveCount(1);
  await expect(page.locator('.canvas-edge__label')).toHaveText('裁剪');

  // 对裁剪产物切分 2×2 → 4 条"切分"边
  const split = await sendCommand(projectId!, 'canvas.image.split', { nodeId: crop.result.nodeIds[0], rows: 2, columns: 2 }, canvasId!);
  expect(split.ok).toBe(true);
  await expect(page.locator('.react-flow__node')).toHaveCount(6);
  await expect(page.locator('.canvas-edge__label')).toHaveCount(5);
  await expect(page.locator('.canvas-edge__label', { hasText: '裁剪' })).toHaveCount(1);
  await expect(page.locator('.canvas-edge__label', { hasText: '切分' })).toHaveCount(4);

  // 详情面板展示完整操作记录与血缘链
  await page.locator('.react-flow__node').last().click();
  await page.getByRole('button', { name: '画布设置' }).click();
  await expect(page.getByText('操作记录')).toBeVisible();
  await expect(page.getByText('血缘链')).toBeVisible();
});
