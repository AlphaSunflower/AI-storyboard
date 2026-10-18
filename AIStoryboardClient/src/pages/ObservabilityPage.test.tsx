import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ObservabilityPage, classifySubagentError } from './ObservabilityPage';
import { useAuthStore } from '../stores/authStore';
import { useProjectStore } from '../stores/projectStore';
import { agentApi } from '../api/agent';

vi.mock('../api/agent', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/agent')>();
  return {
    ...actual,
    agentApi: {
      ...actual.agentApi,
      listConversations: vi.fn(),
      listSubagentRuns: vi.fn(),
    },
  };
});

afterEach(cleanup);

describe('ObservabilityPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      checkAuth: vi.fn().mockResolvedValue(true),
    } as never);
    useProjectStore.setState({
      currentProject: { id: 'p1', name: '测试项目' } as never,
      loadProjects: vi.fn().mockResolvedValue(undefined),
    });
    vi.mocked(agentApi.listConversations).mockResolvedValue({
      data: { data: [{ id: 'c1', title: '测试会话' }] },
    } as never);
    vi.mocked(agentApi.listSubagentRuns).mockResolvedValue({
      data: {
        data: [{
          id: 'g1:writer', graphId: 'g1', conversationId: 'c1', nodeId: 'writer',
          role: 'writer', status: 'COMPLETED', output: '已写入文件', tokens: 12, durationMs: 30,
        }],
      },
    } as never);
  });

  it('展示任务图节点与状态', async () => {
    render(<MemoryRouter><ObservabilityPage /></MemoryRouter>);

    await waitFor(() => expect(screen.getAllByText('writer').length).toBeGreaterThan(0));
    expect(screen.getByText('完成')).toBeTruthy();
    expect(screen.getByText('已写入文件')).toBeTruthy();
    expect(screen.getByText('12 tokens')).toBeTruthy();
  });

  it('将错误归并为稳定分类', () => {
    expect(classifySubagentError('Timeout on blocking read')).toBe('执行超时');
    expect(classifySubagentError('节点没有获批的写工具')).toBe('审批未通过/拒绝');
    expect(classifySubagentError('Tool not found: foo')).toBe('工具不可用');
    expect(classifySubagentError('预算超限')).toBe('预算超限');
  });
});
