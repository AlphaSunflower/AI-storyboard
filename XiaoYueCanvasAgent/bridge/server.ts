import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';
import { BRIDGE_PROTOCOL_VERSION, BRIDGE_COMMAND_TIMEOUT_MS, BRIDGE_DEFAULT_PORT } from '../src/canvas/bridgeProtocol.ts';
import type { BridgeHealth, BridgeRegisterPayload, BridgeResultPayload, CanvasBridgeResponse } from '../src/canvas/bridgeProtocol.ts';

// 常驻 Bridge 进程：浏览器打开项目后注册当前标签页，CLI/MCP 通过 HTTP
// 提交命令，Bridge 把命令转发给对应标签页，浏览器执行命令层后回传结果。
// 只监听 127.0.0.1，不做任何鉴权（纯内网本地开发场景）。

interface PendingCommand {
  requestId: string;
  method: string;
  params: Record<string, unknown>;
  dryRun?: boolean;
  expectedRevision?: number;
  finish: (response: CanvasBridgeResponse) => void;
}

interface TabState {
  tabId: string;
  projectId: string;
  lastSeen: number;
}

const tabs = new Map<string, TabState>();
const projectTab = new Map<string, string>();
const pendingByTab = new Map<string, PendingCommand[]>();
const pendingById = new Map<string, PendingCommand>();

const port = Number(process.env.MORROW_BRIDGE_PORT ?? BRIDGE_DEFAULT_PORT);

const readJson = (req: IncomingMessage): Promise<Record<string, unknown>> => new Promise((resolve, reject) => {
  let raw = '';
  req.setEncoding('utf8');
  req.on('data', (chunk: string) => {
    raw += chunk;
    if (raw.length > 1_000_000) { reject(new Error('请求体过大')); req.destroy(); }
  });
  req.on('end', () => {
    if (!raw) { resolve({}); return; }
    try { resolve(JSON.parse(raw) as Record<string, unknown>); } catch { reject(new Error('请求体不是合法 JSON')); }
  });
  req.on('error', reject);
});

const sendJson = (res: ServerResponse, status: number, body: unknown) => {
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  });
  res.end(JSON.stringify(body));
};

const resolveTab = (projectId: unknown): string | { code: string; message: string } => {
  if (typeof projectId === 'string' && projectId) {
    const tabId = projectTab.get(projectId);
    if (!tabId) return { code: 'PROJECT_NOT_OPEN', message: '目标项目当前未在浏览器中打开' };
    return tabId;
  }
  const entries = [...projectTab.entries()];
  if (entries.length === 0) return { code: 'PROJECT_NOT_OPEN', message: '没有打开的项目' };
  if (entries.length > 1) return { code: 'AMBIGUOUS_PROJECT', message: '多个项目打开，请用 --project 指定' };
  return entries[0][1];
};

const dispatchCommand = (tabId: string, method: unknown, params: unknown, dryRun: unknown, expectedRevision: unknown): Promise<CanvasBridgeResponse> => {
  const requestId = randomUUID();
  return new Promise((resolve) => {
    const state: { settled: boolean; timer?: ReturnType<typeof setTimeout> } = { settled: false };
    const finish = (response: CanvasBridgeResponse) => {
      if (state.settled) return;
      state.settled = true;
      if (state.timer !== undefined) clearTimeout(state.timer);
      pendingById.delete(requestId);
      resolve(response);
    };
    const command: PendingCommand = {
      requestId,
      method: typeof method === 'string' ? method : '',
      params: (params && typeof params === 'object' && !Array.isArray(params) ? params : {}) as Record<string, unknown>,
      dryRun: dryRun === true,
      expectedRevision: typeof expectedRevision === 'number' ? expectedRevision : undefined,
      finish,
    };
    pendingById.set(requestId, command);
    const queue = pendingByTab.get(tabId) ?? [];
    queue.push(command);
    pendingByTab.set(tabId, queue);
    state.timer = setTimeout(() => finish({ version: 1, id: requestId, ok: false, error: { code: 'BRIDGE_TIMEOUT', message: '浏览器未在超时时间内响应命令' } }), BRIDGE_COMMAND_TIMEOUT_MS);
  });
};

const server = createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    });
    res.end();
    return;
  }

  const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
  const path = url.pathname;

  try {
    if (req.method === 'GET' && path === '/health') {
      const projects: BridgeHealth['projects'] = [...projectTab.keys()].map((projectId) => ({
        projectId,
        lastSeen: tabs.get(projectTab.get(projectId) ?? '')?.lastSeen ?? 0,
      }));
      sendJson(res, 200, { ok: true, version: BRIDGE_PROTOCOL_VERSION, projects });
      return;
    }

    if (req.method === 'GET' && path === '/projects') {
      sendJson(res, 200, [...projectTab.keys()]);
      return;
    }

    if (req.method === 'POST' && path === '/register') {
      const body = (await readJson(req)) as unknown as BridgeRegisterPayload;
      if (!body.tabId || !body.projectId) { sendJson(res, 400, { ok: false, error: { code: 'INVALID_ARGUMENTS', message: '缺少 tabId 或 projectId' } }); return; }
      tabs.set(body.tabId, { tabId: body.tabId, projectId: body.projectId, lastSeen: Date.now() });
      projectTab.set(body.projectId, body.tabId);
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && path === '/poll') {
      const tabId = url.searchParams.get('tabId') ?? '';
      const tab = tabs.get(tabId);
      if (tab) tab.lastSeen = Date.now();
      const queue = pendingByTab.get(tabId) ?? [];
      const command = queue.shift();
      if (command) pendingByTab.set(tabId, queue);
      else pendingByTab.delete(tabId);
      sendJson(res, 200, command ? { command: { requestId: command.requestId, method: command.method, params: command.params, dryRun: command.dryRun, expectedRevision: command.expectedRevision } } : {});
      return;
    }

    if (req.method === 'POST' && path === '/result') {
      const body = (await readJson(req)) as unknown as BridgeResultPayload;
      const pending = body.requestId ? pendingById.get(body.requestId) : undefined;
      if (pending) pending.finish(body.response);
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'POST' && path === '/command') {
      const body = await readJson(req);
      const tabId = resolveTab(body.projectId);
      if (typeof tabId !== 'string') {
        sendJson(res, 200, { version: 1, id: '', ok: false, error: tabId });
        return;
      }
      const response = await dispatchCommand(tabId, body.method, body.params, body.dryRun, body.expectedRevision);
      sendJson(res, 200, response);
      return;
    }

    sendJson(res, 404, { ok: false, error: { code: 'NOT_FOUND', message: `未知端点 ${req.method} ${path}` } });
  } catch (error) {
    sendJson(res, 500, { ok: false, error: { code: 'INTERNAL', message: error instanceof Error ? error.message : '内部错误' } });
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`[morrow-bridge] listening on http://127.0.0.1:${port}`);
});
