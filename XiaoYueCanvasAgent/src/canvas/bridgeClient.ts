import { createId } from '../utils/id';
import { executeToolCall } from './toolRegistry';
import { BRIDGE_DEFAULT_PORT, BRIDGE_POLL_INTERVAL_MS, type BridgePollResult, type CanvasBridgeResponse } from './bridgeProtocol';

const endpoint = import.meta.env.VITE_BRIDGE_URL?.trim().replace(/\/$/, '') || `http://127.0.0.1:${BRIDGE_DEFAULT_PORT}`;

// 打开项目后调用，返回一个停止函数。浏览器以轮询方式从 Bridge 拉取
// 外部（CLI/MCP）命令，执行命令层后回传结果。Bridge 不可用时静默忽略。
export function startCanvasBridge(projectId: string): () => void {
  const tabId = createId();
  let stopped = false;
  let timer: number | null = null;

  const post = async (path: string, body: unknown) => {
    try {
      await fetch(`${endpoint}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
    } catch {
      // Bridge 未启动或网络错误时保持静默，轮询会持续重试。
    }
  };

  const poll = async () => {
    if (stopped) return;
    try {
      const response = await fetch(`${endpoint}/poll?tabId=${encodeURIComponent(tabId)}`);
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
      // 忽略单次轮询失败。
    }
    if (!stopped) timer = window.setTimeout(() => void poll(), BRIDGE_POLL_INTERVAL_MS);
  };

  void post('/register', { tabId, projectId }).then(() => poll());

  return () => {
    stopped = true;
    if (timer !== null) window.clearTimeout(timer);
  };
}
