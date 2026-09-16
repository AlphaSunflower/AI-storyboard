import { describe, expect, it } from 'vitest';
import { planGraphExecution } from './graphExecutionPlanner';
import type { CanvasGraphV2 } from './graphContracts';

const graph = (): CanvasGraphV2 => ({
  schemaVersion: 2,
  viewport: { x: 0, y: 0, zoom: 1 },
  generationRecords: [],
  nodes: [
    { id: 'text', type: 'text', position: { x: 0, y: 0 }, data: { kind: 'text', label: '脚本', prompt: '', status: 'idle', accent: '#fff' } },
    { id: 'planner', type: 'storyboardPlanner', position: { x: 300, y: 0 }, data: { kind: 'storyboardPlanner', label: '拆解', prompt: '', status: 'idle', accent: '#fff' } },
    { id: 'shot-a', type: 'shot', position: { x: 600, y: 0 }, data: { kind: 'shot', label: '镜头 A', prompt: '', status: 'idle', accent: '#fff' } },
    { id: 'shot-b', type: 'shot', position: { x: 600, y: 300 }, data: { kind: 'shot', label: '镜头 B', prompt: '', status: 'idle', accent: '#fff' } },
  ],
  edges: [
    { id: 'e1', source: 'text', sourceHandle: 'text', target: 'planner', targetHandle: 'script', type: 'canvas' },
    { id: 'e2', source: 'planner', sourceHandle: 'shots', target: 'shot-a', targetHandle: 'origin', type: 'canvas' },
    { id: 'e3', source: 'planner', sourceHandle: 'shots', target: 'shot-b', targetHandle: 'origin', type: 'canvas' },
  ],
});

describe('planGraphExecution', () => {
  it('keeps dependency order and ignores non-executable input nodes', () => {
    const plan = planGraphExecution(graph());
    expect(plan.order).toEqual(['planner', 'shot-a', 'shot-b']);
    expect(plan.nodes.find((node) => node.nodeId === 'shot-a')?.dependencies).toEqual(['planner']);
    expect(plan.nodes.some((node) => node.nodeId === 'text')).toBe(false);
  });

  it('uses ancestor closure when explicit nodes are requested', () => {
    const plan = planGraphExecution(graph(), ['shot-a'], { 'shot-a': 'generateVideo' });
    expect(plan.order).toEqual(['planner', 'shot-a']);
    expect(plan.nodes.find((node) => node.nodeId === 'shot-a')?.actionId).toBe('generateVideo');
  });

  it('inserts result slots between producers and downstream actions', () => {
    const result = graph();
    result.nodes.push(
      { id: 'image-gen', type: 'textToImage', position: { x: 0, y: 0 }, data: { kind: 'textToImage', label: '生图', prompt: '', status: 'idle', accent: '#fff' } },
      { id: 'slot', type: 'image', position: { x: 300, y: 0 }, data: { kind: 'image', label: '结果图', prompt: '', status: 'idle', accent: '#fff' } },
      { id: 'video-gen', type: 'imageToVideo', position: { x: 600, y: 0 }, data: { kind: 'imageToVideo', label: '生视频', prompt: '', status: 'idle', accent: '#fff' } },
    );
    result.edges.push(
      { id: 'e4', source: 'image-gen', sourceHandle: 'image', target: 'slot', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
      { id: 'e5', source: 'slot', sourceHandle: 'image', target: 'video-gen', targetHandle: 'first', type: 'canvas' },
    );

    const plan = planGraphExecution(result, ['video-gen']);

    expect(plan.order).toEqual(['image-gen', 'slot', 'video-gen']);
    expect(plan.nodes.find((node) => node.nodeId === 'slot')?.actionId).toBe('__result_slot__');
    expect(plan.nodes.find((node) => node.nodeId === 'video-gen')?.dependencies)
      .toEqual(expect.arrayContaining(['image-gen', 'slot']));
  });

  it('keeps an already filled result slot in the plan so downstream work can continue', () => {
    const result = graph();
    result.nodes.push(
      { id: 'image-gen', type: 'textToImage', position: { x: 0, y: 0 }, data: { kind: 'textToImage', label: '生图', prompt: '', status: 'idle', accent: '#fff' } },
      { id: 'slot', type: 'image', position: { x: 300, y: 0 }, data: { kind: 'image', label: '已填结果槽', prompt: '', status: 'success', accent: '#fff', assetId: 'asset-1' } },
      { id: 'image-to-image', type: 'imageToImage', position: { x: 600, y: 0 }, data: { kind: 'imageToImage', label: '继续处理', prompt: '', status: 'idle', accent: '#fff' } },
    );
    result.edges.push(
      { id: 'e-slot', source: 'image-gen', sourceHandle: 'image', target: 'slot', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
      { id: 'e-consumer', source: 'slot', sourceHandle: 'image', target: 'image-to-image', targetHandle: 'source', type: 'canvas' },
    );

    const plan = planGraphExecution(result, ['image-to-image']);

    expect(plan.order).toEqual(['image-gen', 'slot', 'image-to-image']);
    expect(plan.nodes.find((node) => node.nodeId === 'slot')?.actionId).toBe('__result_slot__');
    expect(plan.nodes.find((node) => node.nodeId === 'image-to-image')?.dependencies)
      .toEqual(expect.arrayContaining(['image-gen', 'slot']));
  });
});
