import { describe, expect, it } from 'vitest';
import {
  getNodeDefinition,
  validateEdge,
  validateRunnable,
  type CanvasGraphV2,
  type CanvasNodeKind,
  type CanvasNodeV2,
  type CanvasEdgeV2,
} from './graphContracts';
import { canConnectFromPort, canConnectToPort } from '../store/canvasStore';
import { resolveActionId } from './generationModes';

const node = (id: string, kind: CanvasNodeKind): CanvasNodeV2 => ({
  id,
  type: kind,
  position: { x: 0, y: 0 },
  data: { kind, label: kind, prompt: '', status: 'idle', accent: '#fff' },
});

const graph = (nodes: CanvasNodeV2[], edges: CanvasEdgeV2[] = []): CanvasGraphV2 => ({
  schemaVersion: 2,
  nodes,
  edges,
  viewport: { x: 0, y: 0, zoom: 1 },
  generationRecords: [],
});

describe('validateEdge', () => {
  it('allows the first required edge before the node is runnable', () => {
    const sourceA = node('source-a', 'image');
    const target = node('target', 'firstLastFrameToVideo');
    const value = graph([sourceA, target]);

    const first = validateEdge(value, {
      id: 'edge-a',
      source: sourceA.id,
      sourceHandle: 'image',
      target: target.id,
      targetHandle: 'first',
    }, 'user');

    expect(first).toMatchObject({ ok: true });
    expect(validateRunnable(value, target.id, 'generate')).toMatchObject({
      ok: false,
      code: 'INPUT_REQUIRED',
    });
  });

  it('rejects a missing handle when the target or source has multiple ports', () => {
    const source = node('source', 'image');
    const target = node('target', 'referenceToVideo');
    const value = graph([source, target]);

    expect(validateEdge(value, {
      id: 'edge-missing',
      source: source.id,
      target: target.id,
      targetHandle: 'reference',
    } as CanvasEdgeV2, 'user')).toMatchObject({
      ok: false,
      code: 'HANDLE_REQUIRED',
    });
  });

  it('rejects a handle pair whose declared roles and kinds do not match', () => {
    const source = node('source', 'video');
    const target = node('target', 'referenceToVideo');
    const value = graph([source, target]);

    expect(validateEdge(value, {
      id: 'edge-kind',
      source: source.id,
      sourceHandle: 'video',
      target: target.id,
      targetHandle: 'reference',
    }, 'agent')).toMatchObject({
      ok: false,
      code: 'PORT_KIND_MISMATCH',
    });
  });

  it('requires a unique portOrder for ordered target ports', () => {
    const sourceA = node('source-a', 'image');
    const sourceB = node('source-b', 'image');
    const target = node('target', 'referenceToVideo');
    const value = graph([sourceA, sourceB, target], [{
      id: 'edge-a',
      source: sourceA.id,
      sourceHandle: 'image',
      target: target.id,
      targetHandle: 'reference',
      data: { portOrder: 0 },
    }]);

    expect(validateEdge(value, {
      id: 'edge-b',
      source: sourceB.id,
      sourceHandle: 'image',
      target: target.id,
      targetHandle: 'reference',
      data: { portOrder: 0 },
    }, 'user')).toMatchObject({
      ok: false,
      code: 'PORT_ORDER_CONFLICT',
    });
  });

  it('rejects a cycle even when the proposed edge endpoints are otherwise valid', () => {
    const source = node('source', 'image');
    const target = node('target', 'imageToImage');
    const value = graph([source, target], [{
      id: 'edge-existing',
      source: target.id,
      sourceHandle: 'image',
      target: source.id,
      targetHandle: 'source',
    }]);

    expect(validateEdge(value, {
      id: 'edge-cycle',
      source: source.id,
      sourceHandle: 'image',
      target: target.id,
      targetHandle: 'source',
      data: { portOrder: 1 },
    }, 'pipeline')).toMatchObject({
      ok: false,
      code: 'CYCLE',
    });
  });

});

