import { describe, expect, it } from 'vitest';
import type { CanvasEdge, CanvasNode } from '../../types';
import { buildElkGraph, computeDagLayout } from './dagLayout';

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
  id: string,
  source: string,
  sourceHandle: string,
  target: string,
  targetHandle: string,
): CanvasEdge => ({
  id,
  source,
  sourceHandle,
  target,
  targetHandle,
  type: 'canvas',
});

describe('ELK DAG layout', () => {
  it('maps real handles to fixed-order ELK ports and excludes edges outside the scope', () => {
    const planner = node('planner', 'storyboardPlanner', { x: 0, y: 0 }, { width: 520, height: 420 });
    const shot = node('shot-1', 'shot', { x: 600, y: 0 }, { width: 360, height: 300 });
    const outside = node('outside', 'image', { x: 1200, y: 0 }, { width: 250, height: 250 });
    const graph = buildElkGraph(
      [planner, shot],
      [
        edge('planner-shot', planner.id, 'shots', shot.id, 'origin'),
        edge('planner-outside', planner.id, 'shots', outside.id, 'origin'),
        edge('invalid-handle', planner.id, '__missing__', shot.id, 'origin'),
      ],
      { direction: 'right' },
    );

    const plannerGraph = graph.children?.find((child) => child.id === planner.id);
    const shotGraph = graph.children?.find((child) => child.id === shot.id);
    expect(plannerGraph?.layoutOptions?.['elk.portConstraints']).toBe('FIXED_ORDER');
    expect(plannerGraph?.ports).toEqual([
      expect.objectContaining({
        id: 'planner::script',
        layoutOptions: expect.objectContaining({ 'elk.port.side': 'WEST', 'elk.port.index': '0' }),
      }),
      expect.objectContaining({
        id: 'planner::reference',
        layoutOptions: expect.objectContaining({ 'elk.port.side': 'WEST', 'elk.port.index': '1' }),
      }),
      expect.objectContaining({
        id: 'planner::shots',
        layoutOptions: expect.objectContaining({ 'elk.port.side': 'EAST', 'elk.port.index': '0' }),
      }),
    ]);
    expect(shotGraph?.ports).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 'shot-1::origin' }),
      expect.objectContaining({ id: 'shot-1::reference' }),
      expect.objectContaining({ id: 'shot-1::image' }),
      expect.objectContaining({ id: 'shot-1::video' }),
    ]));
    expect(graph.edges).toEqual([
      expect.objectContaining({
        id: 'planner-shot',
        sources: ['planner::shots'],
        targets: ['shot-1::origin'],
      }),
    ]);
  });

  it('keeps each ELK layer in one vertical column and preserves explicit spacing', async () => {
    const planner = node('planner', 'storyboardPlanner', { x: 1_000, y: 700 }, { width: 520, height: 420 });
    const firstShot = node('shot-1', 'shot', { x: 2_000, y: 110 }, { width: 360, height: 300 });
    const secondShot = node('shot-2', 'shot', { x: 2_420, y: 110 }, { width: 360, height: 300 });
    const script = node('script', 'script', { x: 200, y: 900 }, { width: 420, height: 320 });
    const nodes = [secondShot, planner, firstShot, script];
    const edges = [
      edge('script-planner', script.id, 'script', planner.id, 'script'),
      edge('planner-first', planner.id, 'shots', firstShot.id, 'origin'),
      edge('planner-second', planner.id, 'shots', secondShot.id, 'origin'),
    ];

    const positions = await computeDagLayout(nodes, edges, {
      anchor: { x: 120, y: 80 },
      gapX: 240,
      gapY: 96,
    });
    const byId = new Map(positions.map((item) => [item.nodeId, item.position]));
    const scriptPosition = byId.get(script.id)!;
    const plannerPosition = byId.get(planner.id)!;
    const firstPosition = byId.get(firstShot.id)!;
    const secondPosition = byId.get(secondShot.id)!;

    expect(scriptPosition.x).toBeLessThan(plannerPosition.x);
    expect(plannerPosition.x).toBeLessThan(firstPosition.x);
    expect(firstPosition.x).toBe(secondPosition.x);
    expect(secondPosition.y - (firstPosition.y + firstShot.height!)).toBeGreaterThanOrEqual(96);
    expect(Math.min(...positions.map((item) => item.position.x))).toBe(120);
    expect(Math.min(...positions.map((item) => item.position.y))).toBe(80);
  });

  it('returns deterministic positions for the same graph', async () => {
    const source = node('source', 'script', { x: 0, y: 0 }, { width: 420, height: 320 });
    const planner = node('planner', 'storyboardPlanner', { x: 500, y: 0 }, { width: 520, height: 420 });
    const shots = ['a', 'b', 'c'].map((id, index) =>
      node(`shot-${id}`, 'shot', { x: 1_000 + index * 400, y: 0 }, { width: 360, height: 300 }));
    const nodes = [source, planner, ...shots];
    const edges = [
      edge('source-planner', source.id, 'script', planner.id, 'script'),
      ...shots.map((shot) => edge(`planner-${shot.id}`, planner.id, 'shots', shot.id, 'origin')),
    ];

    const first = await computeDagLayout(nodes, edges);
    const second = await computeDagLayout(nodes, edges);
    expect(second).toEqual(first);
  });

  it('keeps the legacy downward direction compatible', async () => {
    const script = node('script', 'script', { x: 400, y: 700 }, { width: 420, height: 320 });
    const planner = node('planner', 'storyboardPlanner', { x: 400, y: 1_200 }, { width: 520, height: 420 });

    const positions = await computeDagLayout(
      [planner, script],
      [edge('script-planner', script.id, 'script', planner.id, 'script')],
      { direction: 'down', anchor: { x: 60, y: 100 }, gapX: 180, gapY: 140 },
    );
    const byId = new Map(positions.map((item) => [item.nodeId, item.position]));
    expect(byId.get(script.id)!.y).toBeLessThan(byId.get(planner.id)!.y);
    expect(byId.get(script.id)!.x + script.width! / 2)
      .toBe(byId.get(planner.id)!.x + planner.width! / 2);
  });

  it('stacks disconnected workflows into separate vertical bands', async () => {
    const first = node('first-a', 'script', { x: 0, y: 0 }, { width: 300, height: 200 });
    const second = node('first-b', 'textToImage', { x: 500, y: 0 }, { width: 320, height: 240 });
    const third = node('second-a', 'script', { x: 0, y: 500 }, { width: 300, height: 200 });
    const fourth = node('second-b', 'textToImage', { x: 500, y: 500 }, { width: 320, height: 240 });
    const nodes = [first, second, third, fourth];
    const edges = [
      edge('first-edge', first.id, 'script', second.id, 'prompt'),
      edge('second-edge', third.id, 'script', fourth.id, 'prompt'),
    ];

    const positions = await computeDagLayout(nodes, edges, {
      anchor: { x: 100, y: 80 },
      gapX: 180,
      gapY: 60,
    });
    const byId = new Map(positions.map((item) => [item.nodeId, item.position]));
    const firstBottom = Math.max(
      byId.get(first.id)!.y + first.height!,
      byId.get(second.id)!.y + second.height!,
    );
    const secondTop = Math.min(byId.get(third.id)!.y, byId.get(fourth.id)!.y);

    expect(secondTop).toBeGreaterThanOrEqual(firstBottom + 360);
  });

  it('stacks fan-in inputs vertically to the left of their shared target', async () => {
    const target = node('refine', 'imageGeneration', { x: 800, y: 200 }, { width: 520, height: 420 });
    const inputWidths = [250, 420, 340];
    const inputs = ['one', 'two', 'three'].map((id, index) =>
      node(`image-${id}`, 'image', { x: 100 + index * 320, y: 200 }, { width: inputWidths[index], height: 250 }));
    const nodes = [...inputs, target];
    const edges = inputs.map((input, index) =>
      edge(`edge-${index}`, input.id, 'image', target.id, 'reference'));

    const positions = await computeDagLayout(nodes, edges, {
      anchor: { x: 80, y: 60 },
      gapX: 220,
      gapY: 80,
    });
    const byId = new Map(positions.map((item) => [item.nodeId, item.position]));
    const inputPositions = inputs.map((input) => byId.get(input.id)!);

    expect(new Set(inputPositions.map((position) => position.x)).size).toBe(1);
    expect(inputPositions[1].y - inputPositions[0].y).toBeGreaterThanOrEqual(250 + 80);
    expect(inputPositions[2].y - inputPositions[1].y).toBeGreaterThanOrEqual(250 + 80);
    expect(byId.get(target.id)!.x).toBeGreaterThan(inputPositions[0].x);
    const inputsMiddle = (inputPositions[0].y + inputPositions[2].y + inputs[2].height!) / 2;
    const targetMiddle = byId.get(target.id)!.y + target.height! / 2;
    expect(Math.abs(targetMiddle - inputsMiddle)).toBeLessThan(.5);
  });

  it('promotes image results with different upstream depths to one fan-in column', async () => {
    const firstScript = node('first-script', 'script', { x: 0, y: 0 }, { width: 300, height: 200 });
    const firstProducer = node('first-producer', 'textToImage', { x: 400, y: 0 }, { width: 360, height: 300 });
    const firstImage = node('first-image', 'image', { x: 800, y: 0 }, { width: 250, height: 250 });
    const secondScript = node('second-script', 'script', { x: 0, y: 500 }, { width: 300, height: 200 });
    const secondProducer = node('second-producer', 'imageGeneration', { x: 400, y: 500 }, { width: 360, height: 300 });
    const secondImage = node('second-image', 'image', { x: 800, y: 500 }, { width: 250, height: 250 });
    const thirdProducer = node('third-producer', 'imageGeneration', { x: 1_200, y: 500 }, { width: 360, height: 300 });
    const thirdImage = node('third-image', 'image', { x: 1_600, y: 500 }, { width: 250, height: 250 });
    const target = node('target', 'imageGeneration', { x: 2_100, y: 250 }, { width: 520, height: 420 });
    const nodes = [
      firstScript, firstProducer, firstImage,
      secondScript, secondProducer, secondImage,
      thirdProducer, thirdImage, target,
    ];
    const edges = [
      edge('fs-fp', firstScript.id, 'script', firstProducer.id, 'prompt'),
      edge('fp-fi', firstProducer.id, 'image', firstImage.id, 'origin'),
      edge('ss-sp', secondScript.id, 'script', secondProducer.id, 'prompt'),
      edge('sp-si', secondProducer.id, 'image', secondImage.id, 'origin'),
      edge('si-tp', secondImage.id, 'image', thirdProducer.id, 'reference'),
      edge('tp-ti', thirdProducer.id, 'image', thirdImage.id, 'origin'),
      edge('fi-target', firstImage.id, 'image', target.id, 'reference'),
      edge('ti-target', thirdImage.id, 'image', target.id, 'reference'),
    ];

    const positions = await computeDagLayout(nodes, edges, { gapX: 180, gapY: 80 });
    const byId = new Map(positions.map((item) => [item.nodeId, item.position]));
    expect(byId.get(firstImage.id)!.x).toBe(byId.get(thirdImage.id)!.x);
    expect(byId.get(firstImage.id)!.x).toBeLessThan(byId.get(target.id)!.x);
    expect(byId.get(thirdImage.id)!.x).toBeLessThan(byId.get(target.id)!.x);
  });
});
