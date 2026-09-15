import { describe, expect, it } from 'vitest';
import type { CanvasGraphV2 } from './graphContracts';
import { getNodeDefinition, validateRunnable } from './graphContracts';

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
          type: 'batchImageRefine',
          position: { x: 300, y: 0 },
          data: { kind: 'batchImageRefine', label: '批量完善图片', prompt: '', status: 'idle', accent: '#b48cff' },
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

    expect(validateRunnable(graph, 'batch-1', 'refineBatch')).toEqual({ ok: true });
  });
});
