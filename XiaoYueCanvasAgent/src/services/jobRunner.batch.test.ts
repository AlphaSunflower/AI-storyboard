import { describe, expect, it } from 'vitest';
import { batchRefineOptions, remapReferenceRoleAssetIds } from './jobRunner';

describe('batch refine submission', () => {
  it('queues each item without holding a client execution slot', () => {
    const options = batchRefineOptions({
      itemId: 'asset-1',
      nodeId: 'image-1',
      assetId: 'asset-1',
      order: 0,
      effectivePrompt: '变成卡通形象',
    }, 'batch-1');

    expect(options).toMatchObject({
      sourceAssetIds: ['asset-1'],
      sourceNodeIds: ['image-1'],
      batchId: 'batch-1',
      itemId: 'asset-1',
      skipSlot: true,
      params: { actionId: 'redraw' },
    });
  });
});

describe('persisted reference role mapping', () => {
  it('rewrites role asset ids after local references receive canvas asset ids', () => {
    const roles = remapReferenceRoleAssetIds(
      [
        { assetId: 'local-first', role: 'FIRST_FRAME' },
        { assetId: 'local-last', role: 'LAST_FRAME' },
      ],
      {
        'local-first': 'canvas-first',
        'local-last': 'canvas-last',
      },
    );

    expect(roles).toEqual([
      { assetId: 'canvas-first', role: 'FIRST_FRAME' },
      { assetId: 'canvas-last', role: 'LAST_FRAME' },
    ]);
  });
});
