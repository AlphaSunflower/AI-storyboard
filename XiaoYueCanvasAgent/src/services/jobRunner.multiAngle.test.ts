// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CanvasEdge, CanvasNode } from '../types';

const mocks = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  submit: vi.fn(),
  subscribe: vi.fn(),
}));

vi.mock('./canvasAssets', () => ({
  uploadCanvasAsset: vi.fn(),
  linkGeneratedCanvasAssets: vi.fn(),
  listCanvasAssets: vi.fn().mockResolvedValue([]),
}));

vi.mock('./capabilities', () => ({
  jobAdapter: {
    getCapabilities: mocks.getCapabilities,
    submit: mocks.submit,
    subscribe: mocks.subscribe,
  },
}));

import { submitImageMultiAngle } from './jobRunner';
import { useCanvasStore } from '../store/canvasStore';

const sourceNode: CanvasNode = {
  id: 'source-node',
  type: 'media',
  position: { x: 0, y: 0 },
  data: {
    kind: 'image',
    label: '源图',
    prompt: '',
    status: 'idle',
    accent: '#fff',
    assetId: 'source-asset',
  },
};

const targetNode: CanvasNode = {
  id: 'target-node',
  type: 'media',
  position: { x: 400, y: 0 },
  data: {
    kind: 'imageToImage',
    label: '源图 · 多角度',
    prompt: '多角度提示词',
    status: 'idle',
    accent: '#b48cff',
    model: 'doubao-seedream-4-5-251128',
    size: '2K',
    n: 1,
  },
};

const edge: CanvasEdge = {
  id: 'edge',
  source: sourceNode.id,
  sourceHandle: 'image',
  target: targetNode.id,
  targetHandle: 'source',
};

describe('submitImageMultiAngle', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getCapabilities.mockResolvedValue([
      { id: 'image.redraw', label: '重绘', category: 'remote', available: true },
    ]);
    mocks.submit.mockResolvedValue({ jobId: 'job-angle' });
    mocks.subscribe.mockReturnValue(() => undefined);
    useCanvasStore.setState({
      projectId: 'project',
      canvasId: 'canvas',
      nodes: [sourceNode, targetNode],
      edges: [edge],
      generationRecords: [],
      executions: [],
      derivedAssetIdsBySource: {},
    });
  });

  it('submits the fixed Seedream parameters through the image edit capability', async () => {
    await submitImageMultiAngle(
      '多角度提示词',
      targetNode.id,
      { multiAngleMode: 'multi', model: 'doubao-seedream-4-5-251128', size: '2K', n: 4 },
      vi.fn(),
      { sourceAssetIds: [], sourceNodeIds: [sourceNode.id] },
    );

    expect(mocks.submit).toHaveBeenCalledWith(expect.objectContaining({
      capabilityId: 'image.redraw',
      params: expect.objectContaining({
        prompt: '多角度提示词',
        model: 'doubao-seedream-4-5-251128',
        size: '2K',
        n: 4,
        multiAngleMode: 'multi',
        actionId: 'generate',
        mode: 'image-to-image',
      }),
    }), undefined);
  });

  it('rejects a request whose count does not match the selected mode', async () => {
    await expect(submitImageMultiAngle(
      '多角度提示词',
      targetNode.id,
      { multiAngleMode: 'multi', model: 'doubao-seedream-4-5-251128', size: '2K', n: 1 },
      vi.fn(),
      { sourceAssetIds: [], sourceNodeIds: [sourceNode.id] },
    )).rejects.toThrow('多角度参数与模式不匹配');

    expect(mocks.submit).not.toHaveBeenCalled();
  });
});
