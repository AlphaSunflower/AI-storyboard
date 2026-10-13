import { describe, expect, it } from 'vitest';
import {
  buildCanvasRequestContext,
  filterCanvasReferenceUrls,
  mediaKindFromUrl,
  parseCanvasEditContext,
  parseCanvasEditLock,
  parseCanvasMediaParam,
  rebindCanvasEditContext,
  resolveCanvasModePhase,
  withoutCanvasMediaRef,
} from './canvasEditMode';

describe('canvas edit mode', () => {
  it('parses a canvas edit mode embedded in chat query parameters', () => {
    expect(parseCanvasEditContext('?surface=canvas-edit&projectId=project-1&canvasId=canvas-1')).toEqual({
      mode: 'canvas-edit',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [],
    });
  });

  it('ignores query parameters that do not request canvas edit mode', () => {
    expect(parseCanvasEditContext('?embed=1&surface=chat')).toBeNull();
    expect(parseCanvasEditContext('?surface=canvas-edit&projectId=project-1')).toBeNull();
  });

  it('reads the canvas edit lock handed down by the canvas sub-site', () => {
    expect(parseCanvasEditLock('?embed=1&surface=canvas-edit&canvasLock=1')).toBe(true);
    expect(parseCanvasEditLock('?embed=1&surface=canvas-edit')).toBe(false);
    expect(parseCanvasEditLock('?canvasLock=0')).toBe(false);
  });

  it('keeps pinned media references and adds current image and asset references', () => {
    const context = {
      mode: 'canvas-edit' as const,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [
        {
          kind: 'video' as const,
          assetId: 'video-1',
          url: '/api/files/videos/video-1.mp4',
          source: 'upload' as const,
        },
      ],
    };

    expect(buildCanvasRequestContext(context, {
      picUrls: ['/api/files/images/ref-1.png'],
      assetIds: ['asset-1'],
      mediaRefs: [
        {
          kind: 'image',
          url: '/api/files/images/ref-1.png',
          source: 'upload',
        },
      ],
    })).toEqual({
      mode: 'canvas-edit',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [
        {
          kind: 'video',
          assetId: 'video-1',
          url: '/api/files/videos/video-1.mp4',
          source: 'upload',
        },
        {
          kind: 'image',
          url: '/api/files/images/ref-1.png',
          source: 'upload',
        },
        {
          kind: 'file',
          assetId: 'asset-1',
          source: 'project',
        },
      ],
    });
  });

  it('deduplicates the same media reference when one URL is absolute and one is relative', () => {
    const context = {
      mode: 'canvas-edit' as const,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [{
        kind: 'image' as const,
        url: 'http://localhost:5173/api/files/images/ref.png',
        source: 'upload' as const,
      }],
    };

    const result = buildCanvasRequestContext(context, {
      picUrls: ['/api/files/images/ref.png'],
    });

    expect(result?.mediaRefs).toHaveLength(1);
  });

  it('removes a media reference and keeps it excluded until added back', () => {
    const context = {
      mode: 'canvas-edit' as const,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [{ kind: 'image' as const, url: 'http://localhost:3000/api/files/images/removed.jpg', source: 'upload' as const }],
    };

    const removed = withoutCanvasMediaRef(context, '/api/files/images/removed.jpg');
    expect(removed.mediaRefs).toEqual([]);
    expect(removed.excludedMediaUrls).toContain('/api/files/images/removed.jpg');

    const restored = buildCanvasRequestContext(removed, {
      picUrls: ['/api/files/images/removed.jpg'],
    });
    expect(restored?.mediaRefs).toHaveLength(1);
    expect(restored?.excludedMediaUrls).toEqual([]);
  });

  it('removes duplicate reference-image chips for canvas media', () => {
    const context = {
      mode: 'canvas-edit' as const,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [{
        kind: 'image' as const,
        url: 'http://localhost:3000/api/files/images/duplicate.jpg',
        source: 'upload' as const,
      }],
    };

    expect(filterCanvasReferenceUrls([
      '/api/files/images/duplicate.jpg',
      '/api/files/images/keep.jpg',
    ], context)).toEqual(['/api/files/images/keep.jpg']);
    expect(filterCanvasReferenceUrls(['/api/files/images/keep.jpg'], null))
      .toEqual(['/api/files/images/keep.jpg']);
  });

  it('derives the canvas workflow phase from binding, lock, and exit state', () => {
    const context = {
      mode: 'canvas-edit' as const,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [],
    };
    expect(resolveCanvasModePhase(null, null, false)).toBe('natural');
    expect(resolveCanvasModePhase(null, { formToken: 'token-1' }, false)).toBe('binding');
    expect(resolveCanvasModePhase(context, null, false)).toBe('locked');
    expect(resolveCanvasModePhase(context, null, true)).toBe('exit_pending');
  });

  it('rebinds the locked conversation to a newly selected canvas in the same project', () => {
    const context = {
      mode: 'canvas-edit' as const,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [],
    };

    expect(rebindCanvasEditContext(context, 'project-1', 'canvas-2')).toEqual({
      ...context,
      canvasId: 'canvas-2',
    });
    expect(rebindCanvasEditContext(context, 'project-2', 'canvas-2')).toBeNull();
  });

  it('normalizes null media collections restored from legacy conversation data', () => {
    const legacy = {
      mode: 'canvas-edit' as const,
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: null,
      excludedMediaUrls: null,
    } as unknown as Parameters<typeof buildCanvasRequestContext>[0];

    expect(buildCanvasRequestContext(legacy, {
      picUrls: ['/api/files/images/ref.png'],
    })).toEqual({
      mode: 'canvas-edit',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      mediaRefs: [{
        kind: 'image',
        url: '/api/files/images/ref.png',
        source: 'upload',
      }],
    });
    expect(filterCanvasReferenceUrls(['/api/files/images/ref.png'], legacy!)).toEqual([
      '/api/files/images/ref.png',
    ]);
  });

  it('parses the media the canvas site forwards into the embedded chat', () => {
    // 画布子站把参数原样透传进 iframe URL，URLSearchParams 取值时已解码
    const raw = JSON.stringify([
      '/api/files/images/dragon.png',
      { url: '/api/files/videos/clip.mp4', kind: 'video' },
    ]);

    expect(parseCanvasMediaParam(raw)).toEqual([
      { kind: 'image', url: '/api/files/images/dragon.png', source: 'upload' },
      { kind: 'video', url: '/api/files/videos/clip.mp4', source: 'upload' },
    ]);
  });

  it('ignores malformed media parameters instead of breaking the chat boot', () => {
    expect(parseCanvasMediaParam(null)).toEqual([]);
    expect(parseCanvasMediaParam('not-json')).toEqual([]);
    expect(parseCanvasMediaParam('{"url":"/a.png"}')).toEqual([]);
    expect(parseCanvasMediaParam(JSON.stringify(['', 42, { kind: 'image' }, { url: ' ' }]))).toEqual([]);
  });

  it('infers the media kind from the file extension', () => {
    expect(mediaKindFromUrl('/api/files/images/a.webp')).toBe('image');
    expect(mediaKindFromUrl('/api/files/videos/a.MP4')).toBe('video');
    expect(mediaKindFromUrl('/api/files/audio/a.mp3')).toBe('audio');
  });
});
