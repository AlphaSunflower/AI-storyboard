// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAgentStore } from '../store/agentStore';
import { useCanvasStore } from '../store/canvasStore';
import type { JobAdapter, JobEvent, JobSnapshot, ProjectDocumentV1 } from '../types';
import { deleteGenerationQueueItem, listGenerationQueueItems, putGenerationQueueItem } from '../services/projectStorage';
import { AgentPanel } from './AgentPanel';

const project: ProjectDocumentV1 = {
  schemaVersion: 1,
  id: 'project-agent-test',
  title: 'Agent UI Test',
  nodes: [],
  edges: [],
  viewport: { x: 0, y: 0, zoom: 1 },
  createdAt: 1,
  updatedAt: 1,
};

describe('AgentPanel', () => {
  beforeEach(async () => {
    cleanup();
    useCanvasStore.setState({ projectId: project.id, nodes: [], edges: [], selectedNodeId: null, past: [], future: [], revision: 0 });
    useAgentStore.setState({ sessions: {} });
    // 生成队列在 IndexedDB 里是持久化的：不清理会串到下一个用例（上一个用例遗留的 running
    // 任务会被再次恢复订阅，导致"未订阅"断言假失败）。
    for (const item of await listGenerationQueueItems()) {
      await deleteGenerationQueueItem(item.id);
    }
  });

  it('renders a real request and reports the unconfigured adapter without mutating the canvas', async () => {
    const user = userEvent.setup();
    render(<AgentPanel project={project} />);

    const input = screen.getByRole('textbox', { name: '给设计助理发送请求' });
    const send = screen.getByRole('button', { name: '发送请求' });
    expect(send).toBeDisabled();

    await user.type(input, '分析当前版式层级');
    expect(send).toBeEnabled();
    await user.click(send);

    expect(await screen.findByText('分析当前版式层级')).toBeVisible();
    const error = await screen.findByRole('alert');
    expect(within(error).getByText('执行服务未连接')).toBeVisible();
    expect(useCanvasStore.getState().nodes).toHaveLength(0);
    expect(useCanvasStore.getState().edges).toHaveLength(0);
  });

  it('consumes real job events and keeps the conversation in the project session', async () => {
    const listeners = new Map<string, (event: JobEvent) => void>();
    const adapter: JobAdapter = {
      getStatus: vi.fn(async () => ({ connected: true, label: '测试执行服务' })),
      getCapabilities: vi.fn(async () => [{ id: 'agent.request', label: '设计助理请求', category: 'remote', available: true }]),
      submit: vi.fn(async () => ({ jobId: 'job-1' })),
      subscribe: vi.fn((jobId, listener) => { listeners.set(jobId, listener); return () => listeners.delete(jobId); }),
      cancel: vi.fn(async () => undefined),
      fetchAsset: vi.fn(async () => { throw new Error('本测试不返回素材'); }),
    };
    const user = userEvent.setup();
    const view = render(<AgentPanel project={project} adapter={adapter} />);

    const input = screen.getByRole('textbox', { name: '给设计助理发送请求' });
    await user.type(input, '调整信息层级');
    await user.click(screen.getByRole('button', { name: '发送请求' }));
    expect(adapter.submit).toHaveBeenCalledWith(expect.objectContaining({
      capabilityId: 'agent.request',
      context: expect.objectContaining({ projectId: project.id }),
      params: { prompt: '调整信息层级' },
    }));
    expect(await screen.findByText('等待执行')).toBeVisible();

    act(() => {
      listeners.get('job-1')?.({ type: 'job.started', jobId: 'job-1' });
      listeners.get('job-1')?.({ type: 'job.progress', jobId: 'job-1', progress: .42 });
      listeners.get('job-1')?.({ type: 'agent.message', jobId: 'job-1', message: '先统一', messageId: 'answer', delta: true });
      listeners.get('job-1')?.({ type: 'agent.message', jobId: 'job-1', message: '间距层级。', messageId: 'answer', delta: true });
    });
    expect(screen.getByText('先统一间距层级。')).toBeVisible();
    expect(screen.getByText('42%')).toBeVisible();

    act(() => listeners.get('job-1')?.({ type: 'job.completed', jobId: 'job-1' }));
    expect(screen.queryByText('42%')).not.toBeInTheDocument();
    view.unmount();

    render(<AgentPanel project={project} adapter={adapter} />);
    expect(screen.getByText('调整信息层级')).toBeVisible();
    expect(screen.getByText('先统一间距层级。')).toBeVisible();
  });

  /**
   * 刷新恢复：画布服务的任务事件流支持续传，但刷新会丢掉组件内存里的订阅表。
   * 这里要求挂载后从生成队列里重新接上仍在运行的任务，并且只在快照确实还在跑时才订阅。
   */
  const queueItem = (jobId: string) => ({
    id: `agent-${jobId}`,
    projectId: project.id,
    canvasId: 'canvas-recovery',
    producerNodeId: '',
    generationRecordId: `agent-${jobId}`,
    requestSnapshot: { capabilityId: 'agent.request', sourceAssetIds: [], params: { prompt: '继续把画布补完' } },
    status: 'running' as const,
    jobId,
    attempt: 1,
    createdAt: 1,
    updatedAt: 1,
  });

  const recoveryAdapter = (snapshot: JobSnapshot | null) => {
    const listeners = new Map<string, (event: JobEvent) => void>();
    const adapter: JobAdapter = {
      getStatus: vi.fn(async () => ({ connected: true, label: '测试执行服务' })),
      getCapabilities: vi.fn(async () => []),
      submit: vi.fn(async () => ({ jobId: 'unused' })),
      getJob: vi.fn(async () => {
        if (!snapshot) throw new Error('快照不可用');
        return snapshot;
      }),
      subscribe: vi.fn((jobId, listener) => {
        listeners.set(jobId, listener);
        return () => listeners.delete(jobId);
      }),
      cancel: vi.fn(async () => undefined),
      fetchAsset: vi.fn(async () => { throw new Error('本测试不返回素材'); }),
    };
    return { adapter, listeners };
  };

  it('刷新后重新订阅仍在运行的任务，事件继续驱动面板', async () => {
    const item = queueItem('job-resume');
    await putGenerationQueueItem(item);
    const { adapter, listeners } = recoveryAdapter({ jobId: 'job-resume', status: 'running', progress: .3 });

    render(<AgentPanel project={project} adapter={adapter} />);

    await waitFor(() => expect(adapter.subscribe).toHaveBeenCalledWith('job-resume', expect.any(Function)));
    expect(adapter.getJob).toHaveBeenCalledWith('job-resume');
    // 恢复出来的任务进入"运行中"计数，而不是停在等待执行
    expect(await screen.findByText('1 个任务运行中')).toBeVisible();

    act(() => listeners.get('job-resume')?.({ type: 'job.progress', jobId: 'job-resume', progress: .8 }));
    expect(await screen.findByText('80%')).toBeVisible();
  });

  it('快照已是终态时不再订阅，并把队列项落终态', async () => {
    const item = queueItem('job-done');
    await putGenerationQueueItem(item);
    const { adapter } = recoveryAdapter({ jobId: 'job-done', status: 'completed', progress: 1 });

    render(<AgentPanel project={project} adapter={adapter} />);

    await waitFor(async () => {
      const items = await listGenerationQueueItems(project.id);
      expect(items.find((candidate) => candidate.id === item.id)?.status).toBe('terminal');
    });
    expect(adapter.subscribe).not.toHaveBeenCalled();
    // 已结束的任务不应再占用"运行中"计数
    expect(screen.queryByText(/个任务运行中/)).not.toBeInTheDocument();
  });
});
