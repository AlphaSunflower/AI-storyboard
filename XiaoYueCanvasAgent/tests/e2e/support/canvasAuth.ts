import { expect, type Page } from '@playwright/test';

// 画布 App 的登录门控：非内嵌走 POST /api/auth/refresh（同源 cookie），
// 内嵌（iframe）由宿主页面 postMessage 注入 token。e2e 里分别用这两个入口造登录态。

export const E2E_CANVAS_TOKEN = 'e2e-canvas-token';
export const E2E_CANVAS_USER_ID = 'e2e-user';

/** 让非内嵌画布页在启动时拿到登录态（App 挂载后会自己调 /api/auth/refresh）。 */
export const mockCanvasAuth = async (page: Page) => {
  await page.route('**/api/auth/refresh', (route) => route.fulfill({
    json: { data: { accessToken: E2E_CANVAS_TOKEN, userId: E2E_CANVAS_USER_ID } },
  }));
};

/**
 * 兜底后端：未被子用例覆盖的 /api/canvas/** 由内存假后端应答。
 *
 * e2e 不能让请求漏到宿主：本机/CI 上可能有真实网关在 8080，假 token 会被判 401，
 * 画布 App 收到 401 会清 token 回登录页，用例就会莫名失败。
 * 必须最先注册——Playwright 后注册的路由优先，子用例自己的 mock 会覆盖它。
 */
export const mockCanvasBackendFallback = async (page: Page) => {
  // 有状态的内存假后端：项目/画布/图都记住，列表接口返回真实结果，
  // 否则"新建画布 → 返回项目中心看到列表"这类用例拿不到数据。
  const projects = new Map<string, { id: string; title: string; updatedAt: string }>();
  const canvases = new Map<string, Array<{ id: string; projectId: string; title: string; updatedAt: string }>>();
  const graphs = new Map<string, { graph: unknown; revision: number }>();
  let projectSeq = 0;
  let canvasSeq = 0;
  let assetSeq = 0;
  const now = () => new Date().toISOString();
  // 后端 id 形如 32 位十六进制，画布 App 用它判断"是否已迁移到后端项目"。
  const backendId = (seq: number) => seq.toString(16).padStart(32, '0');

  await page.route((url) => url.pathname.startsWith('/api/canvas/'), (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/canvas', '');
    const method = request.method();
    const body = (() => {
      try {
        return (request.postDataJSON() ?? {}) as Record<string, unknown>;
      } catch {
        return {} as Record<string, unknown>;
      }
    })();

    // 画布素材上传（multipart）：按表单里的 type/canvasId 造一条真实形状的资产记录
    if (path === '/assets/upload' && method === 'POST') {
      assetSeq += 1;
      const multipart = request.postDataBuffer()?.toString('utf8') ?? '';
      const field = (name: string) =>
        new RegExp(`name="${name}"\\r?\\n\\r?\\n([^\\r\\n]+)`).exec(multipart)?.[1] ?? '';
      const type = field('type') || 'image';
      const folder = type === 'video' ? 'videos' : type === 'audio' ? 'audios' : 'images';
      const filename = /filename="([^"]+)"/.exec(multipart)?.[1] ?? `e2e-asset-${assetSeq}.png`;
      return route.fulfill({ json: {
        id: `e2e-asset-${assetSeq}`,
        canvasId: field('canvasId'),
        producerNodeId: field('producerNodeId'),
        type,
        name: filename,
        mimeType: type === 'video' ? 'video/mp4' : type === 'audio' ? 'audio/mpeg' : 'image/png',
        objectUrl: `/api/files/${folder}/${filename}`,
        width: 24,
        height: 18,
      } });
    }
    if (path === '/projects' && method === 'GET') return route.fulfill({ json: [...projects.values()] });
    // 未接入远程执行服务：capabilities 返回空数组，App 用静态 REMOTE_CAPABILITIES（全部 unavailable）
    if (path === '/capabilities' && method === 'GET') return route.fulfill({ json: [] });
    if (path === '/projects' && method === 'POST') {
      projectSeq += 1;
      const project = {
        id: backendId(projectSeq),
        title: String(body.title ?? 'Untitled'),
        updatedAt: now(),
      };
      projects.set(project.id, project);
      return route.fulfill({ json: project });
    }
    if (/^\/projects\/[^/]+$/.test(path) && method === 'GET') {
      const projectId = path.split('/')[2];
      const project = projects.get(projectId);
      if (project) return route.fulfill({ json: project });
      // 后端不知道的项目按真实行为回 404：画布 App 会回落到本地 IndexedDB 里的项目副本。
      return route.fulfill({ status: 404, json: { message: 'project not found' } });
    }
    if (/^\/projects\/[^/]+\/canvases$/.test(path) && method === 'GET') {
      return route.fulfill({ json: canvases.get(path.split('/')[2]) ?? [] });
    }
    if (/^\/projects\/[^/]+\/canvases$/.test(path) && method === 'POST') {
      const projectId = path.split('/')[2];
      canvasSeq += 1;
      const canvas = {
        id: backendId(1000 + canvasSeq),
        projectId,
        title: String(body.title ?? '画布'),
        updatedAt: now(),
      };
      canvases.set(projectId, [...(canvases.get(projectId) ?? []), canvas]);
      return route.fulfill({ json: canvas });
    }
    if (/^\/canvases\/[^/]+\/graph$/.test(path)) {
      const canvasId = path.split('/')[2];
      if (method === 'PUT') {
        const next = { graph: body.graph, revision: (graphs.get(canvasId)?.revision ?? 0) + 1 };
        graphs.set(canvasId, next);
        return route.fulfill({ json: next });
      }
      const stored = graphs.get(canvasId);
      return route.fulfill({ json: stored ?? {
        graph: {
          schemaVersion: 2,
          nodes: [],
          edges: [],
          viewport: { x: 0, y: 0, zoom: 1 },
          generationRecords: [],
          executions: [],
        },
        revision: 0,
      } });
    }
    if (/^\/canvases\/[^/]+$/.test(path)) {
      const canvasId = path.split('/')[2];
      for (const list of canvases.values()) {
        const canvas = list.find((entry) => entry.id === canvasId);
        if (!canvas) continue;
        if (method === 'PATCH') canvas.title = String(body.title ?? canvas.title);
        if (method === 'DELETE') {
          list.splice(list.indexOf(canvas), 1);
          return route.fulfill({ json: { ok: true } });
        }
        canvas.updatedAt = now();
        return route.fulfill({ json: canvas });
      }
      return route.fulfill({ status: 404, json: { message: 'canvas not found' } });
    }
    return route.fulfill({ json: {} });
  });
};

