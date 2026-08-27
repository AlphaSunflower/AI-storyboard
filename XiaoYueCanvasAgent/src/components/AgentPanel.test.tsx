// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAgentStore } from '../store/agentStore';
import { useCanvasStore } from '../store/canvasStore';
import type { JobAdapter, JobEvent, ProjectDocumentV1 } from '../types';
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
  beforeEach(() => {
    cleanup();
    useCanvasStore.setState({ projectId: project.id, nodes: [], edges: [], selectedNodeId: null, past: [], future: [], revision: 0 });
    useAgentStore.setState({ sessions: {} });
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
    expect(within(error).getByText('未配置执行服务，请设置 VITE_JOB_API_URL')).toBeVisible();
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

    expect(await screen.findByText('测试执行服务')).toBeVisible();
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
});
