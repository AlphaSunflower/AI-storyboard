import { createId } from '../utils/id';
import { executeToolCall } from './toolRegistry';
import { BRIDGE_DEFAULT_PORT, BRIDGE_POLL_INTERVAL_MS, type BridgePollResult, type CanvasBridgeResponse, type BridgeHealth } from './bridgeProtocol';

const endpoint = import.meta.env.VITE_BRIDGE_URL?.trim().replace(/\/$/, '') || `http://127.0.0.1:${BRIDGE_DEFAULT_PORT}`;

// 打开项目后调用，返回一个停止函数。浏览器以轮询方式从 Bridge 拉取
// 外部（CLI/MCP）命令，执行命令层后回传结果。
// Bridge 未启动时**不启动轮询**（避免 fetch 抛 ERR_CONNECTION_REFUSED 在控制台无限刷屏）；
// 仅在 /health 探测成功后才 register + poll，之后单次网络失败也保持静默。
export function startCanvasBridge(projectId: string): () => void {
  const tabId = createId();
  let stopped = false;
  let timer: number | null = null;

  const fetchWithTimeout = (input: string, init: RequestInit = {}, timeoutMs = 700) => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    return fetch(input, { ...init, signal: controller.signal }).finally(() => window.clearTimeout(timeout));
  };

  const post = async (path: string, body: unknown) => {
    try {
      await fetchWithTimeout(`${endpoint}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
    } catch {
      // Bridge 已停止/网络错误：静默忽略（轮询在下一轮自然停止或继续）。
    }
  };

  const poll = async () => {
    if (stopped) return;
    try {
      const response = await fetchWithTimeout(`${endpoint}/poll?tabId=${encodeURIComponent(tabId)}`);
      if (response.ok) {
        const data = (await response.json()) as BridgePollResult;
        if (data.command) {
          const { requestId, method, params, dryRun, expectedRevision } = data.command;
          const result = await executeToolCall(projectId, { id: requestId, name: method, arguments: params }, {
            source: 'cli',
            dryRun: dryRun ?? false,
            expectedRevision,
          });
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
      // 单次轮询失败（Bridge 中途断开）：忽略，下一轮继续。
    }
    if (!stopped) timer = window.setTimeout(() => void poll(), BRIDGE_POLL_INTERVAL_MS);
  };

  // 先探测 Bridge 是否可达：可达才注册并轮询；不可达则不启动（避免控制台无限 ERR_CONNECTION_REFUSED）。
  void fetchWithTimeout(`${endpoint}/health`)
    .then(async (response) => {
      if (stopped) return;
      if (!response.ok) return;
      const health = (await response.json()) as BridgeHealth;
      if (!health?.ok) return;
      await post('/register', { tabId, projectId });
      if (!stopped) void poll();
    })
    .catch(() => {
      // Bridge 未启动：静默跳过，不报错不重试。
    });

  return () => {
    stopped = true;
    if (timer !== null) window.clearTimeout(timer);
  };
}
