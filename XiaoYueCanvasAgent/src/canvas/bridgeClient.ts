import { createId } from '../utils/id';
import { executeToolCall } from './toolRegistry';
import {
  BRIDGE_BROWSER_COMMAND_TIMEOUT_MS,
  BRIDGE_DEFAULT_PORT,
  BRIDGE_POLL_INTERVAL_MS,
  type BridgePollResult,
  type CanvasBridgeResponse,
  type BridgeHealth,
} from './bridgeProtocol';

const endpoint = import.meta.env.VITE_BRIDGE_URL?.trim().replace(/\/$/, '') || `http://127.0.0.1:${BRIDGE_DEFAULT_PORT}`;
const bridgeToken = import.meta.env.VITE_BRIDGE_TOKEN?.trim() ?? '';
const BRIDGE_RETRY_INTERVAL_MS = 2000;
const BRIDGE_HTTP_TIMEOUT_MS = 5_000;

export interface CanvasBridgeStatus {
  state: 'connecting' | 'ready' | 'error';
  message?: string;
}

// 打开项目后调用，返回一个停止函数。浏览器以轮询方式从 Bridge 拉取
// 外部（CLI/MCP）命令，执行命令层后回传结果。
// Bridge 未启动时**不启动轮询**（避免 fetch 抛 ERR_CONNECTION_REFUSED 在控制台无限刷屏）；
// 仅在 /health 探测成功后才 register + poll，之后单次网络失败也保持静默。
export function startCanvasBridge(
  projectId: string,
  canvasId: string,
  onStatus?: (status: CanvasBridgeStatus) => void,
  titles?: { projectTitle?: string; canvasTitle?: string },
): () => void {
  if (!bridgeToken) {
    onStatus?.({ state: 'error', message: '未配置画布桥接令牌' });
    return () => {};
  }
  const tabId = createId();
  let stopped = false;
  let timer: number | null = null;
  let retryTimer: number | null = null;

  const fetchWithTimeout = (input: string, init: RequestInit = {}, timeoutMs = BRIDGE_HTTP_TIMEOUT_MS) => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    const headers = new Headers(init.headers);
    if (bridgeToken) headers.set('X-Bridge-Token', bridgeToken);
    return fetch(input, { ...init, headers, signal: controller.signal }).finally(() => window.clearTimeout(timeout));
  };

  const post = async (path: string, body: unknown) => {
    try {
      const response = await fetchWithTimeout(`${endpoint}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      return response.ok;
    } catch {
      // Bridge 已停止/网络错误：静默忽略（轮询在下一轮自然停止或继续）。
      return false;
    }
  };

  const executeCommand = async (
    requestId: string,
    method: string,
    params: Record<string, unknown>,
    source: 'mcp' | 'cli' | 'agent',
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

  const poll = async () => {
    if (stopped) return;
    try {
      const response = await fetchWithTimeout(`${endpoint}/poll?tabId=${encodeURIComponent(tabId)}`);
      if (response.ok) {
        const data = (await response.json()) as BridgePollResult;
        if (data.command) {
          const { requestId, method, params, source, dryRun, expectedRevision } = data.command;
          const result = await executeCommand(
            requestId,
            method,
            params,
            source ?? 'cli',
            dryRun ?? false,
            expectedRevision,
          );
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
      const response = await fetchWithTimeout(`${endpoint}/health`);
      if (stopped) return;
      if (!response.ok) throw new Error('Bridge health check failed');
      const health = (await response.json()) as BridgeHealth;
      if (!health?.ok) throw new Error('Bridge is not healthy');
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
