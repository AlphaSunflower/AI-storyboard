import { describe, expect, it } from 'vitest';
import type { CanvasGraphV2 } from './graphContracts';
import { getNodeDefinition, listNodeDefinitions, validateRunnable } from './graphContracts';

describe('node library surface', () => {
  it('hides the retired script node but keeps the definition for stored canvases', () => {
    const kinds = listNodeDefinitions().map((definition) => definition.kind);

    expect(kinds).not.toContain('script');
    expect(kinds).toContain('text');
    // 存量画布里的剧本节点仍要能渲染：定义还在
    expect(getNodeDefinition('script')?.title).toBe('剧本');
  });

  it('hides the retired annotation node but keeps it renderable', () => {
    const kinds = listNodeDefinitions().map((definition) => definition.kind);

    expect(kinds).not.toContain('note');
    // 存量画布里的批注节点仍要能渲染：定义还在
    expect(getNodeDefinition('note')?.title).toBe('批注');
  });
});

describe('image node contract', () => {
  it('keeps image nodes as result containers without generation actions', () => {
    const definition = getNodeDefinition('image');
    expect(definition?.actions.some((action) => action.capabilityId === 'image.generate')).toBe(false);

    const graph: CanvasGraphV2 = {
      schemaVersion: 2,
      nodes: [{
        id: 'image-1',
        type: 'image',
        position: { x: 0, y: 0 },
        data: { kind: 'image', label: '图片', prompt: '', status: 'idle', accent: '#ff775f' },
      }],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: [],
    };
    expect(validateRunnable(graph, 'image-1', 'generateImage')).toEqual({
      ok: false,
      code: 'ACTION_NOT_FOUND',
      message: '节点动作不存在',
    });
  });

  it('accepts legacy ordered inputs without explicit portOrder values', () => {
    const graph: CanvasGraphV2 = {
      schemaVersion: 2,
      nodes: [
        {
          id: 'image-1',
          type: 'image',
          position: { x: 0, y: 0 },
          data: { kind: 'image', label: '图片', prompt: '', status: 'idle', accent: '#ff775f' },
        },
        {
          id: 'batch-1',
          type: 'imageGeneration',
          position: { x: 300, y: 0 },
          data: { kind: 'imageGeneration', label: '图片生成', prompt: '', status: 'idle', accent: '#b48cff', mode: 'batchRefine' },
        },
      ],
      edges: [{
        id: 'edge-1',
        source: 'image-1',
        sourceHandle: 'image',
        target: 'batch-1',
        targetHandle: 'images',
        type: 'canvas',
      }],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: [],
    };

    expect(validateRunnable(graph, 'batch-1', 'redraw')).toEqual({ ok: true });
  });

  it('reports mode-driven missing inputs for merged generators', () => {
    const graph: CanvasGraphV2 = {
      schemaVersion: 2,
      nodes: [
        {
          id: 'video-1',
          type: 'videoGeneration',
          position: { x: 0, y: 0 },
          data: { kind: 'videoGeneration', label: '视频生成', prompt: '', status: 'idle', accent: '#8f9cff', mode: 'firstLastFrame' },
        },
      ],
      edges: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: [],
    };

    expect(validateRunnable(graph, 'video-1', 'generate')).toMatchObject({
      ok: false,
      code: 'INPUT_REQUIRED',
      message: '需要至少 2 张图片',
    });
  });
});

describe('video merge node contract', () => {
  it('accepts ordered video inputs between two and twelve clips', () => {
    const definition = getNodeDefinition('videoMerge');

    expect(definition).toMatchObject({
      kind: 'videoMerge',
      title: '视频合并',
      category: '视频处理',
    });
    expect(definition?.inputs).toEqual([
      expect.objectContaining({
        id: 'clips',
        media: 'video',
        ordered: true,
        min: 2,
        max: 12,
        acceptsKinds: expect.arrayContaining(['video', 'textToVideo', 'imageToVideo', 'videoMerge']),
      }),
    ]);
    expect(definition?.outputs[0]).toMatchObject({ id: 'video', media: 'video' });
  });

  it('requires contiguous clip order before merge', () => {
    const graph: CanvasGraphV2 = {
      schemaVersion: 2,
      nodes: [
        {
          id: 'video-1',
          type: 'video',
          position: { x: 0, y: 0 },
          data: { kind: 'video', label: '视频 1', prompt: '', status: 'success', accent: '#8f9cff', assetId: 'a1' },
        },
        {
          id: 'video-2',
          type: 'video',
          position: { x: 0, y: 300 },
          data: { kind: 'video', label: '视频 2', prompt: '', status: 'success', accent: '#8f9cff', assetId: 'a2' },
        },
        {
          id: 'merge-1',
          type: 'videoMerge',
          position: { x: 500, y: 0 },
          data: { kind: 'videoMerge', label: '视频合并', prompt: '', status: 'idle', accent: '#8f9cff' },
        },
      ],
      edges: [
        {
          id: 'edge-1',
          source: 'video-1',
          sourceHandle: 'video',
          target: 'merge-1',
          targetHandle: 'clips',
          type: 'canvas',
          data: { portOrder: 0 },
        },
        {
          id: 'edge-2',
          source: 'video-2',
          sourceHandle: 'video',
          target: 'merge-1',
          targetHandle: 'clips',
          type: 'canvas',
          data: { portOrder: 2 },
        },
      ],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: [],
    };

    expect(validateRunnable(graph, 'merge-1', 'mergeVideo')).toMatchObject({
      ok: false,
      code: 'PORT_ORDER_REQUIRED',
    });
  });
});
