// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  getCapabilities: vi.fn(),
  submit: vi.fn(),
  subscribe: vi.fn(),
  getJob: vi.fn(),
}));

vi.mock('./capabilities', () => ({
  jobAdapter: {
    getCapabilities: mocks.getCapabilities,
    submit: mocks.submit,
    subscribe: mocks.subscribe,
    getJob: mocks.getJob,
  },
}));

vi.mock('./canvasAssets', () => ({
  uploadCanvasAsset: vi.fn(),
  linkGeneratedCanvasAssets: vi.fn().mockResolvedValue([]),
  listCanvasAssets: vi.fn().mockResolvedValue([]),
}));

vi.mock('./projectStorage', () => ({
  saveAsset: vi.fn().mockResolvedValue(undefined),
  getAsset: vi.fn().mockResolvedValue(undefined),
  putGenerationQueueItem: vi.fn().mockResolvedValue(undefined),
  listGenerationQueueItems: vi.fn().mockResolvedValue([]),
}));

import { submitImageEdit, submitImageGeneration } from './jobRunner';
import { useCanvasStore } from '../store/canvasStore';

const node = {
  id: 'image-1',
  type: 'textToImage',
  position: { x: 0, y: 0 },
  data: { kind: 'textToImage', label: '生图', prompt: '一只猫', status: 'idle', accent: '#fff' },
};

describe('生成任务终态兜底轮询', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getCapabilities.mockResolvedValue([
      { id: 'image.generate', label: '生图', category: 'remote', available: true },
      { id: 'image.redraw', label: '图生图', category: 'remote', available: true },
    ]);
    mocks.submit.mockResolvedValue({ jobId: 'job-1' });
    // 事件流一条都不发：模拟 SSE 中途静默断掉（真实事故：12 条事件只收到 8 条）
    mocks.subscribe.mockReturnValue(() => undefined);
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [node] as never,
      edges: [],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      revision: 0,
    });
  });

  it('事件流丢终态帧时靠任务快照收敛，onTerminal 只通知一次', async () => {
    vi.useFakeTimers();
    try {
      mocks.getJob.mockResolvedValue({ status: 'completed', progress: 1, assets: [] });
      const onTerminal = vi.fn();
      await submitImageGeneration('一只猫', 'image-1', vi.fn(), { onTerminal });

      expect(onTerminal).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(4_100);

      expect(onTerminal).toHaveBeenCalledTimes(1);
      expect(onTerminal.mock.calls[0][0]).toMatchObject({ status: 'succeeded', jobId: 'job-1' });
      const state = useCanvasStore.getState();
      expect(state.nodes[0].data.isGenerating).toBe(false);
      expect(state.generationRecords[0]?.status).toBe('completed');

      // 收敛之后不再继续轮询
      await vi.advanceTimersByTimeAsync(12_000);
      expect(mocks.getJob).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('任务快照是失败时按失败收敛，节点不再停在生成中', async () => {
    vi.useFakeTimers();
    try {
      mocks.getJob.mockResolvedValue({ status: 'failed', progress: 0.2, error: '内容审核不通过' });
      const onTerminal = vi.fn();
      await submitImageGeneration('一只猫', 'image-1', vi.fn(), { onTerminal });

      await vi.advanceTimersByTimeAsync(4_100);

      expect(onTerminal).toHaveBeenCalledTimes(1);
      expect(onTerminal.mock.calls[0][0]).toMatchObject({ status: 'failed', error: '内容审核不通过' });
      const state = useCanvasStore.getState();
      expect(state.nodes[0].data.isGenerating).toBe(false);
      expect(state.generationRecords[0]?.status).toBe('failed');
    } finally {
      vi.useRealTimers();
    }
  });

  it('迟到的「事件流断开」失败不会把已经完成的结果改回失败', async () => {
    let listener: ((event: { type: string; message?: string; jobId: string }) => void) | null = null;
    mocks.subscribe.mockImplementation((_jobId: string, next: typeof listener) => {
      listener = next;
      return () => undefined;
    });
    const onTerminal = vi.fn();
    await submitImageGeneration('一只猫', 'image-1', vi.fn(), { onTerminal });

    listener!({ type: 'job.completed', jobId: 'job-1' });
    expect(onTerminal).toHaveBeenCalledTimes(1);
    listener!({ type: 'job.failed', jobId: 'job-1', message: '任务事件流已断开' });

    const state = useCanvasStore.getState();
    expect(state.generationRecords[0]?.status).toBe('completed');
    expect(onTerminal).toHaveBeenCalledTimes(1);
  });

  it('终态同时回收节点阶段：分组标题条不再因为手动重跑而一直显示失败', async () => {
    let listener: ((event: { type: string; message?: string; jobId: string }) => void) | null = null;
    mocks.subscribe.mockImplementation((_jobId: string, next: typeof listener) => {
      listener = next;
      return () => undefined;
    });
    await submitImageGeneration('一只猫', 'image-1', vi.fn(), {});

    const phaseOf = () => (useCanvasStore.getState().nodes[0]?.data.automation as { phase?: string } | undefined)?.phase;
    expect(phaseOf()).toBe('running');

    listener!({ type: 'job.completed', jobId: 'job-1' });
    expect(phaseOf()).toBe('done');
  });

  it('图生图没有区域掩码时按整图编辑在同一节点提交，不要求重新发起', async () => {
    await submitImageEdit('把背景换成海边', 'image-1', vi.fn(), {
      sourceAssetIds: [],
      params: { model: 'gpt-image-2' },
    });

    expect(mocks.submit).toHaveBeenCalledWith(expect.objectContaining({
      capabilityId: 'image.redraw',
      params: expect.objectContaining({
        prompt: '把背景换成海边',
        actionId: 'generate',
        mode: 'image-to-image',
        model: 'gpt-image-2',
      }),
    }), undefined);
    // 不要求掩码资产：没有 redrawMaskAssetId 也能提交
    const submitted = mocks.submit.mock.calls.at(-1)?.[0] as { params?: Record<string, unknown> } | undefined;
    expect(submitted?.params?.redrawMaskAssetId).toBeUndefined();
  });
});
