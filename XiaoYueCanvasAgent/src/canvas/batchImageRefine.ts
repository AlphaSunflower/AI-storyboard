import type { GenerationRecord } from './graphContracts';

export interface BatchRefineInput {
  itemId: string;
  nodeId: string;
  assetId: string;
  order: number;
}

export interface BatchGenerationSummary {
  state: 'idle' | 'submitted' | 'running' | 'completed' | 'error';
  total: number;
  completed: number;
  running: number;
  failed: number;
  batchId?: string;
  message: string;
  error?: string;
}

export interface BatchRefineRequest extends BatchRefineInput {
  effectivePrompt: string;
}

export function buildBatchRefineRequests(
  items: BatchRefineInput[],
  commonPrompt: string,
  overrides: Record<string, string> = {},
): BatchRefineRequest[] {
  const common = commonPrompt.trim();
  return [...items]
    .sort((a, b) => a.order - b.order)
    .map((item) => {
      const override = overrides[item.itemId]?.trim();
      if (!common && !override) throw new Error('请填写公共提示词或单图覆盖');
      return {
        ...item,
        effectivePrompt: [common, override].filter(Boolean).join('\n'),
      };
    });
}

export function summarizeBatchGeneration(
  records: GenerationRecord[],
  producerNodeId: string,
): BatchGenerationSummary {
  const candidates = records
    .filter((record) => record.producerNodeId === producerNodeId && record.batchId)
    .sort((left, right) => right.startedAt - left.startedAt);
  const latest = candidates[0];
  if (!latest?.batchId) {
    return { state: 'idle', total: 0, completed: 0, running: 0, failed: 0, message: '' };
  }
  const items = candidates.filter((record) => record.batchId === latest.batchId);
  const completed = items.filter((record) => record.status === 'completed').length;
  const running = items.filter((record) => ['queued', 'running'].includes(record.status)).length;
  const failed = items.filter((record) => ['failed', 'interrupted', 'cancelled'].includes(record.status)).length;
  const error = items.find((record) => record.error)?.error;
  const total = items.length;

  if (failed > 0) {
    return {
      state: 'error',
      total,
      completed,
      running,
      failed,
      batchId: latest.batchId,
      message: `批量任务失败 · 完成 ${completed}/${total} · 失败 ${failed}${error ? `\n${error}` : ''}`,
      error,
    };
  }
  if (completed === total) {
    return {
      state: 'completed',
      total,
      completed,
      running,
      failed,
      batchId: latest.batchId,
      message: `批量任务已完成 · ${completed}/${total}`,
    };
  }
  if (running > 0) {
    return {
      state: 'running',
      total,
      completed,
      running,
      failed,
      batchId: latest.batchId,
      message: `批量任务生成中 · 完成 ${completed}/${total}`,
    };
  }
  return {
    state: 'submitted',
    total,
    completed,
    running,
    failed,
    batchId: latest.batchId,
    message: `已提交 ${total} 个改图任务，等待执行服务`,
  };
}
