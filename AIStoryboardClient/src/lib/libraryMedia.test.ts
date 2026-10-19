import { describe, expect, it, vi } from 'vitest';
import type { AgentMessage } from '../api/agent';
import {
  libraryMediaIdFromUrl,
  libraryThumbnailUrl,
  refreshLibraryMediaAttachments,
  refreshLibraryMediaMessages,
} from './libraryMedia';

function media(contentUrl: string) {
  return {
    id: 'media-1',
    blobId: 'blob-1',
    type: 'IMAGE' as const,
    url: '/api/library/media/media-1/content',
    contentUrl,
    objectKey: 'images/a.png',
    fileName: 'a.png',
    relativePath: null,
    mimeType: 'image/png',
    sizeBytes: 10,
    width: 1,
    height: 1,
    durationSeconds: null,
    sortOrder: 0,
    role: 'REFERENCE' as const,
    primary: true,
    constraintText: null,
  };
}

describe('library thumbnails', () => {
  it('rewrites a signed image cover to the thumbnail endpoint', () => {
    expect(libraryThumbnailUrl('/api/files/images/a.png?token=123.sig', 256))
      .toBe('/api/library/media/thumbnails?path=%2Fapi%2Ffiles%2Fimages%2Fa.png&token=123.sig&size=256');
  });

  it('leaves non-image and unsigned URLs untouched', () => {
    expect(libraryThumbnailUrl('/api/files/docs/a.pdf?token=1.sig')).toBe('/api/files/docs/a.pdf?token=1.sig');
    expect(libraryThumbnailUrl('/api/files/images/a.png')).toBe('/api/files/images/a.png');
    expect(libraryThumbnailUrl('https://cdn.example.com/a.png')).toBe('https://cdn.example.com/a.png');
  });
});

describe('library media URL refresh', () => {
  it('extracts the stable media id from a signed content URL', () => {
    expect(libraryMediaIdFromUrl(
      '/api/library/media/media-1/content?token=1790734676.old',
    )).toBe('media-1');
  });

  it('refreshes expired signed URLs in message attachments without mutating history', async () => {
    const message: AgentMessage = {
      id: 'message-1',
      conversationId: 'conversation-1',
      role: 'user',
      content: '',
      createdAt: new Date().toISOString(),
      metadata: {
        messageInput: {
          picUrls: ['/api/library/media/media-1/content?token=1.old'],
          attachments: [{
            url: '/api/library/media/media-1/content?token=1.old',
            name: 'a.png',
            mimeType: 'image/png',
            kind: 'image',
            source: 'library',
          }],
        },
      },
    };
    const resolver = vi.fn().mockResolvedValue(
      media('/api/library/media/media-1/content?token=2.fresh'),
    );

    const refreshed = await refreshLibraryMediaMessages([message], resolver);

    expect(resolver).toHaveBeenCalledWith('media-1');
    expect(refreshed[0].metadata?.messageInput?.picUrls?.[0])
      .toBe('/api/library/media/media-1/content?token=2.fresh');
    expect(refreshed[0].metadata?.messageInput?.attachments?.[0]).toMatchObject({
      url: '/api/library/media/media-1/content?token=2.fresh',
      mediaId: 'media-1',
      source: 'library',
    });
    expect(message.metadata?.messageInput?.attachments?.[0].url)
      .toBe('/api/library/media/media-1/content?token=1.old');
  });

  it('refreshes pending composer attachments before reusing them', async () => {
    const refreshed = await refreshLibraryMediaAttachments([{
      url: '/api/library/media/media-1/content?token=1.old',
      name: 'a.png',
      mimeType: 'image/png',
      kind: 'image',
      source: 'library',
    }], async () => media('/api/library/media/media-1/content?token=3.fresh'));

    expect(refreshed[0]).toMatchObject({
      url: '/api/library/media/media-1/content?token=3.fresh',
      mediaId: 'media-1',
    });
  });
});
