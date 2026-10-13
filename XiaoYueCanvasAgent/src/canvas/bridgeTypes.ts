// 画布命令信箱（canvas 服务 /api/canvas/bridge）的命令与响应契约。
// 命令的实际执行仍在浏览器侧（toolRegistry / canvasCommandService）。

export type BridgeSource = 'mcp' | 'cli' | 'agent';

export interface CanvasBridgeResponse {
  version: 1;
  id: string;
  ok: boolean;
  result?: unknown;
  transactionId?: string;
  revision?: number;
  status?: 'preview' | 'applied' | 'accepted' | 'rejected' | 'failed';
  error?: { code: string; message: string; details?: unknown };
}
