import { apiJson } from './morrowApi';

/**
 * 画布执行批次（服务端 DAG 执行）。
 *
 * <p>批次由 canvas 服务推进：每个节点状态落库，浏览器只轮询快照。
 * 所以刷新页面 / HMR / 断网都不会中断工作流——这是把「分组执行」从浏览器搬走的关键。
 */

export type CanvasExecutionStatus = 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';
export type CanvasExecutionNodeStatus = 'pending' | 'running' | 'succeeded' | 'failed' | 'blocked' | 'cancelled';

export interface CanvasExecutionNodeSnapshot {
  nodeId: string;
  actionId: string;
  status: CanvasExecutionNodeStatus;
  jobId?: string;
  resultAssetIds: string[];
  /** 服务端已挂回画布图的画布资产 id（有它就直接显示，不再重复物化） */
  attachedAssetId?: string;
  error?: string;
  startedAt?: number;
  completedAt?: number;
}

export interface CanvasExecutionSnapshot {
  id: string;
  canvasId: string;
  status: CanvasExecutionStatus;
  maxParallel: number;
  error?: string;
  createdAt: number;
  updatedAt: number;
  nodes: CanvasExecutionNodeSnapshot[];
}

/** 提交给服务端的一步：节点 + 已解析好的 job 参数（含幂等键）。 */
export interface CanvasExecutionStep {
  nodeId: string;
  actionId: string;
  capabilityId: string;
  clientRequestId: string;
  sourceAssetIds: string[];
  params: Record<string, unknown>;
  resultSlotNodeId?: string;
}

const executionsPath = (canvasId: string) =>
  `/canvases/${encodeURIComponent(canvasId)}/executions`;

export function createCanvasExecution(
  canvasId: string,
  steps: CanvasExecutionStep[],
  maxParallel = 1,
): Promise<CanvasExecutionSnapshot> {
  return apiJson<CanvasExecutionSnapshot>(executionsPath(canvasId), {
    method: 'POST',
    body: JSON.stringify({ maxParallel, steps }),
  });
}

export function getCanvasExecution(canvasId: string, executionId: string): Promise<CanvasExecutionSnapshot> {
  return apiJson<CanvasExecutionSnapshot>(
    `${executionsPath(canvasId)}/${encodeURIComponent(executionId)}`);
}

export function listCanvasExecutions(canvasId: string): Promise<CanvasExecutionSnapshot[]> {
  return apiJson<CanvasExecutionSnapshot[]>(executionsPath(canvasId));
}

export function cancelCanvasExecution(canvasId: string, executionId: string): Promise<CanvasExecutionSnapshot> {
  return apiJson<CanvasExecutionSnapshot>(
    `${executionsPath(canvasId)}/${encodeURIComponent(executionId)}/cancel`, { method: 'POST' });
}

export const isFinalExecutionStatus = (status: CanvasExecutionStatus) =>
  status === 'succeeded' || status === 'failed' || status === 'cancelled';
