import { beforeEach, describe, expect, it, vi } from 'vitest';
import { clearLocalData, listGenerationQueueItems, putGenerationQueueItem } from '../services/projectStorage';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasNode } from '../types';
import type { GenerationQueueItem } from './generationQueue';
import type { GenerationRecord } from './graphContracts';
import { recoverGenerationJobs } from './generationRuntime';

const mocks = vi.hoisted(() => ({
  getJob: vi.fn(),
  subscribe: vi.fn(),
  fetchAsset: vi.fn(),
  linkGeneratedCanvasAssets: vi.fn(),
  materializeGenerationResult: vi.fn(),
}));

vi.mock('../services/capabilities', () => ({
  jobAdapter: {
    getJob: mocks.getJob,
    subscribe: mocks.subscribe,
    fetchAsset: mocks.fetchAsset,
  },
}));

vi.mock('./generationMaterializer', () => ({
  materializeGenerationResult: mocks.materializeGenerationResult,
}));

vi.mock('../services/canvasAssets', () => ({
  linkGeneratedCanvasAssets: mocks.linkGeneratedCanvasAssets,
}));

const queueItem = (): GenerationQueueItem => ({
  id: 'record-1',
  projectId: 'project-1',
  canvasId: 'canvas-1',
  producerNodeId: 'node-1',
  generationRecordId: 'record-1',
  requestSnapshot: { capabilityId: 'video.generate', sourceAssetIds: [], params: {} },
  status: 'running',
  jobId: 'job-1',
  attempt: 1,
  createdAt: 1,
  updatedAt: 1,
});

const record = (): GenerationRecord => ({
  id: 'record-1',
  producerNodeId: 'node-1',
  actionId: 'generate',
  attempt: 1,
  status: 'running',
  queueStatus: 'running',
  materializationStatus: 'pending',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [],
  prompt: 'generate a video',
  effectivePrompt: 'generate a video',
  params: { capabilityId: 'video.generate' },
  jobId: 'job-1',
  startedAt: 1,
});

const node = (): CanvasNode => ({
  id: 'node-1',
  type: 'textToVideo',
  position: { x: 0, y: 0 },
  data: {
    kind: 'textToVideo',
    label: '文生视频',
    prompt: '',
    status: 'idle',
    accent: '#d7ff54',
    isGenerating: true,
  },
});

