import { afterEach, describe, expect, it, vi } from 'vitest';
import { importCanvasLibraryGroup } from './canvasProjectLibrary';

describe('canvasProjectLibrary', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('imports every canvas asset returned for an asset set', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => [
        {
          id: 'canvas-image',
          canvasId: 'canvas-1',
          type: 'image',
          mimeType: 'image/png',
          objectUrl: '/api/files/images/reference.png',
          libraryAssetId: 'asset-image',
          librarySyncState: 'ready',
        },
        {
          id: 'canvas-video',
          canvasId: 'canvas-1',
          type: 'video',
          mimeType: 'video/mp4',
          objectUrl: '/api/files/videos/reference.mp4',
          libraryAssetId: 'asset-video',
          librarySyncState: 'ready',
        },
      ],
    }) as Response);
    vi.stubGlobal('fetch', fetchMock);

    const imported = await importCanvasLibraryGroup('canvas-1', 'group-1', 'node-1');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/canvas/project-library/import-group',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          canvasId: 'canvas-1',
          libraryAssetId: 'group-1',
          producerNodeId: 'node-1',
        }),
      }),
    );
    expect(imported.map((asset) => asset.type)).toEqual(['image', 'video']);
    expect(imported.map((asset) => asset.url)).toEqual([
      '/api/files/images/reference.png',
      '/api/files/videos/reference.mp4',
    ]);
  });
});