/** 一次性装好登录态与后端兜底（子用例可继续注册更具体的路由覆盖）。 */
export const prepareCanvasApp = async (page: Page) => {
  await mockApiFallback(page);
  await mockCanvasAuth(page);
  await mockCanvasBackendFallback(page);
  await mockCanvasFiles(page);
};

/**
 * 总兜底：任何没被用例 mock 的 /api/** 请求就地应答。
 *
 * e2e 里绝不能把请求漏到 Vite 代理：本机/CI 上可能有真实网关在 8080，
 * 假 token 会被判 401，画布 App 收到 401 会清 token 回登录页，用例就会莫名失败。
 * 必须最先注册（Playwright 后注册的路由优先），具体用例的 mock 依然会覆盖它。
 */
export const mockApiFallback = async (page: Page) => {
  // 只匹配真正打到后端网关的 /api/**；不能用 '**/api/**' ——它会把 Vite 的
  // /src/api/*.ts 源码模块也拦成 JSON，导致模块加载失败、画布连线渲染不出来。
  await page.route((url) => url.pathname.startsWith('/api/'), (route) => route.fulfill({ json: {} }));
};

/** 画布素材/上传文件的读取端点：给一张 1x1 PNG，避免请求漏到宿主网关。 */
export const mockCanvasFiles = async (page: Page) => {
  const onePixelPng = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    'base64',
  );
  await page.route((url) => url.pathname.startsWith('/api/files/'), (route) => route.fulfill({
    status: 200,
    contentType: 'image/png',
    body: onePixelPng,
  }));
};

/**
 * 给内嵌画布 iframe 注入登录态，等价于宿主页 EmbeddedAppWindow 的行为。
 * 宿主要等到内嵌应用上报 embedded-app-ready 才回令牌，所以这里也先等这个信号。
 */
export const postCanvasAuth = async (page: Page, token = E2E_CANVAS_TOKEN) => {
  await expect.poll(() => page.evaluate(() => {
    const messages = (window as typeof window & { __canvasModeMessages?: Array<{ type?: string }> })
      .__canvasModeMessages ?? [];
    return messages.some((message) => message?.type === 'ai-storyboard:embedded-app-ready');
  }), { timeout: 10_000 }).toBe(true);
  await page.evaluate((accessToken) => {
    const frame = (window as typeof window & { __canvasModeFrame?: HTMLIFrameElement }).__canvasModeFrame;
    frame?.contentWindow?.postMessage({
      type: 'ai-storyboard:canvas-auth',
      accessToken,
      userId: 'e2e-user',
    }, window.location.origin);
  }, token);
};
