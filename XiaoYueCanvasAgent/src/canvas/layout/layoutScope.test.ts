import { describe, expect, it } from 'vitest';
import type { CanvasEdge, CanvasNode } from '../../types';
import { collectConnectedComponentNodeIds, collectConnectedComponents } from './layoutScope';

const node = (id: string, selected = false): CanvasNode => ({
  id,
  type: 'media',
  position: { x: 0, y: 0 },
  selected,
  data: { kind: 'image', label: id, prompt: '', status: 'idle', accent: '#fff' },
});

const edge = (source: string, target: string): CanvasEdge => ({
  id: `${source}-${target}`,
  source,
  sourceHandle: 'image',
  target,
  targetHandle: 'origin',
  type: 'canvas',
});

describe('layout scope', () => {
  it('collects only the connected component containing the seed nodes', () => {
    const ids = collectConnectedComponentNodeIds(
      [node('script'), node('planner'), node('shot-a'), node('shot-b'), node('unrelated')],
      [edge('script', 'planner'), edge('planner', 'shot-a'), edge('planner', 'shot-b')],
      ['shot-a'],
    );
    expect(ids).toEqual(['script', 'planner', 'shot-a', 'shot-b']);
  });

  it('keeps disconnected workflows in separate components', () => {
    const components = collectConnectedComponents(
      [node('a'), node('b'), node('c'), node('d')],
      [edge('a', 'b'), edge('c', 'd')],
    );
    expect(components.map((component) => component.map((item) => item.id))).toEqual([
      ['a', 'b'],
      ['c', 'd'],
    ]);
  });
});
