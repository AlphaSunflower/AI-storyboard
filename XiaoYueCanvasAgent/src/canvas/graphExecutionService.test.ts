import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasGraphV2, ExecutionStatus } from './graphContracts';

const completions = new Map<string, (outcome: { status: 'succeeded' | 'failed' | 'cancelled'; jobId: string; error?: string }) => void>();

vi.mock('./nodeExecutionService', () => ({
  cancelNodeExecution: vi.fn(async () => undefined),
  // 这个测试锁的是浏览器内执行器；服务端计划为空 → 走本地执行路径。
  describeNodeExecution: vi.fn(() => undefined),
  executeNodeAction: vi.fn(async (_projectId: string, nodeId: string) => {
    const completion = new Promise<{ status: 'succeeded' | 'failed' | 'cancelled'; jobId: string; error?: string }>((resolve) => {
      completions.set(nodeId, resolve);
    });
    return { jobId: `job-${nodeId}`, recordId: `record-${nodeId}`, completion };
  }),
}));

vi.mock('../services/canvasExecution', () => ({
  createCanvasExecution: vi.fn(async () => { throw new Error('offline'); }),
  getCanvasExecution: vi.fn(),
  listCanvasExecutions: vi.fn(async () => []),
  cancelCanvasExecution: vi.fn(),
  isFinalExecutionStatus: () => false,
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

  it('does not let an unfilled result slot starve the next node in a serial batch', async () => {
    useCanvasStore.setState({
      nodes: [
        { id: 'p1', type: 'textToImage', position: { x: 0, y: 0 }, data: { kind: 'textToImage', label: '一', prompt: 'a', status: 'idle', accent: '#fff' } },
        { id: 's1', type: 'image', position: { x: 300, y: 0 }, data: { kind: 'image', label: '结果 1', prompt: '', status: 'idle', accent: '#fff' } },
        { id: 'p2', type: 'textToImage', position: { x: 0, y: 300 }, data: { kind: 'textToImage', label: '二', prompt: 'b', status: 'idle', accent: '#fff' } },
        { id: 's2', type: 'image', position: { x: 300, y: 300 }, data: { kind: 'image', label: '结果 2', prompt: '', status: 'idle', accent: '#fff' } },
      ] as never,
      edges: [
        { id: 'p1-s1', source: 'p1', sourceHandle: 'image', target: 's1', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
        { id: 'p2-s2', source: 'p2', sourceHandle: 'image', target: 's2', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
      ] as never,
      executions: [],
    });

    // 分组执行是串行的（maxParallel=1）
    const execution = startGraphExecution('project-1', ['p1', 'p2'], {}, 1);
    await vi.waitFor(() => expect(completions.has('p1')).toBe(true));
    expect(completions.has('p2')).toBe(false);

    // p1 跑完、结果还没落地：结果槽在等，但 p2 不能被它挡住
    completions.get('p1')!({ status: 'succeeded', jobId: 'job-p1' });
    await vi.waitFor(() => expect(completions.has('p2')).toBe(true));

    completions.get('p2')!({ status: 'succeeded', jobId: 'job-p2' });
    await new Promise((resolve) => setTimeout(resolve, 40));
    // 两个结果槽都没填，批次还不能算结束
    expect(useCanvasStore.getState().executions.find((item) => item.id === execution.id)?.status).toBe('running');
  });

  it('settles a node whose terminal event was lost once its generation record is terminal', async () => {
    const execution = startGraphExecution('project-1', undefined, {}, 1);
    await vi.waitFor(() => expect(completions.has('a')).toBe(true));

    // 终态事件丢失：onTerminal 永远不触发，但生成记录自己走到了 completed
    useCanvasStore.getState().appendGenerationRecord({
      id: 'record-a',
      producerNodeId: 'a',
      actionId: 'generate',
      attempt: 1,
      status: 'completed',
      progress: 1,
      queueStatus: 'terminal',
      materializationStatus: 'pending',
      materializedAssetIds: [],
      resultNodeIds: [],
      sources: [],
      prompt: 'a',
      effectivePrompt: 'a',
      params: {},
      jobId: 'job-a',
      startedAt: Date.now(),
    } as never);

    // 批次必须继续往前走，而不是卡在第一个节点
    await vi.waitFor(() => expect(completions.has('c')).toBe(true));
    completions.get('c')!({ status: 'succeeded', jobId: 'job-c' });
    await vi.waitFor(() => expect(completions.has('b')).toBe(true));
    completions.get('b')!({ status: 'succeeded', jobId: 'job-b' });
    await waitForStatus(execution.id, 'succeeded');
  });

  it('settles a reloaded batch: landed results stay done, the rest stop pretending to be generating', () => {
    const nodes = [
      { id: 'p1', type: 'textToImage', position: { x: 0, y: 0 }, data: { kind: 'textToImage', label: '一', prompt: 'a', status: 'idle', accent: '#fff', automation: { phase: 'running' } } },
      { id: 's1', type: 'image', position: { x: 300, y: 0 }, data: { kind: 'image', label: '结果 1', prompt: '', status: 'success', accent: '#fff', assetId: 'asset-1', mediaLoadState: 'ready' } },
      { id: 'p2', type: 'textToImage', position: { x: 0, y: 300 }, data: { kind: 'textToImage', label: '二', prompt: 'b', status: 'idle', accent: '#fff' } },
      { id: 's2', type: 'image', position: { x: 300, y: 300 }, data: { kind: 'image', label: '结果 2', prompt: '', status: 'idle', accent: '#fff' } },
    ];
    const edges = [
      { id: 'p1-s1', source: 'p1', sourceHandle: 'image', target: 's1', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
      { id: 'p2-s2', source: 'p2', sourceHandle: 'image', target: 's2', targetHandle: 'origin', type: 'canvas', data: { resultSlot: true, resultSlotOrder: 0 } },
    ];
    useCanvasStore.setState({
      nodes: nodes as never,
      edges: edges as never,
      executions: [{
        id: 'execution-reloaded',
        projectId: 'project-1',
        canvasId: 'canvas-1',
        nodeIds: ['p1', 's1', 'p2', 's2'],
        status: 'running',
        maxParallel: 1,
        nodes: [
          { nodeId: 'p1', actionId: 'generate', status: 'running', jobId: 'job-p1' },
          { nodeId: 's1', actionId: '__result_slot__', status: 'pending' },
          { nodeId: 'p2', actionId: 'generate', status: 'pending' },
          { nodeId: 's2', actionId: '__result_slot__', status: 'pending' },
        ],
        createdAt: Date.now(),
        updatedAt: Date.now(),
      }] as never,
    });

    recoverGraphExecutions();

    const execution = useCanvasStore.getState().executions[0];
    expect(execution.status).toBe('interrupted');
    expect(execution.nodes.map((node) => [node.nodeId, node.status])).toEqual([
      ['p1', 'succeeded'],
      ['s1', 'succeeded'],
      ['p2', 'interrupted'],
      ['s2', 'interrupted'],
    ]);
    const byId = new Map(useCanvasStore.getState().nodes.map((node) => [node.id, node]));
    // 已经出结果的节点不再显示「生成中」
    expect((byId.get('p1')?.data.automation as { phase?: string }).phase).toBe('done');
    expect(byId.get('p1')?.data.isGenerating).toBe(false);
    // 没跑完的节点落到 blocked + 明确原因，分组标题条不再一直显示「生成中」
    expect((byId.get('p2')?.data.automation as { phase?: string }).phase).toBe('blocked');
    expect((byId.get('p2')?.data.automation as { error?: string }).error).toContain('已中断');
    expect((byId.get('s2')?.data.automation as { phase?: string }).phase).toBe('blocked');
  });
});
