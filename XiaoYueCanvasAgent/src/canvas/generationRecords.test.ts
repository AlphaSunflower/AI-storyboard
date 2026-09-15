import { describe, expect, it } from 'vitest';
import type { CanvasNode, GenerationRecord } from '../types';
import {
  buildGenerationSources,
  generationRecordProgress,
  generationResultAssetIds,
  selectActiveGenerationRecords,
} from './generationRecords';

const record = (overrides: Partial<GenerationRecord>): GenerationRecord => ({
  id: 'record-1',
  producerNodeId: 'producer',
  actionId: 'generate',
  attempt: 1,
  status: 'queued',
  materializationStatus: 'pending',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [],
  prompt: 'prompt',
  effectivePrompt: 'prompt',
  params: {},
  startedAt: 1,
  ...overrides,
});

const node = (id: string, assetId?: string): CanvasNode => ({
  id,
  type: 'image',
  position: { x: 0, y: 0 },
  data: {
    kind: 'image',
    label: id,
    prompt: '',
    status: 'idle',
    accent: '#fff',
    ...(assetId ? { assetId } : {}),
  },
});

describe('generation records projection', () => {
  it('keeps only queued, running, or materializing records in the task queue', () => {
    const records = [
      record({ id: 'queued', status: 'queued' }),
      record({ id: 'running', status: 'running' }),
      record({ id: 'materializing', status: 'completed', materializationStatus: 'materializing' }),
      record({ id: 'completed', status: 'completed', materializationStatus: 'completed' }),
      record({ id: 'failed', status: 'failed' }),
    ];

    expect(selectActiveGenerationRecords(records).map((item) => item.id)).toEqual([
      'queued',
      'running',
      'materializing',
    ]);
  });

  it('resolves source node ids and roles from the submitted asset ids', () => {
    const sources = buildGenerationSources({
      sourceAssetIds: ['first', 'last', 'reference'],
      roleByAssetId: {
        first: 'FIRST_FRAME',
        last: 'LAST_FRAME',
        reference: 'REFERENCE',
      },
      nodes: [node('first-node', 'first'), node('last-node', 'last'), node('reference-node', 'reference')],
      fallbackRole: 'REFERENCE',
    });

    expect(sources).toEqual([
      { nodeId: 'first-node', assetId: 'first', role: 'FIRST_FRAME', order: 0 },
      { nodeId: 'last-node', assetId: 'last', role: 'LAST_FRAME', order: 1 },
      { nodeId: 'reference-node', assetId: 'reference', role: 'REFERENCE', order: 2 },
    ]);
  });

  it('collects result assets from persistent ids and result nodes without duplicates', () => {
    const recordValue = record({
      materializedAssetIds: ['asset-1', 'asset-2'],
      resultNodeIds: ['result-1', 'result-2'],
    });
    const nodes = [node('result-1', 'asset-2'), node('result-2', 'asset-3')];

    expect(generationResultAssetIds(recordValue, nodes)).toEqual(['asset-1', 'asset-2', 'asset-3']);
  });

  it('projects record progress with node fallback and materializing floor', () => {
    expect(generationRecordProgress(record({ progress: .42 }), [])).toBe(.42);
    expect(generationRecordProgress(record({ progress: undefined }), [
      { ...node('producer'), data: { ...node('producer').data, progressPercent: 67 } },
    ])).toBe(.67);
    expect(generationRecordProgress(record({
      status: 'completed',
      materializationStatus: 'materializing',
    }), [])).toBe(.98);
  });
});
