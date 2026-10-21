import { describe, expect, it } from 'vitest';
import { mergeCanvasGraph } from './graphMerge';
import type { CanvasEdge, CanvasNode } from '../types';

const node = (id: string, label: string): CanvasNode => ({
  id,
  type: 'imageGeneration',
  position: { x: 0, y: 0 },
  data: { kind: 'imageGeneration', label, prompt: '', status: 'idle', accent: '#fff' },
});

const edge = (id: string, source: string, target: string): CanvasEdge => ({
  id,
  source,
  sourceHandle: 'image',
  target,
  targetHandle: 'origin',
  type: 'canvas',
});

describe('mergeCanvasGraph（409 冲突时把服务端图并回本地）', () => {
  it('服务端新增的节点并回本地，同 id 的节点以本地为准', () => {
    const merged = mergeCanvasGraph(
      { nodes: [node('a', '本地 A')], edges: [] },
      { nodes: [node('a', '服务端 A'), node('b', '智能体新建')], edges: [] },
    );

    expect(merged.nodes.map((item) => item.id)).toEqual(['a', 'b']);
    expect(merged.nodes[0].data.label).toBe('本地 A');
    expect(merged.addedNodeIds).toEqual(['b']);
  });

  it('服务端新增的连线并回本地，同语义连线（同源同端口同目标）只保留本地那条', () => {
    const merged = mergeCanvasGraph(
      {
        nodes: [node('a', 'A'), node('b', 'B'), node('c', 'C')],
        edges: [edge('local-a-b', 'a', 'b')],
      },
      {
        nodes: [],
        // 与本地同语义的一条 + 一条本地没有的
        edges: [edge('server-a-b', 'a', 'b'), edge('server-b-c', 'b', 'c')],
      },
    );

    expect(merged.edges.map((item) => item.id)).toEqual(['local-a-b', 'server-b-c']);
    expect(merged.addedEdgeIds).toEqual(['server-b-c']);
  });

  it('生成记录与执行批次按 id 取并集、同 id 以本地为准', () => {
    const merged = mergeCanvasGraph(
      {
        nodes: [],
        edges: [],
        generationRecords: [{ id: 'r1', status: 'running' } as never],
        executions: [{ id: 'e1', status: 'running' } as never],
      },
      {
        nodes: [],
        edges: [],
        generationRecords: [{ id: 'r1', status: 'completed' } as never, { id: 'r2', status: 'completed' } as never],
        executions: [{ id: 'e2', status: 'interrupted' } as never],
      },
    );

    expect(merged.generationRecords.map((record) => record.id)).toEqual(['r1', 'r2']);
    expect((merged.generationRecords[0] as unknown as { status: string }).status).toBe('running');
    expect(merged.executions.map((execution) => execution.id)).toEqual(['e1', 'e2']);
  });

  it('服务端没有新增时保持本地图不变（不制造重复）', () => {
    const local = { nodes: [node('a', 'A')], edges: [edge('e1', 'a', 'a')] };
    const merged = mergeCanvasGraph(local, { nodes: [node('a', 'A')], edges: [edge('e1', 'a', 'a')] });

    expect(merged.nodes).toHaveLength(1);
    expect(merged.edges).toHaveLength(1);
    expect(merged.addedNodeIds).toEqual([]);
    expect(merged.addedEdgeIds).toEqual([]);
  });
});
