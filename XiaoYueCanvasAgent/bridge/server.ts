import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { BRIDGE_DEFAULT_PORT } from '../src/canvas/bridgeProtocol.ts';
import type { BridgeRegisterPayload, BridgeResultPayload } from '../src/canvas/bridgeProtocol.ts';
import { CanvasBridgeCore } from './canvasBridgeCore.ts';
import { attachMcpEndpoint } from './mcpServer.ts';

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

const sendJson = (res: ServerResponse, status: number, body: unknown) => {
  if (res.headersSent) return;
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Mcp-Session-Id, MCP-Protocol-Version',
  });
  res.end(JSON.stringify(body));
};

const port = Number(process.env.MORROW_BRIDGE_PORT ?? BRIDGE_DEFAULT_PORT);
const core = new CanvasBridgeCore();

export const server = createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Mcp-Session-Id, MCP-Protocol-Version',
    });
    res.end();
    return;
  }

  const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
  const path = url.pathname;

  try {
    if (path === '/mcp') {
      await attachMcpEndpoint(core, req, res);
      return;
    }

    if (req.method === 'GET' && path === '/health') {
      sendJson(res, 200, core.health());
      return;
    }

    if (req.method === 'GET' && path === '/projects') {
      sendJson(res, 200, core.projects());
      return;
    }

    if (req.method === 'POST' && path === '/register') {
      const body = (await readJson(req)) as unknown as BridgeRegisterPayload;
      const result = core.register(body);
      sendJson(res, result.ok ? 200 : 400, result);
      return;
    }

    if (req.method === 'POST' && path === '/unregister') {
      const body = await readJson(req);
      if (typeof body.tabId === 'string') core.unregister(body.tabId);
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && path === '/poll') {
      sendJson(res, 200, core.poll(url.searchParams.get('tabId') ?? ''));
      return;
    }

    if (req.method === 'POST' && path === '/result') {
      const body = (await readJson(req)) as unknown as BridgeResultPayload;
      core.result(body);
      sendJson(res, 200, { ok: true });
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
  console.log(`[morrow-bridge] MCP endpoint: http://127.0.0.1:${port}/mcp`);
});
