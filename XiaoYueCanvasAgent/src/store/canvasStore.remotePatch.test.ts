// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { useCanvasStore } from './canvasStore';
import type { CanvasEdge, CanvasNode } from '../types';
import type { CanvasGraphPatch } from '../canvas/graphContracts';

/**
 * 远端增量补丁（postMessage `patch`）的本地应用语义。
 *
 * 关键约束：身份键只有 id（补丁里的 assetUrl 会被后端重新签名，不能拿来判等）；
 * 幂等（同一份「累积补丁」可能被重复投递）；不污染撤销栈。
 */

const node = (id: string, overrides: Partial<CanvasNode['data']> = {}, x = 0): CanvasNode => ({
  id,
  type: 'image',
  position: { x, y: 0 },
  width: 250,
  height: 250,
  data: { kind: 'image', label: id, prompt: '', status: 'idle', accent: '#fff', ...overrides },
}) as CanvasNode;

const edge = (id: string, source: string, target: string): CanvasEdge => ({
  id,
  source,
  target,
  sourceHandle: 'image',
  targetHandle: 'origin',
  type: 'canvas',
}) as CanvasEdge;

const reset = (nodes: CanvasNode[], edges: CanvasEdge[] = []) => {
  useCanvasStore.setState({
    projectId: 'project-1',
    canvasId: 'canvas-1',
    nodes,
    edges,
    past: [],
    future: [],
    selectedNodeId: null,
    revision: 0,
  });
};

