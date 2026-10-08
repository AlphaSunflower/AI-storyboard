import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  downloadCanvasAssetBlob,
  linkGeneratedCanvasAssets,
  uploadCanvasAsset,
} from './canvasAssets';
import { clearToken, setJobToken } from './jobAuth';

beforeEach(() => {
  setJobToken(validJwt(), 'user-1');
});

afterEach(() => {
  clearToken();
  vi.restoreAllMocks();
});

describe('canvas asset API', () => {
  it('uploads a Blob and returns the database asset id', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      id: 'asset-1',
      canvasId: 'canvas-1',
      type: 'image',
      mimeType: 'image/png',
      objectUrl: '/api/files/images/a.png',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));

    const asset = await uploadCanvasAsset(
      'canvas-1', 'node-1', 'image', new Blob(['x'], { type: 'image/png' }), 'a.png');

    expect(asset.id).toBe('asset-1');
    expect(asset.url).toBe('/api/files/images/a.png');
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.body).toBeInstanceOf(FormData);
  });

  it('links generated assets by generation record id', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify([{
      id: 'asset-video',
      type: 'video',
      mimeType: 'video/mp4',
      objectUrl: '/api/files/videos/v.mp4',
    }]), { status: 200, headers: { 'Content-Type': 'application/json' } }));

    const assets = await linkGeneratedCanvasAssets('canvas-1', 'node-1', 'generation-1');

    expect(assets[0].id).toBe('asset-video');
    expect(assets[0].url).toBe('/api/files/videos/v.mp4');
  });

  it('downloads a durable canvas asset through the authenticated canvas endpoint', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(new Blob(['video']), {
      status: 200,
      headers: { 'Content-Type': 'video/mp4' },
    }));

    const blob = await downloadCanvasAssetBlob({
      id: 'asset-video',
      type: 'video',
      mimeType: 'video/mp4',
      url: '/api/files/videos/v.mp4',
    });

    expect(blob.type).toBe('video/mp4');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/canvas/assets/asset-video',
      expect.objectContaining({ headers: expect.any(Headers) }),
    );
  });

  it('falls back to the canvas asset endpoint when no durable file URL is available', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(new Blob(['image']), {
      status: 200,
      headers: { 'Content-Type': 'image/png' },
    }));

    const blob = await downloadCanvasAssetBlob({
      id: 'asset-image',
      type: 'image',
      mimeType: 'image/png',
      url: '',
    });

    expect(blob.type).toBe('image/png');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/canvas/assets/asset-image',
      expect.objectContaining({ headers: expect.any(Headers) }),
    );
  });
});

function validJwt(): string {
  const encode = (value: object) => btoa(JSON.stringify(value))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`;
}
