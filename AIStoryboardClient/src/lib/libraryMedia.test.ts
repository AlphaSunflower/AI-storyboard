import { describe, expect, it, vi } from 'vitest';
import type { AgentMessage } from '../api/agent';
import {
  libraryMediaIdFromUrl,
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
