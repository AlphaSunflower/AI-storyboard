import { describe, expect, it } from 'vitest';
import {
  CHAT_MEDIA_DRAG_MIME,
  PROJECT_LIBRARY_DRAG_MIME,
  buildProjectLibraryDragPayload,
  parseChatMediaDragPayload,
  parseProjectLibraryDragPayload,
  serializeDragPayload,
} from './projectLibraryDrag';

describe('project library drag protocol', () => {
  it('round-trips a project library image payload', () => {
    const payload = {
      kind: 'asset' as const,
      assetId: 'asset-1',
      mediaType: 'IMAGE' as const,
      url: '/api/files/images/reference.png',
      name: '参考图',
    };

    expect(parseProjectLibraryDragPayload(serializeDragPayload(payload))).toEqual(payload);
  });

  it('round-trips an asset group payload', () => {
    const payload = {
      kind: 'group' as const,
      groupId: 'group-1',
      name: '主角集',
    };

    expect(parseProjectLibraryDragPayload(serializeDragPayload(payload))).toEqual(payload);
  });

  it('round-trips conversation media for a library drop destination', () => {
    const payload = {
      projectId: 'project-1',
      conversationId: 'conv-1',
      mediaType: 'VIDEO' as const,
      url: '/api/files/videos/generated.mp4',
      name: '生成视频',
    };

    expect(parseChatMediaDragPayload(serializeDragPayload(payload))).toEqual(payload);
    expect(CHAT_MEDIA_DRAG_MIME).not.toBe(PROJECT_LIBRARY_DRAG_MIME);
  });

  it('rejects malformed drag payloads', () => {
    expect(parseProjectLibraryDragPayload('{broken')).toBeNull();
    expect(parseChatMediaDragPayload(JSON.stringify({ mediaType: 'VIDEO' }))).toBeNull();
  });

  it('builds an external drag payload for a library image', () => {
    expect(buildProjectLibraryDragPayload({
      id: 'asset-1',
      name: '参考图',
      type: 'IMAGE',
      coverUrl: '/api/files/images/reference.png',
    })).toEqual({
      kind: 'asset',
      assetId: 'asset-1',
      mediaType: 'IMAGE',
      url: '/api/files/images/reference.png',
      name: '参考图',
    });
  });
});
