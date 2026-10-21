import { apiFetch } from '../services/morrowApi';
import { createId } from '../utils/id';
import { executeToolCall } from './toolRegistry';
import { markPendingGroupRun } from './pendingGroupRun';
import type { BridgeSource, CanvasBridgeResponse } from './bridgeTypes';

// 画布命令信箱（canvas 服务）：浏览器每 1s 轮询一次，领取 MCP 侧投递的命令并在页面内执行。
// 同源调用，身份走正常登录态（apiFetch 自带 Bearer 与 401 刷新），不再需要独立端口与桥接令牌。
// 路径相对 morrowApi 的 /api/canvas 前缀，最终落到 /api/canvas/bridge/**。
const BRIDGE_PATH = '/bridge';
const BRIDGE_POLL_INTERVAL_MS = 1000;
const BRIDGE_RETRY_INTERVAL_MS = 2000;
const BRIDGE_HTTP_TIMEOUT_MS = 5_000;
const BRIDGE_BROWSER_COMMAND_TIMEOUT_MS = 10 * 60_000;
const RESYNC_TIMEOUT_MS = 8_000;

// 这两类失败都只说明「浏览器这份图落后于服务端」：命令是拿本地图执行的，
// 智能体刚在服务端建的节点/推进的 revision 本地还没有。重载一次再重试即可。
const STALE_GRAPH_CODES = new Set(['NODE_NOT_FOUND', 'CANVAS_REVISION_CONFLICT']);

export interface CanvasBridgeStatus {
  state: 'connecting' | 'ready' | 'error';
  message?: string;
}

interface BridgePollResult {
  command?: {
    requestId: string;
    method: string;
    params: Record<string, unknown>;
    source?: BridgeSource;
    dryRun?: boolean;
    expectedRevision?: number;
  };
}

interface BridgeHealth {
  ok?: boolean;
  version?: number;
  projects?: Array<{ projectId: string; canvasId: string }>;
}

