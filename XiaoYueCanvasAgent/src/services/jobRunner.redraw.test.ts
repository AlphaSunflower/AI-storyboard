// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RedrawMaskDocument } from '../types';

const mocks = vi.hoisted(() => ({
  uploadCanvasAsset: vi.fn(),
  rasterizeRedrawMask: vi.fn(),
  getCapabilities: vi.fn(),
  submit: vi.fn(),
  subscribe: vi.fn(),
}));

vi.mock('./canvasAssets', () => ({
  uploadCanvasAsset: mocks.uploadCanvasAsset,
  linkGeneratedCanvasAssets: vi.fn(),
  listCanvasAssets: vi.fn().mockResolvedValue([]),
}));

vi.mock('../utils/redrawMask', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/redrawMask')>();
  return { ...actual, rasterizeRedrawMask: mocks.rasterizeRedrawMask };
});

vi.mock('./capabilities', () => ({
  jobAdapter: {
    getCapabilities: mocks.getCapabilities,
    submit: mocks.submit,
    subscribe: mocks.subscribe,
  },
}));

import { submitImageRedraw } from './jobRunner';
import { useCanvasStore } from '../store/canvasStore';

const redraw: RedrawMaskDocument = {
  version: 2,
  sourceAssetId: 'source',
  marks: [{
    id: 'region-1',
    type: 'rectangle',
    operation: 'paint',
    start: { x: .1, y: .1 },
    end: { x: .8, y: .8 },
    intent: '改成蓝色织物',
    color: '#59d8ff',
    createdAt: 1,
  }],
};

describe('submitImageRedraw', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.rasterizeRedrawMask.mockResolvedValue(new Blob(['mask'], { type: 'image/png' }));
    mocks.uploadCanvasAsset.mockResolvedValue({ id: 'mask-asset', url: '/mask.png', type: 'image', mimeType: 'image/png' });
    mocks.getCapabilities.mockResolvedValue([{ id: 'image.redraw', label: '重绘', category: 'remote', available: true }]);
    mocks.submit.mockResolvedValue({ jobId: 'job-redraw' });
    mocks.subscribe.mockReturnValue(() => undefined);
    useCanvasStore.setState({
      projectId: 'project',
      canvasId: 'canvas',
      nodes: [],
      edges: [],
      generationRecords: [],
      executions: [],
    });
  });

  it('uploads the generated mask and submits image.redraw', async () => {
    await submitImageRedraw(
      '请修改选区',
      'image-to-image',
      redraw,
      { width: 64, height: 48 },
      vi.fn(),
      { sourceAssetIds: [], sourceNodeIds: [], params: { model: 'gpt-image-1', size: '1024x1024' } },
    );

    expect(mocks.uploadCanvasAsset).toHaveBeenCalledWith(
      'canvas',
      'image-to-image',
      'image',
      expect.any(Blob),
      'image-to-image-redraw-mask.png',
    );
    expect(mocks.submit).toHaveBeenCalledWith(expect.objectContaining({
      capabilityId: 'image.redraw',
      params: expect.objectContaining({
        prompt: '请修改选区',
        model: 'gpt-image-1',
        redrawMaskAssetId: 'mask-asset',
        mode: 'image-to-image',
      }),
    }), undefined);
  });
});
