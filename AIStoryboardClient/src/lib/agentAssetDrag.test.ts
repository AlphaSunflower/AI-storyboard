import { describe, expect, it } from 'vitest';
import {
  AGENT_ASSET_DRAG_MIME,
  parseAgentAssetDragPayload,
  serializeAgentAssetDragPayload,
} from './agentAssetDrag';
import { CHAT_MEDIA_DRAG_MIME, PROJECT_LIBRARY_DRAG_MIME } from './projectLibraryDrag';

describe('agent asset drag protocol', () => {
  it('round-trips an image payload', () => {
    const payload = {
      url: '/api/files/images/generated.png',
      name: 'generated.png',
      kind: 'image' as const,
    };

    expect(parseAgentAssetDragPayload(serializeAgentAssetDragPayload(payload))).toEqual(payload);
  });

  it('round-trips a video payload', () => {
    const payload = {
      url: '/api/files/videos/generated.mp4',
      name: 'generated.mp4',
      kind: 'video' as const,
    };

    expect(parseAgentAssetDragPayload(serializeAgentAssetDragPayload(payload))).toEqual(payload);
  });

  it('uses a MIME distinct from the other drag protocols', () => {
    expect(AGENT_ASSET_DRAG_MIME).not.toBe(PROJECT_LIBRARY_DRAG_MIME);
    expect(AGENT_ASSET_DRAG_MIME).not.toBe(CHAT_MEDIA_DRAG_MIME);
  });

  it('rejects malformed payloads', () => {
    expect(parseAgentAssetDragPayload('{broken')).toBeNull();
    expect(parseAgentAssetDragPayload(JSON.stringify({ url: '/x.png', name: 'x.png' }))).toBeNull();
    expect(parseAgentAssetDragPayload(JSON.stringify({ url: '', name: 'x.png', kind: 'image' }))).toBeNull();
    expect(parseAgentAssetDragPayload(JSON.stringify({ url: '/x.png', name: 'x.png', kind: 'audio' }))).toBeNull();
  });
});
