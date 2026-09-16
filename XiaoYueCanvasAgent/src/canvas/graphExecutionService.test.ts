import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasGraphV2, ExecutionStatus } from './graphContracts';

const completions = new Map<string, (outcome: { status: 'succeeded' | 'failed' | 'cancelled'; jobId: string; error?: string }) => void>();

vi.mock('./nodeExecutionService', () => ({
  cancelNodeExecution: vi.fn(async () => undefined),
  executeNodeAction: vi.fn(async (_projectId: string, nodeId: string) => {
    const completion = new Promise<{ status: 'succeeded' | 'failed' | 'cancelled'; jobId: string; error?: string }>((resolve) => {
      completions.set(nodeId, resolve);
    });
    return { jobId: `job-${nodeId}`, recordId: `record-${nodeId}`, completion };
  }),
}));

import { recoverGraphExecutions, startGraphExecution } from './graphExecutionService';

const graph = (): CanvasGraphV2 => ({
  schemaVersion: 2,
  viewport: { x: 0, y: 0, zoom: 1 },
  generationRecords: [],
  executions: [],
  nodes: [
    { id: 'a', type: 'textToImage', position: { x: 0, y: 0 }, data: { kind: 'textToImage', label: 'A', prompt: 'a', status: 'idle', accent: '#fff' } },
    { id: 'c', type: 'imageToImage', position: { x: 300, y: 0 }, data: { kind: 'imageToImage', label: 'C', prompt: 'c', status: 'idle', accent: '#fff' } },
    { id: 'b', type: 'textToImage', position: { x: 0, y: 300 }, data: { kind: 'textToImage', label: 'B', prompt: 'b', status: 'idle', accent: '#fff' } },
  ],
  edges: [
    { id: 'a-c', source: 'a', sourceHandle: 'image', target: 'c', targetHandle: 'source', type: 'canvas' },
  ],
});

const waitForStatus = async (executionId: string, status: ExecutionStatus) => {
  const deadline = Date.now() + 2_000;
  while (Date.now() < deadline) {
    const execution = useCanvasStore.getState().executions.find((item) => item.id === executionId);
    if (execution?.status === status) return execution;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`执行批次未进入 ${status}`);
};

describe('graphExecutionService', () => {
  beforeEach(() => {
    completions.clear();
    const value = graph();
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: value.nodes.map((node) => ({ ...node, data: { ...node.data } })) as never,
      edges: value.edges as never,
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: null,
      dragStartSnapshot: null,
      revision: 0,
    });
  });

  it('continues an independent branch and blocks descendants of a failed node', async () => {
    const execution = startGraphExecution('project-1', undefined, {}, 2);
    await vi.waitFor(() => expect(completions.has('a') && completions.has('b')).toBe(true));
    completions.get('a')!({ status: 'failed', jobId: 'job-a', error: 'failed a' });
    completions.get('b')!({ status: 'succeeded', jobId: 'job-b' });

    const settled = await waitForStatus(execution.id, 'failed');
    expect(settled.nodes.find((node) => node.nodeId === 'a')?.status).toBe('failed');
    expect(settled.nodes.find((node) => node.nodeId === 'b')?.status).toBe('succeeded');
    expect(settled.nodes.find((node) => node.nodeId === 'c')?.status).toBe('blocked');
  });

  it('carries a result through an intermediate slot to a terminal slot', async () => {
    const value = graph();
    value.nodes = [
      { id: 'producer', type: 'textToImage', position: { x: 0, y: 0 }, data: { kind: 'textToImage', label: '生图', prompt: 'a', status: 'idle', accent: '#fff' } },
      { id: 'slot', type: 'image', position: { x: 300, y: 0 }, data: { kind: 'image', label: '中间占位', prompt: '', status: 'idle', accent: '#fff' } },
      { id: 'consumer', type: 'imageToImage', position: { x: 600, y: 0 }, data: { kind: 'imageToImage', label: '下游', prompt: 'c', status: 'idle', accent: '#fff' } },
      { id: 'final-slot', type: 'image', position: { x: 900, y: 0 }, data: { kind: 'image', label: '最终占位', prompt: '', status: 'idle', accent: '#fff' } },
    ];
    value.edges = [
      { id: 'producer-slot', source: 'producer', sourceHandle: 'image', target: 'slot', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
      { id: 'slot-consumer', source: 'slot', sourceHandle: 'image', target: 'consumer', targetHandle: 'source', type: 'canvas' },
      { id: 'consumer-final', source: 'consumer', sourceHandle: 'image', target: 'final-slot', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
    ];
    useCanvasStore.setState({
      nodes: value.nodes as never,
      edges: value.edges as never,
      executions: [],
      generationRecords: [],
    });

    const execution = startGraphExecution('project-1', undefined, {}, 2);
    await vi.waitFor(() => expect(completions.has('producer')).toBe(true));
    completions.get('producer')!({ status: 'succeeded', jobId: 'job-producer' });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(completions.has('consumer')).toBe(false);

    useCanvasStore.getState().updateNode('slot', {
      assetId: 'asset-1',
      status: 'success',
      mediaLoadState: 'ready',
      automation: { phase: 'done' },
    });

    await vi.waitFor(() => expect(completions.has('consumer')).toBe(true));
    completions.get('consumer')!({ status: 'succeeded', jobId: 'job-consumer' });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(useCanvasStore.getState().executions.find((item) => item.id === execution.id)?.status).toBe('running');

    useCanvasStore.getState().updateNode('final-slot', {
      assetId: 'asset-2',
      status: 'success',
      mediaLoadState: 'ready',
      automation: { phase: 'done' },
    });
    await waitForStatus(execution.id, 'succeeded');
  });

  it('does not interrupt an active execution during periodic recovery', async () => {
    const execution = startGraphExecution('project-1', undefined, {}, 2);
    await vi.waitFor(() => expect(completions.has('a') && completions.has('b')).toBe(true));

    recoverGraphExecutions();

    const current = useCanvasStore.getState().executions.find((item) => item.id === execution.id);
    expect(current?.status).toBe('running');
    expect(current?.nodes.filter((node) => node.status === 'running')).toHaveLength(2);

    completions.get('a')!({ status: 'succeeded', jobId: 'job-a' });
    completions.get('b')!({ status: 'succeeded', jobId: 'job-b' });
    await vi.waitFor(() => expect(completions.has('c')).toBe(true));
    completions.get('c')!({ status: 'succeeded', jobId: 'job-c' });
    await waitForStatus(execution.id, 'succeeded');
  });
});
