import { vi } from 'vitest';

/**
 * 全局 fetch mock：node/jsdom 无后端，而 projectStorage 的 createProject / createCanvas /
 * saveCanvas / loadCanvas 会 fetch('/api/canvas/...')。这里按路径返回确定性假响应，
 * 让 IndexedDB 业务逻辑（asset 管理 / 迁移 / 打包）能在测试中真实执行。
 * 返回形态对齐 morrowApi.apiJson 的解析约定（读 .ok + .json()）。
 *
 * 用内存 state 让 POST 创建的项目/画布能被后续 GET 回显（migration 测试依赖此往返）。
 */
export function installFetchMock() {
  // key: 原始 title → 生成的 id；画布同理
  const projects = new Map<string, { id: string; title: string }>();
  const canvases = new Map<string, { id: string; projectId: string; title: string }>();
  let projectSeq = 0;
  let canvasSeq = 0;

  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? 'GET').toUpperCase();

    // 创建项目 → {id, title}
    if (/\/api\/canvas\/projects$/.test(url) && method === 'POST') {
      const title = JSON.parse(String(init?.body ?? '{}')).title ?? 'Untitled';
      const id = `proj-${++projectSeq}`;
      projects.set(id, { id, title });
      return jsonResponse({ id, title });
    }
    // 项目列表 → 内存集合
    if (/\/api\/canvas\/projects$/.test(url) && method === 'GET') {
      return jsonResponse([...projects.values()]);
    }
    // 单个项目 → {id, title}（loadProject）
    if (/\/api\/canvas\/projects\/[^/]+$/.test(url) && method === 'GET') {
      const id = url.match(/\/projects\/([^/]+)$/)?.[1] ?? 'remote-project';
      const p = projects.get(id);
      return p ? jsonResponse(p) : jsonResponse({ id, title: 'Untitled' });
    }
    // 项目删除
    if (/\/api\/canvas\/projects\/[^/]+$/.test(url) && method === 'DELETE') {
      const id = url.match(/\/projects\/([^/]+)$/)?.[1] ?? '';
      projects.delete(id);
      return jsonResponse({ ok: true });
    }
    // 建画布 → {id, projectId, title}
    if (/\/projects\/[^/]+\/canvases$/.test(url) && method === 'POST') {
      const projectId = url.match(/\/projects\/([^/]+)\/canvases/)?.[1] ?? '';
      const title = JSON.parse(String(init?.body ?? '{}')).title ?? '画布';
      const id = `canvas-${++canvasSeq}`;
      canvases.set(id, { id, projectId, title });
      return jsonResponse({ id, projectId, title });
    }
    // 项目下画布列表
    if (/\/projects\/[^/]+\/canvases$/.test(url) && method === 'GET') {
      const projectId = url.match(/\/projects\/([^/]+)\/canvases/)?.[1] ?? '';
      return jsonResponse([...canvases.values()].filter((c) => c.projectId === projectId));
    }
    // 画布图读取 → 空图（或记忆的）
    if (/\/canvases\/[^/]+\/graph$/.test(url) && method === 'GET') {
      return jsonResponse({ graph: { nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } }, revision: 0 });
    }
    // 画布图保存 → 回显 revision 1
    if (/\/canvases\/[^/]+\/graph$/.test(url) && method === 'PUT') {
      const body = JSON.parse(String(init?.body ?? '{}'));
      return jsonResponse({ graph: body.graph ?? { nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } }, revision: (body.baseRevision ?? 0) + 1 });
    }
    // 画布重命名 / 元数据 PATCH
    if (/\/canvases\/[^/]+$/.test(url) && method === 'PATCH') {
      return jsonResponse({ ok: true });
    }
    // 单独画布 GET
    if (/\/canvases\/[^/]+$/.test(url) && method === 'GET') {
      const id = url.match(/\/canvases\/([^/]+)$/)?.[1] ?? '';
      const c = canvases.get(id);
      return c ? jsonResponse(c) : jsonResponse({ id, projectId: '', title: '画布' });
    }
    // 画布删除
    if (/\/canvases\/[^/]+$/.test(url) && method === 'DELETE') {
      const id = url.match(/\/canvases\/([^/]+)$/)?.[1] ?? '';
      canvases.delete(id);
      return jsonResponse({ ok: true });
    }

    // 未匹配路径：返回 404 JSON（apiJson 会抛 HTTP 错误）
    return jsonResponse({ code: 40401, message: 'not mocked: ' + url }, 404);
  });
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as Response;
}
