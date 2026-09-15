import { beforeEach, describe, expect, it } from 'vitest';
import { useCanvasStore } from './canvasStore';

const reset = () => useCanvasStore.setState({
  nodes: [],
  edges: [],
  generationRecords: [],
  past: [],
  future: [],
  selectedNodeId: null,
  dragStartSnapshot: null,
  revision: 0,
});

describe('CanvasGraphV2 store bridge', () => {
  beforeEach(reset);

  it('creates new registry nodes with their real node type', () => {
    const id = useCanvasStore.getState().addNode('textToImage', { x: 10, y: 20 });
    const node = useCanvasStore.getState().nodes.find((item) => item.id === id);

    expect(node).toMatchObject({
      type: 'textToImage',
      width: 520,
      height: 420,
    });
    expect(node?.data.kind).toBe('textToImage');
  });

  it('stores generation records in graph snapshots', () => {
    useCanvasStore.getState().appendGenerationRecord({
      id: 'record-1',
      producerNodeId: 'node-1',
      actionId: 'generate',
      attempt: 1,
      status: 'completed',
      materializationStatus: 'pending',
      materializedAssetIds: [],
      resultNodeIds: [],
      sources: [],
      prompt: 'cat',
      effectivePrompt: 'cat',
      params: {},
      startedAt: 1,
    });

    expect(useCanvasStore.getState().generationRecords).toHaveLength(1);
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().generationRecords).toHaveLength(0);
  });

  it('rejects edges without concrete handles', () => {
    const source = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const target = useCanvasStore.getState().addNode('imageToImage', { x: 400, y: 0 });
    const edge = useCanvasStore.getState().createEdge({
      source,
      target,
      sourceHandle: null,
      targetHandle: null,
    });

    expect(edge).toBeNull();
    expect(useCanvasStore.getState().edges).toHaveLength(0);
  });

  it('materializes a deterministic result node only once', () => {
    const producerId = useCanvasStore.getState().addNode('textToImage', { x: 0, y: 0 });
    const request = {
      resultNodeId: 'result:record-1:remote-1',
      producerNodeId: producerId,
      assetId: 'asset-1',
      kind: 'image' as const,
      label: '生成图片',
      size: { width: 320, height: 240 },
    };

    const first = useCanvasStore.getState().materializeResultNode(request);
    const second = useCanvasStore.getState().materializeResultNode(request);

    expect(first).toBe(request.resultNodeId);
    expect(second).toBe(request.resultNodeId);
    expect(useCanvasStore.getState().nodes.filter((node) => node.id === request.resultNodeId)).toHaveLength(1);
    expect(useCanvasStore.getState().edges).toHaveLength(1);
  });

  it('lays out multiple result nodes from one producer without overlap', () => {
    const producerId = useCanvasStore.getState().addNode('batchImageRefine', { x: 0, y: 0 });
    const size = { width: 320, height: 240 };

    const firstId = useCanvasStore.getState().materializeResultNode({
      resultNodeId: 'result:record-1:remote-1',
      producerNodeId: producerId,
      assetId: 'asset-1',
      kind: 'image',
      label: '批量结果 1',
      size,
    });
    const secondId = useCanvasStore.getState().materializeResultNode({
      resultNodeId: 'result:record-2:remote-2',
      producerNodeId: producerId,
      assetId: 'asset-2',
      kind: 'image',
      label: '批量结果 2',
      size,
    });
    const first = useCanvasStore.getState().nodes.find((node) => node.id === firstId)!;
    const second = useCanvasStore.getState().nodes.find((node) => node.id === secondId)!;

    expect(first.position).not.toEqual(second.position);
    expect(Math.abs(first.position.x - second.position.x)).toBeGreaterThanOrEqual(size.width);
    expect(Math.abs(first.position.y - second.position.y)).toBeLessThan(size.height);
  });

  it('applies a storyboard plan without duplicating shots on rerun', () => {
    const plannerId = useCanvasStore.getState().addNode('storyboardPlanner', { x: 0, y: 0 });
    const plan = [{
      shotId: 'shot-1',
      segmentId: 'segment-1',
      shotOrdinal: 1,
      sceneDescription: '门打开',
      shotType: '中景',
      cameraMovement: '推进',
      durationSeconds: 3,
      imagePrompt: '门打开',
      videoPrompt: '镜头推进',
      negativePrompt: '',
      referenceHints: [],
    }];

    const first = useCanvasStore.getState().applyStoryboardPlan(plannerId, plan);
    const second = useCanvasStore.getState().applyStoryboardPlan(plannerId, plan);

    expect(first.created).toHaveLength(1);
    expect(second.created).toHaveLength(0);
    expect(second.updated).toHaveLength(1);
    expect(useCanvasStore.getState().nodes.filter((node) => node.data.kind === 'shot')).toHaveLength(1);
    expect(useCanvasStore.getState().edges.filter((edge) => edge.target.includes('shot-1'))).toHaveLength(1);
  });

  it('connects carried reference assets to their generated shots', () => {
    const imageId = useCanvasStore.getState().addNode('image', { x: 0, y: 0 }, { assetId: 'asset-character' });
    const plannerId = useCanvasStore.getState().addNode('storyboardPlanner', { x: 400, y: 0 });
    useCanvasStore.getState().createEdge({
      source: imageId,
      sourceHandle: 'image',
      target: plannerId,
      targetHandle: 'reference',
      data: { portOrder: 0 },
    } as never);
    const plan = [{
      shotId: 'shot-reference',
      segmentId: 'segment-1',
      shotOrdinal: 1,
      sceneDescription: '角色走进房间',
      shotType: '中景',
      cameraMovement: '推进',
      durationSeconds: 3,
      imagePrompt: '角色走进房间',
      videoPrompt: '镜头跟随角色走进房间',
      negativePrompt: '',
      referenceHints: ['@image#1'],
      referenceAssetIds: ['asset-character'],
    }];

    const result = useCanvasStore.getState().applyStoryboardPlan(plannerId, plan);
    const shotId = result.created[0];
    const referenceEdge = useCanvasStore.getState().edges.find((edge) =>
      edge.source === imageId && edge.target === shotId && edge.targetHandle === 'reference');

    expect(referenceEdge).toMatchObject({ source: imageId, target: shotId, targetHandle: 'reference' });
  });
});
