import { beforeEach, describe, expect, it, vi } from 'vitest';
import { canvasCommandService } from './canvasCommandService';
import { useCanvasStore } from '../store/canvasStore';
import { createPersistedCanvasAsset } from '../services/canvasMedia';

vi.mock('../services/canvasMedia', () => ({
  createPersistedCanvasAsset: vi.fn(async () => ({
    id: 'asset-1',
    name: 'reference.png',
    mimeType: 'image/png',
    kind: 'image',
    width: 640,
    height: 480,
    originalBlob: new Blob(['image'], { type: 'image/png' }),
    createdAt: Date.now(),
    libraryState: 'project',
  })),
}));

describe('canvas media import', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      new Blob(['image'], { type: 'image/png' }),
      { status: 200, headers: { 'Content-Type': 'image/png' } },
    )));
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [],
      edges: [],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: null,
      dragStartSnapshot: null,
      revision: 0,
    });
  });

  it('downloads a carried media reference and creates a real canvas node', async () => {
    const imported = await canvasCommandService.importMedia(
      { projectId: 'project-1', source: 'agent' },
      {
        kind: 'image',
        url: 'https://example.test/reference.png',
        name: 'reference.png',
        mimeType: 'image/png',
      },
    );

    expect(imported.ok).toBe(true);
    expect(createPersistedCanvasAsset).toHaveBeenCalledOnce();
    expect(useCanvasStore.getState().nodes).toHaveLength(1);
    expect(useCanvasStore.getState().nodes[0].data.assetId).toBe('asset-1');
  });

  it('resolves relative Moon file URLs through the configured chat origin', async () => {
    await canvasCommandService.importMedia(
      { projectId: 'project-1', source: 'agent' },
      {
        kind: 'image',
        url: '/api/files/images/reference.png',
        name: 'reference.png',
        mimeType: 'image/png',
      },
    );

    expect(fetch).toHaveBeenCalledWith('http://localhost:5173/api/files/images/reference.png');
  });
});
