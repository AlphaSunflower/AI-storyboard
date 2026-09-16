import { describe, expect, it, vi } from 'vitest';
import type { PersistedAsset } from '../types';
import type { GenerationRecord } from './graphContracts';
import { materializeGenerationResult, type GenerationMaterializerPort } from './generationMaterializer';

const image = (id: string): PersistedAsset => ({
  id,
  kind: 'image',
  name: `${id}.png`,
  mimeType: 'image/png',
  bytes: 1,
  width: 10,
  height: 10,
  originalBlob: new Blob(['x'], { type: 'image/png' }),
  thumbnailBlob: new Blob(['x'], { type: 'image/webp' }),
  previewBlob: new Blob(['x'], { type: 'image/webp' }),
  operation: { type: 'remote', params: {} },
  libraryState: 'project',
  createdAt: 1,
});

const record = (overrides: Partial<GenerationRecord> = {}): GenerationRecord => ({
  id: 'record-1',
  producerNodeId: 'producer-1',
  actionId: 'generate',
  attempt: 1,
  status: 'running',
  materializationStatus: 'pending',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [],
  prompt: 'cat',
  effectivePrompt: 'cat',
  params: {},
  startedAt: 1,
  ...overrides,
});

const createPort = (overrides: Partial<GenerationMaterializerPort> = {}): GenerationMaterializerPort => ({
  fetchFile: vi.fn().mockResolvedValue(new File(['x'], 'cat.png', { type: 'image/png' })),
  createAsset: vi.fn().mockImplementation(async (file: File) => image(file.name.replace(/\.[^.]+$/, ''))),
  saveAsset: vi.fn().mockResolvedValue(undefined),
  upsertAsset: vi.fn(),
  attachResultNode: vi.fn().mockImplementation((resultNodeId, _asset, _record, targetNodeId) => targetNodeId ?? resultNodeId),
  updateRecord: vi.fn(),
  ...overrides,
});

describe('materializeGenerationResult', () => {
  it('uses the persisted canvas asset id when provided', async () => {
    const port = createPort();

    await materializeGenerationResult(
      port,
      record(),
      { assetId: 'remote-1', persistedId: 'asset-db-1', name: 'cat.png', mimeType: 'image/png' },
      'job-1',
    );

    expect(port.createAsset).toHaveBeenCalledWith(expect.any(File), 'asset-db-1');
  });

  it('creates one deterministic result node for duplicate asset events', async () => {
    const source = record();
    const port = createPort();
    const event = { assetId: 'remote-1', name: 'cat.png', mimeType: 'image/png' };

    const first = await materializeGenerationResult(port, source, event, 'job-1');
    const second = await materializeGenerationResult(port, first.record, event, 'job-1');

    expect(first.record.resultNodeIds).toEqual(['result:record-1:remote-1']);
    expect(second.created).toBe(false);
    expect(port.attachResultNode).toHaveBeenCalledTimes(1);
    expect(port.createAsset).toHaveBeenCalledTimes(1);
  });

  it('does not materialize a cancelled or interrupted record', async () => {
    const port = createPort();
    const settlement = await materializeGenerationResult(
      port,
      record({ status: 'cancelled' }),
      { assetId: 'remote-1', name: 'cat.png', mimeType: 'image/png' },
      'job-1',
    );

    expect(settlement.created).toBe(false);
    expect(port.fetchFile).not.toHaveBeenCalled();
    expect(port.updateRecord).toHaveBeenCalledWith(expect.objectContaining({
      materializationStatus: 'failed',
      materializationError: '任务已取消，忽略晚到结果',
    }));
  });

  it('marks materialization failed without changing backend job status', async () => {
    const port = createPort({ fetchFile: vi.fn().mockRejectedValue(new Error('offline')) });
    const settlement = await materializeGenerationResult(
      port,
      record(),
      { assetId: 'remote-1', name: 'cat.png', mimeType: 'image/png' },
      'job-1',
    );

    expect(settlement.created).toBe(false);
    expect(settlement.record.status).toBe('running');
    expect(settlement.record.materializationStatus).toBe('failed');
    expect(settlement.record.materializationError).toBe('offline');
  });

  it('does not attach a result if cancellation arrives before attachment', async () => {
    let cancelled = false;
    const port = createPort({
      fetchFile: vi.fn().mockImplementation(async () => {
        cancelled = true;
        return new File(['x'], 'cat.png', { type: 'image/png' });
      }),
      getRecord: () => record({ status: cancelled ? 'cancelled' : 'running' }),
    });
    const settlement = await materializeGenerationResult(
      port,
      record(),
      { assetId: 'remote-1', name: 'cat.png', mimeType: 'image/png' },
      'job-1',
    );

    expect(settlement.created).toBe(false);
    expect(port.attachResultNode).not.toHaveBeenCalled();
    expect(settlement.record.materializationStatus).toBe('failed');
  });

  it('fills ordered compatible result slots', async () => {
    const port = createPort();
    const source = record({
      resultSlots: [
        { nodeId: 'slot-a', kind: 'image', order: 0, edgeId: 'edge-a' },
        { nodeId: 'slot-b', kind: 'image', order: 1, edgeId: 'edge-b' },
      ],
    });

    const first = await materializeGenerationResult(
      port,
      source,
      { assetId: 'remote-1', name: 'one.png', mimeType: 'image/png' },
      'job-1',
    );
    const second = await materializeGenerationResult(
      port,
      first.record,
      { assetId: 'remote-2', name: 'two.png', mimeType: 'image/png' },
      'job-1',
    );

    expect(port.attachResultNode).toHaveBeenNthCalledWith(
      1,
      'result:record-1:remote-1',
      expect.anything(),
      expect.anything(),
      'slot-a',
    );
    expect(port.attachResultNode).toHaveBeenNthCalledWith(
      2,
      'result:record-1:remote-2',
      expect.anything(),
      expect.anything(),
      'slot-b',
    );
  });

  it('creates a normal result node when all compatible slots are filled', async () => {
    const port = createPort();
    const source = record({
      resultSlots: [{ nodeId: 'slot-a', kind: 'image', order: 0, edgeId: 'edge-a' }],
    });

    const first = await materializeGenerationResult(
      port,
      source,
      { assetId: 'remote-1', name: 'one.png', mimeType: 'image/png' },
      'job-1',
    );
    await materializeGenerationResult(
      port,
      first.record,
      { assetId: 'remote-2', name: 'two.png', mimeType: 'image/png' },
      'job-1',
    );

    expect(port.attachResultNode).toHaveBeenNthCalledWith(
      2,
      'result:record-1:remote-2',
      expect.anything(),
      expect.anything(),
      undefined,
    );
  });

  it('does not fill an image result into a video slot', async () => {
    const port = createPort();
    const settlement = await materializeGenerationResult(
      port,
      record({
        resultSlots: [{ nodeId: 'video-slot', kind: 'video', order: 0, edgeId: 'edge-v' }],
      }),
      { assetId: 'remote-1', name: 'one.png', mimeType: 'image/png' },
      'job-1',
    );

    expect(settlement.resultNodeId).toBe('result:record-1:remote-1');
    expect(port.attachResultNode).toHaveBeenCalledWith(
      'result:record-1:remote-1',
      expect.anything(),
      expect.anything(),
      undefined,
    );
  });
});
