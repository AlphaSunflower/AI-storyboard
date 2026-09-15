import { describe, expect, it } from 'vitest';
import type { CanvasNode } from '../types';
import type { GenerationRecord } from './graphContracts';
import { generationResultLabel, relabelGeneratedResultNodes } from './generationResultLabel';

const record = (actionId: string): GenerationRecord => ({
  id: 'record-1',
  producerNodeId: 'batch-node',
  actionId,
  attempt: 1,
  status: 'completed',
  materializationStatus: 'completed',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [],
  prompt: '',
  effectivePrompt: '',
  params: {},
  startedAt: 1,
});

describe('generationResultLabel', () => {
  it('uses the generated asset id for every generation action', () => {
    expect(generationResultLabel('asset-5f3c7d91')).toBe('asset-5f3c7d91');
    expect(generationResultLabel('')).toBe('生成结果');
  });
});

describe('relabelGeneratedResultNodes', () => {
  it('renames existing generated results to their asset ids', () => {
    const source = {
      id: 'source-1',
      type: 'image',
      position: { x: 0, y: 0 },
      data: { kind: 'image', label: '室外大堂', prompt: '', status: 'idle', accent: '#fff' },
    } satisfies CanvasNode;
    const result = {
      id: 'result-1',
      type: 'image',
      position: { x: 300, y: 0 },
      data: { kind: 'image', label: 'image_0', prompt: '', status: 'success', accent: '#fff', assetId: 'asset-result-1' },
    } satisfies CanvasNode;
    const batch = record('refineBatch');
    batch.sources = [{ nodeId: source.id, assetId: 'source-asset', role: 'source', order: 0 }];
    batch.resultNodeIds = [result.id];

    const relabeled = relabelGeneratedResultNodes([source, result], [batch]);

    expect(relabeled[1].data.label).toBe('asset-result-1');
  });
});