describe('validateRunnable', () => {
  it('passes only after required and min inputs are satisfied', () => {
    const source = node('source', 'image');
    const target = node('target', 'imageToImage');
    const value = graph([source, target], [{
      id: 'edge',
      source: source.id,
      sourceHandle: 'image',
      target: target.id,
      targetHandle: 'source',
      data: { portOrder: 0 },
    }]);

    expect(validateRunnable(value, target.id, 'generate')).toMatchObject({ ok: true });
  });

  it('accepts image-to-image references connected through the reference port', () => {
    const sourceA = node('source-a', 'image');
    const sourceB = node('source-b', 'image');
    const target = node('target', 'imageToImage');
    const value = graph([sourceA, sourceB, target], [
      {
        id: 'edge-a',
        source: sourceA.id,
        sourceHandle: 'image',
        target: target.id,
        targetHandle: 'reference',
        data: { portOrder: 0 },
      },
      {
        id: 'edge-b',
        source: sourceB.id,
        sourceHandle: 'image',
        target: target.id,
        targetHandle: 'reference',
        data: { portOrder: 1 },
      },
    ]);

    expect(validateRunnable(value, target.id, 'generate')).toMatchObject({ ok: true });
  });
});

describe('node action registry', () => {
  it('keeps both generation capabilities on the merged image node and picks by mode', () => {
    expect(getNodeDefinition('imageToImage')?.actions.map((action) => action.capabilityId)).toEqual([
      'image.generate',
      'image.redraw',
    ]);
    expect(getNodeDefinition('batchImageRefine')?.kind).toBe('imageGeneration');
    expect(getNodeDefinition('textToImage')?.kind).toBe('imageGeneration');
    expect(getNodeDefinition('videoGeneration')?.actions.map((action) => action.id)).toEqual(['generate']);
    expect(resolveActionId('imageToImage', {})).toBe('redraw');
    expect(resolveActionId('batchImageRefine', {})).toBe('redraw');
    expect(resolveActionId('textToImage', {})).toBe('generate');
    expect(resolveActionId('videoGeneration', { mode: 'firstLastFrame' })).toBe('generate');
  });

  it('keeps both shot production actions in the registry', () => {
    expect(getNodeDefinition('shot')?.actions.map((action) => action.id)).toEqual([
      'generateImage',
      'generateVideo',
    ]);
  });
});

describe('port-specific connection menus', () => {
  it('keeps storyboard script and reference inputs separate', () => {
    expect(canConnectToPort('storyboardPlanner', 'script', 'script')).toBe(true);
    expect(canConnectToPort('storyboardPlanner', 'script', 'image')).toBe(false);
    expect(canConnectToPort('storyboardPlanner', 'reference', 'image')).toBe(true);
    expect(canConnectToPort('storyboardPlanner', 'reference', 'script')).toBe(false);
  });

  it('lets a plain text node drive storyboard planning now that script is retired', () => {
    expect(canConnectToPort('storyboardPlanner', 'script', 'text')).toBe(true);
    expect(canConnectFromPort('text', 'text', 'storyboardPlanner')).toBe(true);
  });

  it('filters downstream targets from the exact dragged source handle', () => {
    expect(canConnectFromPort('image', 'image', 'imageToImage')).toBe(true);
    expect(canConnectFromPort('image', 'image', 'imageToVideo')).toBe(true);
    expect(canConnectFromPort('image', 'image', 'firstLastFrameToVideo')).toBe(true);
    expect(canConnectFromPort('image', 'image', 'batchImageRefine')).toBe(true);
    expect(canConnectFromPort('image', 'image', 'referenceToVideo')).toBe(true);
    expect(canConnectFromPort('image', 'image', 'storyboardPlanner')).toBe(true);
    expect(canConnectFromPort('image', 'image', 'shot')).toBe(true);
    expect(canConnectFromPort('image', 'origin', 'imageToImage')).toBe(false);
    expect(canConnectFromPort('video', 'video', 'referenceToVideo')).toBe(true);
    expect(canConnectFromPort('video', 'origin', 'referenceToVideo')).toBe(false);
  });
});
