import { beforeEach, describe, expect, it } from 'vitest';
import type { CanvasEdge, CanvasNode } from '../types';
import { canvasQueryService } from './canvasQueryService';
import { useCanvasStore } from '../store/canvasStore';

const node = (id: string, kind: CanvasNode['data']['kind'], assetId?: string): CanvasNode => ({
  id,
  type: kind,
  position: { x: 0, y: 0 },
  data: {
    kind,
    label: id,
    prompt: '',
    status: assetId ? 'success' : 'idle',
    accent: '#fff',
    assetId,
  },
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

describe('canvas query media relay contract', () => {
  beforeEach(() => {
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [],
      edges: [],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: null,
      dragStartSnapshot: null,
      revision: 0,
    });
  });

  it('describes an empty result slot and its producer binding', () => {
    const producer = node('producer', 'textToImage');
    const slot = node('slot', 'image');
    useCanvasStore.setState({
      nodes: [producer, slot],
      edges: [edge('edge-slot', producer.id, slot.id, { resultSlot: true, resultSlotOrder: 0 })],
    });

    const result = canvasQueryService.getNode('project-1', slot.id);

    expect(result.result).toMatchObject({
      placeholder: {
        media: 'image',
        state: 'empty',
        resultSlot: true,
        producerNodeId: producer.id,
        order: 0,
      },
    });
  });

  it('reports required inputs that block a downstream processor', () => {
    const consumer = node('consumer', 'imageToImage');
    useCanvasStore.setState({ nodes: [consumer], edges: [] });

    const result = canvasQueryService.getNode('project-1', consumer.id);

    expect(result.result?.missingInputs).toEqual([
      expect.objectContaining({
        portId: 'source',
        media: 'image',
      }),
    ]);
  });

  it('keeps result slot metadata in edge reads', () => {
    const producer = node('producer', 'textToImage');
    const slot = node('slot', 'image');
    useCanvasStore.setState({
      nodes: [producer, slot],
      edges: [edge('edge-slot', producer.id, slot.id, { resultSlot: true, resultSlotOrder: 2 })],
    });

    const result = canvasQueryService.listEdges('project-1');

    expect(result.result).toEqual([
      expect.objectContaining({
        id: 'edge-slot',
        resultSlot: true,
        resultSlotOrder: 2,
      }),
    ]);
  });

  it('returns unambiguous media relay repair suggestions with the graph state', () => {
    const producer = node('producer', 'textToImage');
    const consumer = node('consumer', 'imageToImage');
    useCanvasStore.setState({ nodes: [producer, consumer], edges: [] });

    const result = canvasQueryService.getState('project-1');

    expect(result.result?.repairSuggestions).toEqual([
      expect.objectContaining({
        producerNodeId: producer.id,
        consumerNodeId: consumer.id,
        inputPortId: 'source',
        kind: 'image',
      }),
    ]);
  });
});
