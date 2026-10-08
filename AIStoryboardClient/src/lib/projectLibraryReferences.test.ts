import { afterEach, describe, expect, it, vi } from 'vitest';
import { assetUrl } from '../config';
import { projectLibraryReferencesToDataUrls } from './projectLibraryReferences';

describe('projectLibraryReferencesToDataUrls', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches selected project library images and returns data URIs', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['image-bytes'], { type: 'image/png' }),
    } as Response);

    const uris = await projectLibraryReferencesToDataUrls([{
      type: 'image',
      purpose: 'image',
      url: '/api/library/media/m1/content?token=1',
      role: 'REFERENCE',
      sourceType: 'library_asset',
      sourceId: 'a1',
      mediaId: 'm1',
      fileName: 'reference.png',
      mimeType: 'image/png',
      sizeBytes: 11,
    }]);

    expect(fetchMock).toHaveBeenCalledWith(
      assetUrl('/api/library/media/m1/content?token=1'),
    );
    expect(uris).toHaveLength(1);
    expect(uris[0]).toMatch(/^data:image\/png;base64,/);
  });

  it('rejects a project library item when it is not an image', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['video-bytes'], { type: 'video/mp4' }),
    } as Response);

    await expect(projectLibraryReferencesToDataUrls([{
      type: 'image',
      purpose: 'image',
      url: '/api/library/media/m1/content',
      role: 'REFERENCE',
      sourceType: 'library_asset',
      sourceId: 'a1',
      mediaId: 'm1',
      fileName: 'wrong.mp4',
      mimeType: 'image/png',
      sizeBytes: 11,
    }])).rejects.toThrow('项目库素材不是图片：wrong.mp4');
  });
});
