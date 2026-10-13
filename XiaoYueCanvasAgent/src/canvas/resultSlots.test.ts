import { describe, expect, it } from 'vitest';
import type { CanvasEdge, CanvasNode } from '../types';
import type { CanvasGraphV2 } from './graphContracts';
import {
  suggestMediaRelayRepairs,
  resolveResultSlots,
  validateResultSlotOwnership,
  withResultSlotData,
} from './resultSlots';

const node = (id: string, kind: CanvasNode['data']['kind'], assetId?: string): CanvasNode => ({
  id,
  type: kind,
  position: { x: 0, y: 0 },
  data: { kind, label: id, prompt: '', status: assetId ? 'success' : 'idle', accent: '#fff', assetId },
});

const edge = (
  id: string,
  source: string,
  target: string,
  data?: CanvasEdge['data'],
): CanvasEdge => ({
  id,
  source,
  sourceHandle: 'image',
  target,
  targetHandle: 'origin',
  type: 'canvas',
  data,
});

const graph = (
  nodes: CanvasNode[],
  edges: CanvasEdge[],
): CanvasGraphV2 => ({
  schemaVersion: 2,
  nodes: nodes as CanvasGraphV2['nodes'],
  edges: edges as CanvasGraphV2['edges'],
  viewport: { x: 0, y: 0, zoom: 1 },
  generationRecords: [],
});

describe('result slots', () => {
  it('resolves empty media placeholders in explicit slot order', () => {
    const slots = resolveResultSlots(graph(
      [node('producer', 'textToImage'), node('slot-b', 'image'), node('slot-a', 'image')],
      [
        edge('e-b', 'producer', 'slot-b', { resultSlot: true, resultSlotOrder: 1 }),
        edge('e-a', 'producer', 'slot-a', { resultSlot: true, resultSlotOrder: 0 }),
      ],
    ), 'producer');

    expect(slots.map((slot) => slot.nodeId)).toEqual(['slot-a', 'slot-b']);
  });

  it('ignores media nodes that already contain an asset', () => {
    const slots = resolveResultSlots(graph(
      [node('producer', 'textToImage'), node('filled', 'image', 'asset-1'), node('empty', 'image')],
      [edge('e1', 'producer', 'filled'), edge('e2', 'producer', 'empty')],
    ), 'producer');

    expect(slots.map((slot) => slot.nodeId)).toEqual(['empty']);
  });

  it('rejects a slot connected to multiple producers', () => {
    const conflict = graph(
      [node('producer-a', 'textToImage'), node('producer-b', 'imageToImage'), node('slot', 'image')],
      [edge('e1', 'producer-a', 'slot'), edge('e2', 'producer-b', 'slot')],
    );

    expect(validateResultSlotOwnership(conflict, 'producer-a', [
      { nodeId: 'slot', kind: 'image', order: 0, edgeId: 'e1' },
    ])).toMatchObject({ ok: false, code: 'RESULT_SLOT_CONFLICT' });
  });

  it('marks a new edge to an empty media node as an ordered result slot', () => {
    const source = node('producer', 'textToImage');
    const target = node('slot', 'image');

    const data = withResultSlotData(source, target, [], undefined);

    expect(data).toMatchObject({ resultSlot: true, resultSlotOrder: 0 });
  });

  it('suggests a media relay when one producer can satisfy a missing downstream input', () => {
    const suggestions = suggestMediaRelayRepairs(graph(
      [node('producer', 'textToImage'), node('consumer', 'imageToImage')],
      [],
    ));

    expect(suggestions).toEqual([
      expect.objectContaining({
        producerNodeId: 'producer',
        consumerNodeId: 'consumer',
        inputPortId: 'reference',
        kind: 'image',
      }),
    ]);
  });

  it('does not guess between multiple compatible producers', () => {
    const suggestions = suggestMediaRelayRepairs(graph(
      [node('producer-a', 'textToImage'), node('producer-b', 'textToImage'), node('consumer', 'imageToImage')],
      [],
    ));

    expect(suggestions).toEqual([]);
  });
});