// 打开项目后调用，返回一个停止函数。浏览器以轮询方式从画布服务拉取
// 外部（MCP/CLI）命令，执行命令层后回传结果。
// 服务不可达时**不启动轮询**，仅在 /bridge/health 探测成功后才 register + poll，
// 之后单次网络失败也保持静默并定时重连。
export function startCanvasBridge(
  projectId: string,
  canvasId: string,
  onStatus?: (status: CanvasBridgeStatus) => void,
  titles?: { projectTitle?: string; canvasTitle?: string },
): () => void {
  const tabId = createId();
  let stopped = false;
  let timer: number | null = null;
  let retryTimer: number | null = null;

  const fetchWithTimeout = (
    path: string,
    init: RequestInit = {},
    timeoutMs = BRIDGE_HTTP_TIMEOUT_MS,
  ) => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    return apiFetch(path, { ...init, signal: controller.signal })
      .finally(() => window.clearTimeout(timeout));
  };

  const post = async (path: string, body: unknown) => {
    try {
      const response = await fetchWithTimeout(`${BRIDGE_PATH}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      return response.ok;
    } catch {
      // 服务已停止/网络错误：静默忽略（轮询在下一轮自然停止或继续）。
      return false;
    }
  };

  const executeCommand = async (
    requestId: string,
    method: string,
    params: Record<string, unknown>,
    source: BridgeSource,
    dryRun: boolean,
    expectedRevision?: number,
  ) => {
    const controller = new AbortController();
    let timer: number | null = null;
    const timeout = new Promise<'timeout'>((resolve) => {
      timer = window.setTimeout(() => {
        controller.abort();
        resolve('timeout');
      }, BRIDGE_BROWSER_COMMAND_TIMEOUT_MS);
    });
    try {
      const result = await Promise.race([
        executeToolCall(projectId, { id: requestId, name: method, arguments: params }, {
          source,
          dryRun,
          expectedRevision,
          signal: controller.signal,
        }),
        timeout,
      ]);
      if (result === 'timeout') {
        return {
          toolCallId: requestId,
          name: method,
          ok: false,
          status: 'failed' as const,
          error: { code: 'COMMAND_TIMEOUT', message: `画布命令 ${method} 执行超时` },
        };
      }
      return result;
    } finally {
      if (timer !== null) window.clearTimeout(timer);
    }
  };

  const scheduleReconnect = () => {
    if (stopped || retryTimer !== null) return;
    retryTimer = window.setTimeout(() => {
      retryTimer = null;
      void connect();
    }, BRIDGE_RETRY_INTERVAL_MS);
  };

  /**
   * 让宿主（App）整图重载到服务端最新状态，重载完成后再继续。
   *
   * <p>命令在浏览器里跑，用的是本地图；智能体刚在服务端建的节点本地还没有时，
   * 直接执行只会报 `NODE_NOT_FOUND`。这里握手成功后重试一次，把这类"假报错"收掉。
   */
  const resyncCanvas = () => new Promise<void>((resolve) => {
    if (typeof window === 'undefined') {
      resolve();
      return;
    }
    let timer: number | null = null;
    const done = () => {
      if (timer !== null) window.clearTimeout(timer);
      window.removeEventListener('morrow:canvas-resynced', done);
      resolve();
    };
    window.addEventListener('morrow:canvas-resynced', done);
    timer = window.setTimeout(done, RESYNC_TIMEOUT_MS);
    window.dispatchEvent(new CustomEvent('morrow:canvas-resync', {
      detail: { projectId, canvasId, reason: 'bridge-command-stale-graph' },
    }));
  });

  const poll = async () => {
    if (stopped) return;
    try {
      const response = await fetchWithTimeout(
        `${BRIDGE_PATH}/poll?tabId=${encodeURIComponent(tabId)}`);
      if (response.ok) {
        const data = (await response.json()) as BridgePollResult;
        if (data.command) {
          const { requestId, method, params, source, dryRun, expectedRevision } = data.command;
          const run = () => executeCommand(
            requestId,
            method,
            params,
            source ?? 'cli',
            dryRun ?? false,
            expectedRevision,
          );
          let result = await run();
          // 本地图落后：先对齐服务端，再重试一次同一条命令。
          if (!result.ok && STALE_GRAPH_CODES.has(String(result.error?.code ?? ''))) {
            console.info(`[canvas-bridge] ${method} 需要最新图（${result.error?.code}）→ 重载后重试`);
            await resyncCanvas();
            if (stopped) return;
            result = await run();
          }
          // 执行类命令被服务端强制成预览：把预览里的分组记下来，
          // 让分组标题条上的「执行」按钮变成高亮的「确认执行」。
          if (result.ok && (method === 'canvas.graph.execute' || method === 'canvas.node.execute')) {
            markPendingGroupRun(result.result);
          }
          const bridgeResponse: CanvasBridgeResponse = {
            version: 1,
            id: requestId,
            ok: result.ok,
            result: result.result,
            transactionId: result.transactionId,
            revision: result.revision,
            status: result.status,
            error: result.error,
          };
          await post('/result', { requestId, response: bridgeResponse });
        }
      }
    } catch {
      if (!stopped) {
        onStatus?.({ state: 'error', message: '画布服务连接中断，正在重试…' });
        scheduleReconnect();
      }
      return;
    }
    if (!stopped) timer = window.setTimeout(() => void poll(), BRIDGE_POLL_INTERVAL_MS);
  };

  const connect = async () => {
    if (stopped) return;
    onStatus?.({ state: 'connecting', message: '正在连接画布服务…' });
    try {
      const response = await fetchWithTimeout(`${BRIDGE_PATH}/health`);
      if (stopped) return;
      if (!response.ok) throw new Error('Bridge health check failed');
      const health = (await response.json()) as BridgeHealth;
      if (health?.ok !== true) throw new Error('Bridge is not healthy');
      const registered = await post('/register', {
        tabId,
        projectId,
        projectTitle: titles?.projectTitle,
        canvasId,
        canvasTitle: titles?.canvasTitle,
      });
      if (!registered) throw new Error('Bridge register failed');
      if (stopped) {
        await post('/unregister', { tabId });
        return;
      }
      onStatus?.({ state: 'ready' });
      void poll();
    } catch {
      if (stopped) return;
      onStatus?.({ state: 'error', message: '画布服务未就绪，正在重试…' });
      scheduleReconnect();
    }
  };

  void connect();

  return () => {
    stopped = true;
    if (timer !== null) window.clearTimeout(timer);
    if (retryTimer !== null) window.clearTimeout(retryTimer);
    void post('/unregister', { tabId });
  };
}