describe('canvasStore.applyRemotePatch', () => {
  beforeEach(() => reset([]));

  it('新增节点与连线，并保持幂等（同一份累积补丁重复应用不产生重复项）', () => {
    const patch: CanvasGraphPatch = {
      nodes: [node('n1'), node('n2', {}, 400)],
      edges: [edge('e1', 'n1', 'n2')],
    };

    const first = useCanvasStore.getState().applyRemotePatch(patch);
    expect(first.addedNodeIds).toEqual(['n1', 'n2']);
    expect(first.addedEdgeIds).toEqual(['e1']);

    const before = useCanvasStore.getState();
    const snapshot = JSON.stringify({ nodes: before.nodes, edges: before.edges });

    // 累积补丁会被重复投递：第二次必须是 upsert（updated 而非 added），图不能变样
    const second = useCanvasStore.getState().applyRemotePatch(patch);
    expect(second.addedNodeIds).toEqual([]);
    expect(second.updatedNodeIds).toEqual(['n1', 'n2']);
    expect(second.addedEdgeIds).toEqual([]);
    expect(second.updatedEdgeIds).toEqual(['e1']);

    const after = useCanvasStore.getState();
    expect(after.nodes.map((item) => item.id)).toEqual(['n1', 'n2']);
    expect(after.edges.map((item) => item.id)).toEqual(['e1']);
    expect(JSON.stringify({ nodes: after.nodes, edges: after.edges })).toBe(snapshot);
  });

  it('身份键是 id：assetUrl 不同也按同一节点覆盖，URL 相同也不去重', () => {
    reset([
      node('n1', { assetId: 'a1', assetUrl: '/api/files/a.png' }),
      node('n2', { assetId: 'a1', assetUrl: '/api/files/a.png' }, 400),
    ]);

    // 补丁里的 assetUrl 被后端重新签名 → 与本地字符串不等，但必须识别为同一节点
    useCanvasStore.getState().applyRemotePatch({
      nodes: [node('n1', { assetId: 'a1', assetUrl: '/api/files/a.png?token=signed' })],
    });

    const state = useCanvasStore.getState();
    expect(state.nodes.map((item) => item.id)).toEqual(['n1', 'n2']);
    expect(state.nodes[0].data.assetUrl).toBe('/api/files/a.png?token=signed');
    expect(state.nodes[0].data.assetId).toBe('a1');
    // 同 URL 的两个不同 id 节点不会互相吞掉
    expect(state.nodes[1].data.assetUrl).toBe('/api/files/a.png');
  });

  it('layout 类补丁（只改 position）原地更新已有节点，不新增节点', () => {
    reset([node('n1'), node('n2', {}, 400)]);
    const result = useCanvasStore.getState().applyRemotePatch({
      nodes: [{ id: 'n1', type: 'image', position: { x: 900, y: 120 }, width: 250, height: 250, data: { kind: 'image', label: 'n1' } }],
    });
    expect(result.addedNodeIds).toEqual([]);
    expect(result.updatedNodeIds).toEqual(['n1']);
    expect(useCanvasStore.getState().nodes).toHaveLength(2);
    expect(useCanvasStore.getState().nodes.find((item) => item.id === 'n1')?.position).toEqual({ x: 900, y: 120 });
  });

  it('只有 edges 的补丁（create_edge）可以应用', () => {
    reset([node('n1'), node('n2', {}, 400)]);
    const result = useCanvasStore.getState().applyRemotePatch({ edges: [edge('e1', 'n1', 'n2')] });
    expect(result.addedEdgeIds).toEqual(['e1']);
    expect(useCanvasStore.getState().edges).toHaveLength(1);
  });

  it('删除被 removedNodeIds 命中的节点、清选中态并顺带剪掉悬挂边', () => {
    reset([node('n1'), node('n2', {}, 400)], [edge('e1', 'n1', 'n2')]);
    useCanvasStore.setState({ selectedNodeId: 'n1', nodes: useCanvasStore.getState().nodes.map((item) => ({ ...item, selected: item.id === 'n1' })) });

    const result = useCanvasStore.getState().applyRemotePatch({ removedNodeIds: ['n1'] });

    const state = useCanvasStore.getState();
    expect(result.removedNodeIds).toEqual(['n1']);
    expect(state.nodes.map((item) => item.id)).toEqual(['n2']);
    // e1 的端点被删 → 必须一起剪掉（否则渲染悬挂边）
    expect(state.edges).toHaveLength(0);
    expect(result.removedEdgeIds).toContain('e1');
    expect(state.selectedNodeId).toBeNull();
  });

  it('removedEdgeIds 精确删除指定连线', () => {
    reset([node('n1'), node('n2', {}, 400)], [edge('e1', 'n1', 'n2')]);
    useCanvasStore.getState().applyRemotePatch({ removedEdgeIds: ['e1'] });
    expect(useCanvasStore.getState().edges).toHaveLength(0);
    // 幂等：再删一次不报错
    expect(() => useCanvasStore.getState().applyRemotePatch({ removedEdgeIds: ['e1'] })).not.toThrow();
  });

  it('不污染撤销栈，也不推进本地 revision（避免触发自动保存回写），但保留本地选中态', () => {
    reset([node('n1')]);
    useCanvasStore.setState({ past: [], future: [], revision: 7, selectedNodeId: 'n1' });

    useCanvasStore.getState().applyRemotePatch({ nodes: [node('n2', {}, 400)] });

    const state = useCanvasStore.getState();
    expect(state.past).toHaveLength(0);
    expect(state.future).toHaveLength(0);
    expect(state.revision).toBe(7);
    expect(state.nodes.find((item) => item.id === 'n1')?.selected ?? false).toBe(false);
    expect(state.selectedNodeId).toBe('n1');
  });

  it('结构不合法时抛错且不改动本地图（调用方据此退回全量重载）', () => {
    reset([node('n1')]);
    const before = JSON.stringify(useCanvasStore.getState().nodes);

    // 缺 position
    expect(() => useCanvasStore.getState().applyRemotePatch({
      nodes: [{ id: 'n2', data: { kind: 'image' } }],
    })).toThrow(/position/);
    // 缺 data
    expect(() => useCanvasStore.getState().applyRemotePatch({
      nodes: [{ id: 'n2', position: { x: 1, y: 1 } }],
    })).toThrow(/data/);
    // 边端点不存在（补丁被截断/漏投）
    expect(() => useCanvasStore.getState().applyRemotePatch({
      edges: [{ id: 'e1', source: 'n1', target: 'missing' }],
    })).toThrow(/端点/);

    const state = useCanvasStore.getState();
    expect(JSON.stringify(state.nodes)).toBe(before);
    expect(state.edges).toHaveLength(0);
  });
});
