import { beforeEach, describe, expect, it } from 'vitest';
import { useCanvasStore } from './canvasStore';

describe('canvas transactions', () => {
  beforeEach(() => useCanvasStore.setState({ nodes: [], edges: [], past: [], future: [], selectedNodeId: null, dragStartSnapshot: null, revision: 0 }));

  it('creates derived assets, nodes, and edges as one undoable action', () => {
    const sourceId = useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    useCanvasStore.getState().createDerivedNodes(sourceId, [{
      data: { assetId: 'asset-1', label: 'derived' },
      size: { width: 200, height: 100 },
      edgeData: { operationDetail: '首帧' },
    }]);
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
    expect(useCanvasStore.getState().edges).toHaveLength(1);
    // 派生边用注册表真实端口 id（left/right 匹配不到 Handle）
    expect(useCanvasStore.getState().edges[0]).toMatchObject({
      source: sourceId,
      sourceHandle: 'image',
      targetHandle: 'origin',
      data: { operationDetail: '首帧' },
    });
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().nodes).toHaveLength(1);
    expect(useCanvasStore.getState().edges).toHaveLength(0);
    useCanvasStore.getState().redo();
    expect(useCanvasStore.getState().nodes[1].data.assetId).toBe('asset-1');
  });

  it('imports a batch as one undoable action', () => {
    useCanvasStore.getState().addImportedImages([
      { assetId: 'a', label: 'a', position: { x: 0, y: 0 }, size: { width: 200, height: 200 } },
      { assetId: 'b', label: 'b', position: { x: 250, y: 0 }, size: { width: 200, height: 200 } },
    ]);
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().nodes).toHaveLength(0);
  });

  it('preserves unchanged node references when selecting inside a large graph', () => {
    const project = {
      schemaVersion: 1 as const,
      id: 'large', title: 'large', edges: [], viewport: { x: 0, y: 0, zoom: 1 }, createdAt: 1, updatedAt: 1,
      nodes: Array.from({ length: 500 }, (_, index) => ({
        id: `node-${index}`, type: 'media' as const, position: { x: index * 10, y: 0 }, width: 200, height: 200,
        data: { kind: 'image' as const, label: `${index}`, prompt: '', status: 'idle' as const, accent: '#fff' },
      })),
    };
    useCanvasStore.getState().loadProjectGraph(project);
    const before = useCanvasStore.getState().nodes;
    useCanvasStore.getState().selectNode('node-250');
    const selected = useCanvasStore.getState().nodes;
    expect(selected.filter((node, index) => node !== before[index])).toHaveLength(1);

    useCanvasStore.getState().selectNode('node-251');
    const next = useCanvasStore.getState().nodes;
    expect(next.filter((node, index) => node !== selected[index])).toHaveLength(2);
  });

  it('copies selected nodes and internal edges without cloning assets', () => {
    const state = useCanvasStore.getState();
    const first = state.addNode('image', { x: 10, y: 20 }, { assetId: 'shared-asset' });
    const second = useCanvasStore.getState().addNode('text', { x: 310, y: 20 });
    useCanvasStore.setState((current) => ({
      nodes: current.nodes.map((node) => ({ ...node, selected: node.id === first || node.id === second })),
      selectedNodeId: second,
      edges: [{ id: 'original-edge', source: first, target: second, type: 'canvas' }],
    }));

    expect(useCanvasStore.getState().copySelected()).toBe(2);
    const created = useCanvasStore.getState().pasteClipboard({ x: 1000, y: 800 });
    expect(created).toHaveLength(2);
    const pasted = useCanvasStore.getState();
    expect(pasted.nodes).toHaveLength(4);
    expect(pasted.edges).toHaveLength(2);
    expect(pasted.nodes.find((node) => node.id === created[0])?.data.assetId).toBe('shared-asset');
    expect(pasted.edges[1].source).toBe(created[0]);
    expect(pasted.edges[1].target).toBe(created[1]);
    expect(Math.min(...pasted.nodes.filter((node) => created.includes(node.id)).map((node) => node.position.x))).toBe(1000);
  });

  it('pastes and duplicates a multi-node selection as one undoable transaction', () => {
    useCanvasStore.getState().addNode('text', { x: 0, y: 0 });
    useCanvasStore.getState().copySelected();
    useCanvasStore.getState().pasteClipboard();
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().nodes).toHaveLength(1);
    useCanvasStore.getState().redo();
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
    useCanvasStore.getState().duplicateSelected();
    expect(useCanvasStore.getState().nodes).toHaveLength(3);
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
  });

  it('deletes a selected edge without removing its nodes', () => {
    const first = useCanvasStore.getState().addNode('text', { x: 0, y: 0 });
    const second = useCanvasStore.getState().addNode('text', { x: 400, y: 0 });
    useCanvasStore.setState({ edges: [{ id: 'edge-only', source: first, target: second, type: 'canvas' }] });
    useCanvasStore.getState().deleteEdge('edge-only');
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
    expect(useCanvasStore.getState().edges).toHaveLength(0);
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().edges).toHaveLength(1);
  });

  it('selects all nodes and batch deletes them as one undoable transaction', () => {
    useCanvasStore.getState().addNode('text', { x: 0, y: 0 });
    useCanvasStore.getState().addNode('image', { x: 300, y: 0 });
    useCanvasStore.getState().addNode('note', { x: 600, y: 0 });

    expect(useCanvasStore.getState().selectAll()).toBe(3);
    expect(useCanvasStore.getState().nodes.filter((node) => node.selected)).toHaveLength(3);

    useCanvasStore.getState().deleteSelected();
    expect(useCanvasStore.getState().nodes).toHaveLength(0);

    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().nodes).toHaveLength(3);
  });

  // 验证 acceptTypes 边校验：源节点声明了可接类型，目标不匹配时应拒绝连线
  it('rejects connections that violate acceptTypes', () => {
    useCanvasStore.getState().addNode('script', { x: 0, y: 0 });
    useCanvasStore.getState().addNode('note', { x: 300, y: 0 });
    const scriptId = useCanvasStore.getState().nodes[0].id;
    const noteId = useCanvasStore.getState().nodes[1].id;
    const edgeId = useCanvasStore.getState().createEdge({ source: scriptId, target: noteId, sourceHandle: 'right', targetHandle: 'left' });
    expect(edgeId).toBeNull();
    // 反向：通过 createConnectedNode 应能正常创建（script 连 storyboard，目标匹配 acceptTypes）
    const storyNodeId = useCanvasStore.getState().createConnectedNode(scriptId, 'storyboard', { x: 600, y: 0 });
    expect(storyNodeId).not.toBeNull();
    expect(useCanvasStore.getState().edges).toHaveLength(1);
  });

  // 空 acceptTypes 的源（批注/注释）不允许连出任何目标
  it('rejects outgoing edges from a source with empty acceptTypes (note)', () => {
    useCanvasStore.getState().addNode('note', { x: 0, y: 0 });
    useCanvasStore.getState().addNode('imageGen', { x: 300, y: 0 });
    const noteId = useCanvasStore.getState().nodes[0].id;
    const imageGenId = useCanvasStore.getState().nodes[1].id;
    expect(useCanvasStore.getState().createEdge({ source: noteId, target: imageGenId, sourceHandle: 'right', targetHandle: 'left' })).toBeNull();
    expect(useCanvasStore.getState().createConnectedNode(noteId, 'imageGen', { x: 600, y: 0 })).toBeNull();
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
    expect(useCanvasStore.getState().edges).toHaveLength(0);
  });

  // onConnect（connect）也走连通性校验：图片只能连图生图/图生视频/首尾帧生视频
  it('enforces acceptTypes on the connect(onConnect) path too', () => {
    useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    useCanvasStore.getState().addNode('script', { x: 300, y: 0 });
    const imageId = useCanvasStore.getState().nodes[0].id;
    const scriptId = useCanvasStore.getState().nodes[1].id;
    // image 不能连 script
    useCanvasStore.getState().connect({ source: imageId, target: scriptId, sourceHandle: 'right', targetHandle: 'left' });
    expect(useCanvasStore.getState().edges).toHaveLength(0);
    // 图片 → 图生视频 应被放行
    useCanvasStore.getState().addNode('imageToVideo', { x: 600, y: 0 });
    const imageToVideoId = useCanvasStore.getState().nodes[2].id;
    useCanvasStore.getState().connect({ source: imageId, target: imageToVideoId, sourceHandle: 'right', targetHandle: 'left' });
    expect(useCanvasStore.getState().edges).toHaveLength(1);
  });

  it('adds images to an ordered multi-image input with sequential portOrder', () => {
    useCanvasStore.getState().addNode('image', { x: 0, y: 0 });
    const sourceId = useCanvasStore.getState().nodes[0].id;
    const batchId = useCanvasStore.getState().createConnectedNode(
      sourceId,
      'batchImageRefine',
      { x: 600, y: 0 },
      'image',
    );

    expect(batchId).not.toBeNull();
    expect(useCanvasStore.getState().edges).toHaveLength(1);
    expect(useCanvasStore.getState().edges[0]).toMatchObject({
      target: batchId,
      targetHandle: 'images',
      data: { portOrder: 0 },
    });

    const secondImageId = useCanvasStore.getState().createNodeBefore(
      batchId!,
      'image',
      { x: 300, y: 0 },
      'images',
    );

    expect(secondImageId).not.toBeNull();
    expect(useCanvasStore.getState().edges).toHaveLength(2);
    expect(useCanvasStore.getState().edges[1]).toMatchObject({
      source: secondImageId,
      target: batchId,
      targetHandle: 'images',
      data: { portOrder: 1 },
    });
  });
});
