import { beforeEach, describe, expect, it } from 'vitest';
import { useCanvasStore } from '../../store/canvasStore';
import type { CanvasEdge, CanvasNode } from '../../types';
import { requestAutoLayout } from './autoLayout';

const node = (
  id: string,
  kind: CanvasNode['data']['kind'],
  position: { x: number; y: number },
): CanvasNode => ({
  id,
  type: 'media',
  position,
  data: { kind, label: id, prompt: '', status: 'idle', accent: '#fff' },
});

const edge = (source: string, target: string): CanvasEdge => ({
  id: `${source}-${target}`,
  source,
  sourceHandle: source === 'planner' ? 'shots' : 'script',
  target,
  targetHandle: target === 'planner' ? 'script' : 'origin',
  type: 'canvas',
});

describe('automatic canvas layout', () => {
  beforeEach(() => {
    useCanvasStore.setState({
      projectId: 'project-auto-layout',
      canvasId: 'canvas-auto-layout',
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

  it('lays out only the affected component after generation', async () => {
    useCanvasStore.setState({
      nodes: [
        node('script', 'script', { x: 600, y: 500 }),
        node('planner', 'storyboardPlanner', { x: 1_200, y: 500 }),
        node('shot-a', 'shot', { x: 2_000, y: 100 }),
        node('shot-b', 'shot', { x: 2_400, y: 100 }),
        node('unrelated', 'note', { x: 4_000, y: 1_200 }),
      ],
      edges: [
        edge('script', 'planner'),
        edge('planner', 'shot-a'),
        edge('planner', 'shot-b'),
      ],
    });

    const result = await requestAutoLayout(['shot-a']);
    expect(result?.ok).toBe(true);
    const nodes = useCanvasStore.getState().nodes;
    const byId = new Map(nodes.map((item) => [item.id, item]));
    expect(byId.get('script')!.position.x).toBeLessThan(byId.get('planner')!.position.x);
    expect(byId.get('shot-a')!.position.x).toBe(byId.get('shot-b')!.position.x);
    expect(byId.get('unrelated')!.position).toEqual({ x: 4_000, y: 1_200 });
  });
});
