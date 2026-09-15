import { describe, expect, it } from 'vitest';
import { buildBatchRefineRequests, summarizeBatchGeneration } from './batchImageRefine';

describe('buildBatchRefineRequests', () => {
  it('merges a common prompt with per-image overrides without mutating inputs', () => {
    const items = [
      { itemId: 'a', nodeId: 'node-a', assetId: 'asset-a', order: 0 },
      { itemId: 'b', nodeId: 'node-b', assetId: 'asset-b', order: 1 },
    ];
    const result = buildBatchRefineRequests(items, '统一蓝色调', { b: '保留红色围巾' });

    expect(result[0].effectivePrompt).toBe('统一蓝色调');
    expect(result[1].effectivePrompt).toBe('统一蓝色调\n保留红色围巾');
    expect(items[1]).toEqual({ itemId: 'b', nodeId: 'node-b', assetId: 'asset-b', order: 1 });
  });

  it('rejects an empty shared prompt when an item has no override', () => {
    expect(() => buildBatchRefineRequests([
      { itemId: 'a', nodeId: 'node-a', assetId: 'asset-a', order: 0 },
    ], '   ')).toThrow('请填写公共提示词或单图覆盖');
  });
});

describe('summarizeBatchGeneration', () => {
  const record = (id: string, status: string, error?: string) => ({
    id,
    batchId: 'batch-1',
    producerNodeId: 'batch-node',
    actionId: 'refineBatch',
    attempt: 1,
    status,
    materializationStatus: 'pending',
    materializedAssetIds: [],
    resultNodeIds: [],
    sources: [],
    prompt: 'p',
    effectivePrompt: 'p',
    params: {},
    startedAt: Number(id.slice(1)),
    error,
  });

  it('keeps a failed batch visible with its error', () => {
    const summary = summarizeBatchGeneration([
      record('1', 'failed', 'Image Edit API returned 404'),
      record('2', 'failed', 'Image Edit API returned 404'),
    ], 'batch-node');

    expect(summary).toMatchObject({
      state: 'error',
      total: 2,
      failed: 2,
      error: 'Image Edit API returned 404',
    });
  });

  it('reports completed and running batch progress', () => {
    expect(summarizeBatchGeneration([
      record('1', 'completed'),
      record('2', 'completed'),
    ], 'batch-node')).toMatchObject({ state: 'completed', total: 2, completed: 2 });

    expect(summarizeBatchGeneration([
      record('1', 'completed'),
      record('2', 'running'),
    ], 'batch-node')).toMatchObject({ state: 'running', total: 2, completed: 1, running: 1 });
  });
});
