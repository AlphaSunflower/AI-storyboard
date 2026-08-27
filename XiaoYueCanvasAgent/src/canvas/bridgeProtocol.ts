// 浏览器画布与外部进程（CLI / MCP）之间的桥接协议。
// 本模块只包含纯类型与常量，不依赖任何运行时业务代码，因此可以被
// 浏览器页面和 Node 进程同时 import（Node 通过 type stripping 直接运行）。

export const BRIDGE_PROTOCOL_VERSION = 1;
export const BRIDGE_DEFAULT_PORT = 8787;
export const BRIDGE_POLL_INTERVAL_MS = 1000;
export const BRIDGE_COMMAND_TIMEOUT_MS = 10_000;

export type BridgeSource = 'mcp' | 'cli' | 'agent';

export interface CanvasBridgeRequest {
  version: 1;
  id: string;
  // 为空时由 Bridge 解析为当前唯一打开的项目。
  projectId?: string;
  method: string;
  params: Record<string, unknown>;
  source: BridgeSource;
  dryRun?: boolean;
  expectedRevision?: number;
  approvalToken?: string;
}

export interface CanvasBridgeResponse {
  version: 1;
  id: string;
  ok: boolean;
  result?: unknown;
  transactionId?: string;
  revision?: number;
  status?: 'preview' | 'applied' | 'rejected' | 'failed';
  error?: { code: string; message: string; details?: unknown };
}

// 浏览器 -> Bridge：打开项目后注册当前标签页。
export interface BridgeRegisterPayload {
  tabId: string;
  projectId: string;
  lastRevision?: number;
}

// Bridge -> 浏览器：poll 时返回待执行命令，或空对象。
export interface BridgePollResult {
  command?: {
    requestId: string;
    method: string;
    params: Record<string, unknown>;
    dryRun?: boolean;
    expectedRevision?: number;
  };
}

// 浏览器 -> Bridge：回传命令执行结果。
export interface BridgeResultPayload {
  requestId: string;
  response: CanvasBridgeResponse;
}

export interface BridgeHealth {
  ok: boolean;
  version: number;
  projects: Array<{ projectId: string; lastSeen: number }>;
}
