import { beforeEach, describe, expect, it } from 'vitest';
import { canvasCommandService } from './canvasCommandService';
import { useCanvasStore } from '../store/canvasStore';
import { createProject, listCanvases } from '../services/projectStorage';

const context = { projectId: 'project-1', source: 'agent' as const };

describe('canvasCommandService MCP execution surface', () => {
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

  it('returns real node ids and updates node data', () => {
    const source = canvasCommandService.createNode(context, {
      kind: 'script',
      data: { scriptText: '第一幕' },
    });
    expect(source.ok).toBe(true);
    expect(source.result?.nodeId).toMatch(/^script-/);

    const updated = canvasCommandService.updateNode(context, {
      nodeId: source.result!.nodeId,
      patch: { prompt: '优化后的剧本' },
    });
    expect(updated.ok).toBe(true);
    expect(useCanvasStore.getState().nodes[0].data.prompt).toBe('优化后的剧本');
  });

  it('requires the model config tool for model parameter fields', () => {
    const generator = canvasCommandService.createNode(context, { kind: 'textToImage' });

    const blocked = canvasCommandService.updateNode(context, {
      nodeId: generator.result!.nodeId,
      patch: { model: 'raw-bypass', gatewayParams: { n: 3 } },
    });

    expect(blocked).toMatchObject({
      ok: false,
      error: { code: 'MODEL_CONFIG_TOOL_REQUIRED' },
    });
    expect(useCanvasStore.getState().nodes[0].data.model).toBeUndefined();
  });

  it('requires the model config tool when creating nodes with model parameters', () => {
    const created = canvasCommandService.createNode(context, {
      kind: 'textToImage',
      data: { model: 'raw-bypass', gatewayParams: { n: 3 } },
    });

    expect(created).toMatchObject({
      ok: false,
      error: { code: 'MODEL_CONFIG_TOOL_REQUIRED' },
    });
    expect(useCanvasStore.getState().nodes).toHaveLength(0);
  });

  it('resolves compatible ports when creating an edge', () => {
    const source = canvasCommandService.createNode(context, { kind: 'image' });
    const target = canvasCommandService.createNode(context, { kind: 'imageToImage' });
    const edge = canvasCommandService.createEdge(context, {
      source: source.result!.nodeId,
      target: target.result!.nodeId,
    });
    expect(edge.ok).toBe(true);
    const created = useCanvasStore.getState().edges[0];
    expect(created.sourceHandle).toBe('image');
    expect(created.targetHandle).toBe('reference');
  });

  it('returns node definitions with ports and actions', () => {
    const result = canvasCommandService.listNodeTypes(context);
    expect(result.ok).toBe(true);
    expect(result.result).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'imageGeneration' }),
      expect.objectContaining({ kind: 'videoGeneration' }),
      expect.objectContaining({ kind: 'storyboardPlanner' }),
    ]));
    // 合并后节点库里视频/图片各只剩一个生成节点
    const kinds = (result.result as Array<{ kind: string }>).map((definition) => definition.kind);
    expect(kinds.filter((kind) => kind === 'imageGeneration')).toHaveLength(1);
    expect(kinds.filter((kind) => kind === 'videoGeneration')).toHaveLength(1);
    expect(kinds).not.toContain('textToImage');
    expect(kinds).not.toContain('referenceToVideo');
  });

  it('exposes media relay roles and placeholder contracts', () => {
    const result = canvasCommandService.listNodeTypes(context);
    const definitions = result.result as Array<Record<string, unknown>>;

    expect(definitions.find((definition) => definition.kind === 'imageGeneration')).toMatchObject({
      flowRole: 'producer',
    });
    expect(definitions.find((definition) => definition.kind === 'image')).toMatchObject({
      flowRole: 'mediaPlaceholder',
      mediaPlaceholder: {
        media: 'image',
        inputPort: 'origin',
        outputPort: 'image',
      },
    });
    expect(definitions.find((definition) => definition.kind === 'video')).toMatchObject({
      flowRole: 'mediaPlaceholder',
      mediaPlaceholder: {
        media: 'video',
        inputPort: 'origin',
        outputPort: 'video',
      },
    });
    expect(definitions.find((definition) => definition.kind === 'shot')).toMatchObject({
      flowRole: 'storyboardPlaceholder',
    });
  });

  it('creates a named canvas inside the current project', async () => {
    const project = await createProject('画布工具项目');
    useCanvasStore.setState({ projectId: project.id, canvasId: 'canvas-1' });

    const created = await canvasCommandService.createCanvas({ ...context, projectId: project.id }, { title: '新画布' });

    expect(created.ok).toBe(true);
    expect(await listCanvases(project.id)).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: created.result?.canvasId, title: '新画布' }),
    ]));
  });

  it('renames the current canvas', async () => {
    const project = await createProject('重命名项目');
    const canvas = await import('../services/projectStorage').then(({ createCanvas }) =>
      createCanvas(project.id, '旧名称'));
    useCanvasStore.setState({ projectId: project.id, canvasId: canvas.id });

    const renamed = await canvasCommandService.renameCanvas({ ...context, projectId: project.id }, { title: '新名称' });

    expect(renamed.ok).toBe(true);
    expect(await listCanvases(project.id)).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: canvas.id, title: '新名称' }),
    ]));
  });

  it('creates ordered image result slots for a producer', async () => {
    const producer = canvasCommandService.createNode(context, { kind: 'textToImage' });

    const slots = await canvasCommandService.createResultSlots(context, {
      producerNodeId: producer.result!.nodeId,
      kind: 'image',
      count: 3,
    });

    expect(slots.ok).toBe(true);
    expect(slots.result?.nodeIds).toHaveLength(3);
    const edges = useCanvasStore.getState().edges.filter((edge) => edge.source === producer.result!.nodeId);
    expect(edges.map((edge) => edge.data?.resultSlotOrder)).toEqual([0, 1, 2]);
    expect(edges.every((edge) => edge.data?.resultSlot === true)).toBe(true);
  });

  it('marks existing compatible placeholders as ordered result slots', async () => {
    const producer = canvasCommandService.createNode(context, { kind: 'textToImage' });
    const first = canvasCommandService.createNode(context, { kind: 'image' });
    const second = canvasCommandService.createNode(context, { kind: 'image' });

    const slots = await canvasCommandService.markResultSlots(context, {
      producerNodeId: producer.result!.nodeId,
      nodeIds: [second.result!.nodeId, first.result!.nodeId],
    });

    expect(slots.result?.nodeIds).toEqual([second.result!.nodeId, first.result!.nodeId]);
    const edges = useCanvasStore.getState().edges;
    expect(edges.find((edge) => edge.target === second.result!.nodeId)?.data?.resultSlotOrder).toBe(0);
    expect(edges.find((edge) => edge.target === first.result!.nodeId)?.data?.resultSlotOrder).toBe(1);
  });

  it('lays out an entire connected component without moving unrelated nodes', async () => {
    const script = canvasCommandService.createNode(context, { kind: 'script' });
    const planner = canvasCommandService.createNode(context, { kind: 'storyboardPlanner', position: { x: 1_000, y: 600 } });
    const firstShot = canvasCommandService.createNode(context, { kind: 'shot', position: { x: 2_000, y: 100 } });
    const secondShot = canvasCommandService.createNode(context, { kind: 'shot', position: { x: 2_400, y: 100 } });
    const unrelated = canvasCommandService.createNode(context, { kind: 'note', position: { x: 4_800, y: 1_400 } });
    canvasCommandService.createEdge(context, {
      source: script.result!.nodeId,
      sourceHandle: 'script',
      target: planner.result!.nodeId,
      targetHandle: 'script',
    });
    canvasCommandService.createEdge(context, {
      source: planner.result!.nodeId,
      sourceHandle: 'shots',
      target: firstShot.result!.nodeId,
      targetHandle: 'origin',
    });
    canvasCommandService.createEdge(context, {
      source: planner.result!.nodeId,
      sourceHandle: 'shots',
      target: secondShot.result!.nodeId,
      targetHandle: 'origin',
    });

    const result = await canvasCommandService.layoutConnectedComponent(context, {
      nodeIds: [firstShot.result!.nodeId],
      gapX: 180,
      gapY: 72,
    });
    expect(result.ok).toBe(true);
    const nodes = useCanvasStore.getState().nodes;
    const byId = new Map(nodes.map((node) => [node.id, node]));
    expect(byId.get(script.result!.nodeId)!.position.x)
      .toBeLessThan(byId.get(planner.result!.nodeId)!.position.x);
    expect(byId.get(firstShot.result!.nodeId)!.position.x)
      .toBe(byId.get(secondShot.result!.nodeId)!.position.x);
    expect(byId.get(planner.result!.nodeId)!.position.y)
      .toBe(byId.get(script.result!.nodeId)!.position.y);
    expect(byId.get(firstShot.result!.nodeId)!.position.y)
      .toBe(byId.get(script.result!.nodeId)!.position.y);
    expect(byId.get(unrelated.result!.nodeId)!.position).toEqual({ x: 4_800, y: 1_400 });
  });

  it('uses the whole canvas for DAG layout when no nodes are selected', async () => {
    const first = canvasCommandService.createNode(context, { kind: 'text', position: { x: 900, y: 500 } });
    const second = canvasCommandService.createNode(context, { kind: 'note', position: { x: 1_400, y: 500 } });
    useCanvasStore.setState((state) => ({
      nodes: state.nodes.map((node) => ({ ...node, selected: false })),
      selectedNodeId: null,
    }));

    const result = await canvasCommandService.layoutDag(context, { direction: 'right' });
    expect(result.ok).toBe(true);
    const positions = useCanvasStore.getState().nodes
      .filter((node) => node.id === first.result!.nodeId || node.id === second.result!.nodeId)
      .map((node) => node.position);
    expect(Math.min(...positions.map((position) => position.y))).toBe(500);
    expect(new Set(positions.map((position) => position.x)).size).toBe(1);
    expect(new Set(positions.map((position) => position.y)).size).toBe(2);
  });

  it('keeps a layout result when only bookkeeping revision changes during calculation', async () => {
    canvasCommandService.createNode(context, { kind: 'text', position: { x: 600, y: 400 } });
    canvasCommandService.createNode(context, { kind: 'note', position: { x: 1_000, y: 400 } });

    const pending = canvasCommandService.layoutDag(context, { direction: 'right' });
    useCanvasStore.setState((state) => ({ revision: state.revision + 1 }));

    await expect(pending).resolves.toMatchObject({ ok: true });
  });
});