describe('recoverGenerationJobs', () => {
  beforeEach(async () => {
    await clearLocalData();
    mocks.getJob.mockReset();
    mocks.subscribe.mockReset();
    mocks.fetchAsset.mockReset();
    mocks.linkGeneratedCanvasAssets.mockReset();
    mocks.materializeGenerationResult.mockReset();
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [node()],
      edges: [],
      generationRecords: [record()],
    });
  });

  it('settles a failed snapshot without waiting for SSE', async () => {
    await putGenerationQueueItem(queueItem());
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'failed',
      progress: 0,
      error: 'upstream failed',
    });

    await recoverGenerationJobs();

    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'failed',
      queueStatus: 'interrupted',
      error: 'upstream failed',
      materializationStatus: 'failed',
    });
    expect(mocks.subscribe).not.toHaveBeenCalled();
    await expect(listGenerationQueueItems()).resolves.toEqual([
      expect.objectContaining({ status: 'interrupted', error: 'upstream failed' }),
    ]);
  });

  it('materializes completed snapshot assets without waiting for SSE', async () => {
    await putGenerationQueueItem(queueItem());
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'completed',
      progress: 1,
      assets: [{ id: 'asset-1', name: 'video.mp4', mimeType: 'video/mp4' }],
    });
    mocks.materializeGenerationResult.mockImplementation(async (_port, current: GenerationRecord) => ({
      created: true,
      record: { ...current, materializationStatus: 'completed' },
    }));
    mocks.linkGeneratedCanvasAssets.mockResolvedValue([
      { id: 'asset-1', type: 'video', mimeType: 'video/mp4', url: '/api/files/videos/video.mp4' },
    ]);

    await recoverGenerationJobs();

    expect(mocks.materializeGenerationResult).toHaveBeenCalledTimes(1);
    expect(mocks.materializeGenerationResult).toHaveBeenCalledWith(
      expect.any(Object),
      expect.any(Object),
      expect.objectContaining({
        assetId: 'asset-1',
        downloadUrl: undefined,
      }),
      'job-1',
    );
    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'completed',
      queueStatus: 'terminal',
      progress: 1,
      materializationStatus: 'completed',
    });
    expect(mocks.subscribe).not.toHaveBeenCalled();
    await expect(listGenerationQueueItems()).resolves.toEqual([
      expect.objectContaining({ status: 'terminal' }),
    ]);
  });

  it('ignores queue items that belong to another canvas', async () => {
    await putGenerationQueueItem({
      ...queueItem(),
      id: 'record-other',
      projectId: 'project-2',
      canvasId: 'canvas-2',
      producerNodeId: 'node-other',
      generationRecordId: 'record-other',
      status: 'terminal',
    });
    useCanvasStore.setState({ generationRecords: [] });
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'completed',
      progress: 1,
    });

    await recoverGenerationJobs();

    expect(mocks.getJob).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().generationRecords).toEqual([]);
  });

  it('clears stale materializing state for a completed snapshot without assets', async () => {
    await putGenerationQueueItem(queueItem());
    useCanvasStore.setState({
      generationRecords: [{
        ...record(),
        status: 'completed',
        queueStatus: 'terminal',
        progress: 1,
        materializationStatus: 'materializing',
      }],
    });
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'completed',
      progress: 1,
    });

    await recoverGenerationJobs();

    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'completed',
      queueStatus: 'terminal',
      materializationStatus: 'completed',
    });
  });

  it('preserves capability metadata when rebuilding a missing record', async () => {
    await putGenerationQueueItem({
      ...queueItem(),
      requestSnapshot: {
        capabilityId: 'image.generate',
        sourceAssetIds: [],
        params: { prompt: 'castle', model: 'image-model' },
      },
      status: 'terminal',
    });
    useCanvasStore.setState({ generationRecords: [] });
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'completed',
      progress: 1,
    });

    await recoverGenerationJobs();

    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      id: 'record-1',
      actionId: 'generateImage',
      model: 'image-model',
      params: expect.objectContaining({ capabilityId: 'image.generate' }),
      status: 'completed',
    });
  });

  it('settles an active record when its local queue item is missing', async () => {
    useCanvasStore.setState({ generationRecords: [record()] });
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'completed',
      progress: 1,
    });

    await recoverGenerationJobs();

    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'completed',
      queueStatus: 'terminal',
      materializationStatus: 'completed',
    });
  });

  it('settles result landing when the produced asset is already attached', async () => {
    useCanvasStore.setState({
      generationRecords: [{
        ...record(),
        status: 'completed',
        queueStatus: 'terminal',
        materializationStatus: 'materializing',
        materializedAssetIds: ['asset-1'],
      }],
    });

    await recoverGenerationJobs();

    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'completed',
      queueStatus: 'terminal',
      materializationStatus: 'completed',
      materializationError: undefined,
    });
  });

  it('settles a terminal queue item even when the job snapshot is unavailable', async () => {
    await putGenerationQueueItem({ ...queueItem(), status: 'terminal' });
    mocks.getJob.mockRejectedValue(new Error('offline'));

    await recoverGenerationJobs();

    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'completed',
      queueStatus: 'terminal',
      materializationStatus: 'completed',
    });
  });

  it('clears result landing for a completed terminal queue item without a snapshot', async () => {
    await putGenerationQueueItem({ ...queueItem(), status: 'terminal' });
    useCanvasStore.setState({
      generationRecords: [{
        ...record(),
        status: 'completed',
        queueStatus: 'terminal',
        progress: 1,
        materializationStatus: 'materializing',
      }],
    });
    mocks.getJob.mockRejectedValue(new Error('offline'));

    await recoverGenerationJobs();

    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'completed',
      queueStatus: 'terminal',
      materializationStatus: 'completed',
    });
  });

  it('retries result materialization for a completed terminal queue item', async () => {
    await putGenerationQueueItem({ ...queueItem(), status: 'terminal' });
    useCanvasStore.setState({
      generationRecords: [{
        ...record(),
        status: 'completed',
        queueStatus: 'terminal',
        progress: 1,
        materializationStatus: 'failed',
      }],
    });
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'completed',
      progress: 1,
      assets: [{ id: 'asset-1', name: 'video.mp4', mimeType: 'video/mp4' }],
    });
    mocks.materializeGenerationResult.mockImplementation(async (_port, current: GenerationRecord) => ({
      created: true,
      record: { ...current, materializationStatus: 'completed' },
    }));
    mocks.linkGeneratedCanvasAssets.mockResolvedValue([
      { id: 'asset-1', type: 'video', mimeType: 'video/mp4', url: '/api/files/videos/video.mp4' },
    ]);

    await recoverGenerationJobs();

    expect(mocks.materializeGenerationResult).toHaveBeenCalledTimes(1);
    expect(useCanvasStore.getState().generationRecords[0]).toMatchObject({
      status: 'completed',
      materializationStatus: 'completed',
    });
  });

  it('rebuilds a missing record without duplicating an already hydrated result slot', async () => {
    await putGenerationQueueItem({ ...queueItem(), status: 'terminal' });
    useCanvasStore.setState({
      nodes: [
        node(),
        {
          id: 'slot-1',
          type: 'video',
          position: { x: 400, y: 0 },
          data: {
            kind: 'video',
            label: '结果输出',
            prompt: '',
            status: 'success',
            accent: '#fff',
            assetId: 'asset-1',
            mediaLoadState: 'ready',
          },
        },
      ],
      edges: [{
        id: 'edge-1',
        source: 'node-1',
        sourceHandle: 'video',
        target: 'slot-1',
        targetHandle: 'origin',
        type: 'canvas',
        data: { resultSlot: true, resultSlotOrder: 0 },
      }],
      generationRecords: [],
    });
    mocks.getJob.mockResolvedValue({
      jobId: 'job-1',
      status: 'completed',
      progress: 1,
      assets: [{ id: 'asset-1', name: 'video.mp4', mimeType: 'video/mp4' }],
    });
    mocks.materializeGenerationResult.mockImplementation(async (_port, current: GenerationRecord) => ({
      created: true,
      record: {
        ...current,
        status: 'completed',
        materializationStatus: 'completed',
        completedAt: 2,
      },
      resultNodeId: 'node-1',
    }));
    mocks.linkGeneratedCanvasAssets.mockResolvedValue([
      { id: 'asset-1', type: 'video', mimeType: 'video/mp4', url: '/api/files/videos/video.mp4' },
    ]);

    await recoverGenerationJobs();

    expect(mocks.materializeGenerationResult).toHaveBeenCalledTimes(1);
    expect(useCanvasStore.getState().generationRecords).toEqual([
      expect.objectContaining({
        id: 'record-1',
        producerNodeId: 'node-1',
        jobId: 'job-1',
        status: 'completed',
        queueStatus: 'terminal',
        materializationStatus: 'completed',
        resultSlots: [
          expect.objectContaining({
            nodeId: 'slot-1',
            kind: 'video',
            order: 0,
          }),
        ],
      }),
    ]);
  });
});
