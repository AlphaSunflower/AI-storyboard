import { describe, expect, it } from 'vitest';
import type { CanvasEdge, CanvasNode } from '../../types';
import { computeReadableDagLayout } from './readableDagLayout';

const node = (
  id: string,
  kind: CanvasNode['data']['kind'],
  position: { x: number; y: number },
  size: { width: number; height: number },
): CanvasNode => ({
  id,
  type: 'media',
  position,
  width: size.width,
  height: size.height,
  data: {
    kind,
    label: id,
    prompt: '',
    status: 'idle',
    accent: '#fff',
  },
});

const edge = (
  source: string,
  sourceHandle: string,
  target: string,
  targetHandle: string,
): CanvasEdge => ({
  id: `${source}-${target}`,
  source,
  sourceHandle,
  target,
  targetHandle,
  type: 'canvas',
});

describe('readable DAG layout', () => {
  it('places topological layers from left to right and nodes from top to bottom', () => {
    const script = node('script', 'script', { x: 700, y: 500 }, { width: 420, height: 320 });
    const planner = node('planner', 'storyboardPlanner', { x: 200, y: 200 }, { width: 520, height: 420 });
    const firstShot = node('shot-1', 'shot', { x: 900, y: 900 }, { width: 360, height: 300 });
    const secondShot = node('shot-2', 'shot', { x: 1_400, y: 900 }, { width: 360, height: 300 });

    const positions = computeReadableDagLayout(
      [secondShot, planner, script, firstShot],
      [
        edge(script.id, 'script', planner.id, 'script'),
        edge(planner.id, 'shots', firstShot.id, 'origin'),
        edge(planner.id, 'shots', secondShot.id, 'origin'),
      ],
      { anchor: { x: 100, y: 80 }, gapX: 180, gapY: 60 },
    );
    const byId = new Map(positions.map((item) => [item.nodeId, item.position]));

    expect(byId.get(script.id)).toEqual({ x: 100, y: 80 });
    expect(byId.get(planner.id)).toEqual({ x: 700, y: 80 });
    expect(byId.get(firstShot.id)).toEqual({ x: 1_400, y: 80 });
    expect(byId.get(secondShot.id)).toEqual({ x: 1_400, y: 440 });
  });

  it('stacks disconnected DAGs into separate vertical bands', () => {
    const first = node('first-a', 'script', { x: 0, y: 0 }, { width: 300, height: 200 });
    const second = node('first-b', 'textToImage', { x: 500, y: 0 }, { width: 320, height: 240 });
    const third = node('second-a', 'script', { x: 0, y: 500 }, { width: 300, height: 200 });
    const fourth = node('second-b', 'textToImage', { x: 500, y: 500 }, { width: 320, height: 240 });

    const positions = computeReadableDagLayout(
      [first, second, third, fourth],
      [
        edge(first.id, 'script', second.id, 'prompt'),
        edge(third.id, 'script', fourth.id, 'prompt'),
      ],
      {
        anchor: { x: 100, y: 80 },
        gapX: 180,
        gapY: 60,
        componentGapY: 200,
      },
    );
    const byId = new Map(positions.map((item) => [item.nodeId, item.position]));
    const firstBottom = Math.max(
      byId.get(first.id)!.y + first.height!,
      byId.get(second.id)!.y + second.height!,
    );
    const secondTop = Math.min(byId.get(third.id)!.y, byId.get(fourth.id)!.y);

    expect(secondTop).toBeGreaterThanOrEqual(firstBottom + 200);
  });
});
