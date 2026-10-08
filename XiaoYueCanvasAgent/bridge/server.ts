import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { loadEnvFile } from 'node:process';
import { BRIDGE_DEFAULT_PORT } from '../src/canvas/bridgeProtocol.ts';
import { isBridgeOriginAllowed, parseBridgeOrigins } from '../src/canvas/bridgeOrigins.ts';
import type { BridgeRegisterPayload, BridgeResultPayload } from '../src/canvas/bridgeProtocol.ts';
import { CanvasBridgeCore } from './canvasBridgeCore.ts';
import { attachMcpEndpoint } from './mcpServer.ts';

for (const envFile of ['.env', '.env.local', '.env.development.local']) {
  try {
    loadEnvFile(envFile);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
      throw error;
    }
  }
}

const readJson = (req: IncomingMessage): Promise<Record<string, unknown>> => new Promise((resolve, reject) => {
  let raw = '';
  req.setEncoding('utf8');
  req.on('data', (chunk: string) => {
    raw += chunk;
    if (raw.length > 1_000_000) {
      reject(new Error('请求体过大'));
      req.destroy();
    }
  });
  req.on('end', () => {
    if (!raw) {
      resolve({});
      return;
    }
    try {
      resolve(JSON.parse(raw) as Record<string, unknown>);
    } catch {
      reject(new Error('请求体不是合法 JSON'));
    }
  });
  req.on('error', reject);
});

const allowedOrigins = parseBridgeOrigins(process.env.MORROW_BRIDGE_ORIGINS);
const bridgeToken = process.env.MORROW_BRIDGE_TOKEN?.trim() ?? '';

if (!bridgeToken) {
  console.error('[morrow-bridge] MORROW_BRIDGE_TOKEN 未配置；请设置环境变量，或将令牌写入 .env.development.local');
  process.exit(1);
}

const requestOrigin = (req: IncomingMessage) => {
  const origin = req.headers.origin;
  return typeof origin === 'string' ? origin : '';
};

const originAllowed = (req: IncomingMessage) => {
  const origin = requestOrigin(req);
  return isBridgeOriginAllowed(origin, allowedOrigins);
};

const tokenAllowed = (req: IncomingMessage) => {
  const raw = req.headers['x-bridge-token'];
  const token = Array.isArray(raw) ? raw[0] : raw;
  if (typeof token !== 'string') return false;
  const expected = Buffer.from(bridgeToken);
  const actual = Buffer.from(token);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
};

const sendJson = (req: IncomingMessage, res: ServerResponse, status: number, body: unknown) => {
  if (res.headersSent) return;
  const origin = requestOrigin(req);
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Mcp-Session-Id, MCP-Protocol-Version, X-Bridge-Token',
    Vary: 'Origin',
  };
  if (origin && allowedOrigins.includes(origin)) {
    headers['Access-Control-Allow-Origin'] = origin;
    headers['Access-Control-Allow-Credentials'] = 'true';
  }
  res.writeHead(status, {
    ...headers,
  });
  res.end(JSON.stringify(body));
};

const port = Number(process.env.MORROW_BRIDGE_PORT ?? BRIDGE_DEFAULT_PORT);
const core = new CanvasBridgeCore();

export const server = createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    if (!originAllowed(req)) {
      res.writeHead(403);
      res.end();
      return;
    }
    const origin = requestOrigin(req);
    res.writeHead(204, {
      ...(origin ? { 'Access-Control-Allow-Origin': origin } : {}),
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Mcp-Session-Id, MCP-Protocol-Version, X-Bridge-Token',
      Vary: 'Origin',
    });
    res.end();
    return;
  }

  const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
  const path = url.pathname;

  try {
    if (!originAllowed(req)) {
      sendJson(req, res, 403, { ok: false, error: { code: 'ORIGIN_DENIED', message: '来源不被允许' } });
      return;
    }
    // MCP 继续只允许本机回环客户端；HTTP 管理接口必须携带 Bridge Token。
    if (path !== '/mcp' && !tokenAllowed(req)) {
      sendJson(req, res, 403, { ok: false, error: { code: 'BRIDGE_TOKEN_INVALID', message: '桥接令牌无效' } });
      return;
    }
    if (path === '/mcp') {
      await attachMcpEndpoint(core, req, res);
      return;
    }

    if (req.method === 'GET' && path === '/health') {
      sendJson(req, res, 200, core.health());
      return;
    }

    if (req.method === 'GET' && path === '/projects') {
      sendJson(req, res, 200, core.projects());
      return;
    }

    if (req.method === 'POST' && path === '/register') {
      const body = (await readJson(req)) as unknown as BridgeRegisterPayload;
      const result = core.register(body);
      sendJson(req, res, result.ok ? 200 : 400, result);
      return;
    }

    if (req.method === 'POST' && path === '/unregister') {
      const body = await readJson(req);
      if (typeof body.tabId === 'string') core.unregister(body.tabId);
      sendJson(req, res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && path === '/poll') {
      sendJson(req, res, 200, core.poll(url.searchParams.get('tabId') ?? ''));
      return;
    }

    if (req.method === 'POST' && path === '/result') {
      const body = (await readJson(req)) as unknown as BridgeResultPayload;
      core.result(body);
      sendJson(req, res, 200, { ok: true });
      return;
    }

    if (req.method === 'POST' && path === '/command') {
      const body = await readJson(req);
      const response = await core.command({
        source: body.source === 'mcp' || body.source === 'agent' || body.source === 'cli' ? body.source : 'cli',
        projectId: typeof body.projectId === 'string' ? body.projectId : undefined,
        canvasId: typeof body.canvasId === 'string' ? body.canvasId : undefined,
        method: typeof body.method === 'string' ? body.method : '',
        params: body.params && typeof body.params === 'object' && !Array.isArray(body.params)
          ? body.params as Record<string, unknown>
          : {},
        dryRun: body.dryRun === true,
        expectedRevision: typeof body.expectedRevision === 'number' ? body.expectedRevision : undefined,
      });
      sendJson(req, res, 200, response);
      return;
    }

    sendJson(req, res, 404, { ok: false, error: { code: 'NOT_FOUND', message: `未知端点 ${req.method} ${path}` } });
  } catch (error) {
    sendJson(req, res, 500, { ok: false, error: { code: 'INTERNAL', message: error instanceof Error ? error.message : '内部错误' } });
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`[morrow-bridge] listening on http://127.0.0.1:${port}`);
  console.log(`[morrow-bridge] MCP endpoint: http://127.0.0.1:${port}/mcp`);
});
