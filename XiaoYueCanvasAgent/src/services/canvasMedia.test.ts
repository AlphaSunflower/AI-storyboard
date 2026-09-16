import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useCanvasStore } from '../store/canvasStore';
import { useMediaStore } from '../store/mediaStore';
import type { CanvasGraphV2 } from '../canvas/graphContracts';

const canvasAssets = vi.hoisted(() => ({
  list: vi.fn(),
  download: vi.fn(),
  upload: vi.fn(),
}));

vi.mock('./canvasAssets', () => ({
  listCanvasAssets: canvasAssets.list,
  downloadCanvasAssetBlob: canvasAssets.download,
  uploadCanvasAsset: canvasAssets.upload,
}));

vi.mock('./projectStorage', () => ({
  saveAsset: vi.fn(async () => undefined),
}));

vi.mock('../utils/mediaPipeline', () => ({
  createImageAsset: vi.fn(async () => ({ id: 'unused' })),
  createVideoAsset: vi.fn(async () => ({ id: 'unused' })),
}));

import {
  findReplacementCanvasAsset,
  hydrateReferencedCanvasAssets,
  persistExistingCanvasAsset,
} from './canvasMedia';

const graph = (): CanvasGraphV2 => ({
  schemaVersion: 2,
  viewport: { x: 0, y: 0, zoom: 1 },
  nodes: [{
    id: 'result:generation-record-1:agent-asset-1',
    type: 'image',
    position: { x: 0, y: 0 },
    data: { kind: 'image', label: '结果', prompt: '', status: 'success', accent: '#fff', assetId: 'local-asset-1' },
  }],
  edges: [],
  generationRecords: [{
    id: 'generation-record-1',
    producerNodeId: 'imageToImage-1',
    actionId: 'generate',
    attempt: 1,
    status: 'completed',
    materializationStatus: 'completed',
    materializedAssetIds: ['local-asset-1'],
    resultNodeIds: ['result:generation-record-1:agent-asset-1'],
    sources: [],
    prompt: '',
    effectivePrompt: '',
    params: {},
    startedAt: 1,
  }],
});

describe('canvas media reconciliation', () => {
  beforeEach(() => {
    canvasAssets.list.mockReset();
    canvasAssets.download.mockReset();
    canvasAssets.upload.mockReset();
    const value = graph();
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: value.nodes as never,
      edges: [],
      generationRecords: value.generationRecords,
      executions: [],
      past: [],
      future: [],
      selectedNodeId: null,
      dragStartSnapshot: null,
      revision: 0,
    });
    useMediaStore.getState().clearAssets();
  });

  it('finds the server asset by generation result key', () => {
    expect(findReplacementCanvasAsset(useCanvasStore.getState().nodes[0], [{
      id: 'server-asset-1',
      type: 'image',
      mimeType: 'image/png',
      url: '/api/files/images/result.png',
      generationRecordId: 'agent-asset-1',
    }])?.id).toBe('server-asset-1');
  });

  it('keeps hydrating other assets when one download fails', async () => {
    canvasAssets.list.mockResolvedValue([{
      id: 'asset-1',
      type: 'image',
      mimeType: 'image/png',
      url: '/api/files/images/missing.png',
    }]);
    canvasAssets.download.mockRejectedValue(new Error('HTTP 404'));
    useCanvasStore.setState((state) => ({
      nodes: state.nodes.map((node) => ({ ...node, data: { ...node.data, assetId: 'asset-1' } })),
    }));

    await expect(hydrateReferencedCanvasAssets('canvas-1')).resolves.toBeUndefined();
    expect(useMediaStore.getState().assetErrors['asset-1']).toBe('HTTP 404');
  });

  it('uploads a library asset before creating the canvas node reference', async () => {
    canvasAssets.upload.mockResolvedValue({
      id: 'server-asset-2',
      type: 'image',
      mimeType: 'image/png',
      url: '/api/files/images/server.png',
    });
    const persisted = await persistExistingCanvasAsset({
      id: 'local-asset',
      kind: 'image',
      name: 'source.png',
      mimeType: 'image/png',
      bytes: 4,
      width: 1,
      height: 1,
      originalBlob: new Blob(['test'], { type: 'image/png' }),
      thumbnailBlob: new Blob(['test'], { type: 'image/webp' }),
      previewBlob: new Blob(['test'], { type: 'image/webp' }),
      libraryState: 'project',
      createdAt: 1,
    }, 'image-1');

    expect(canvasAssets.upload).toHaveBeenCalledWith(
      'canvas-1', 'image-1', 'image', expect.any(Blob), 'source.png',
    );
    expect(persisted.id).toBe('server-asset-2');
  });

  it('reattaches the newest server asset to an empty result slot after refresh', async () => {
    canvasAssets.list.mockResolvedValue([
      {
        id: 'server-asset-new',
        type: 'image',
        mimeType: 'image/jpeg',
        url: '/api/files/images/new.jpeg',
        producerNodeId: 'imageToImage-1',
        generationRecordId: 'agent-asset-new',
        createdAt: '2026-09-16T08:20:45.000Z',
      },
      {
        id: 'server-asset-old',
        type: 'image',
        mimeType: 'image/jpeg',
        url: '/api/files/images/old.jpeg',
        producerNodeId: 'imageToImage-1',
        generationRecordId: 'agent-asset-old',
        createdAt: '2026-09-16T07:36:58.000Z',
      },
    ]);
    canvasAssets.download.mockResolvedValue(new Blob(['image'], { type: 'image/jpeg' }));
    useCanvasStore.setState({
      nodes: [
        {
          id: 'imageToImage-1',
          type: 'imageToImage',
          position: { x: 0, y: 0 },
          data: { kind: 'imageToImage', label: 'Step 2', prompt: '', status: 'idle', accent: '#fff' },
        },
        {
          id: 'final-output',
          type: 'image',
          position: { x: 400, y: 0 },
          data: { kind: 'image', label: '最终效果图输出', prompt: '', status: 'idle', accent: '#fff' },
        },
      ] as never,
      edges: [{
        id: 'edge-producer-slot',
        source: 'imageToImage-1',
        sourceHandle: 'image',
        target: 'final-output',
        targetHandle: 'origin',
        type: 'canvas',
        data: { resultSlot: true, resultSlotOrder: 0 },
      }],
      generationRecords: [],
    });

    await hydrateReferencedCanvasAssets('canvas-1');

    const finalOutput = useCanvasStore.getState().nodes.find((node) => node.id === 'final-output');
    expect(finalOutput?.data.assetId).toBe('server-asset-new');
    expect(finalOutput?.data.status).toBe('success');
    expect(canvasAssets.download).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'server-asset-new' }),
    );
  });
});
