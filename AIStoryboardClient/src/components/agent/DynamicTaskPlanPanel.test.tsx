import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentTaskPlanView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { DynamicTaskPlanPanel } from './DynamicTaskPlanPanel';

const basePlan: AgentTaskPlanView = {
  planId: 'plan-1',
  conversationId: 'conversation-1',
  goal: '生成一段产品演示视频',
  state: 'WAITING_HUMAN',
  version: 3,
  replanCount: 0,
  error: null,
  nodes: [
    {
      nodeId: 'node-1',
      capabilityKey: 'storyboard.generate',
      title: '生成分镜',
      state: 'WAITING_HUMAN',
      attemptCount: 2,
      dependencies: [],
      hitlPolicy: 'BEFORE',
      evaluationMode: 'HYBRID',
      qualityCriteria: '镜头描述完整',
      outputSummary: null,
      asyncTaskId: null,
      error: '等待确认',
    },
  ],
  edges: [],
};

function apiResponse(plan: AgentTaskPlanView | null) {
  return {
    data: {
      code: 0,
      message: '',
      data: plan,
    },
  } as never;
}

describe('DynamicTaskPlanPanel', () => {
  beforeEach(() => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
    useAgentStore.setState({ activeConversationId: null });
  });

  it('renders task and node execution details', async () => {
    vi.spyOn(agentApi, 'getActiveTaskPlan').mockResolvedValue(apiResponse(basePlan));

    render(<DynamicTaskPlanPanel />);

    expect(await screen.findByText('生成一段产品演示视频')).toBeTruthy();
    expect(screen.getByText('生成分镜')).toBeTruthy();
    expect(screen.getAllByText('WAITING_HUMAN')).toHaveLength(2);
    expect(screen.getByText(/尝试 2 次/)).toBeTruthy();
    expect(screen.getByText('等待确认')).toBeTruthy();
    expect(screen.getByRole('button', { name: '批准' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '驳回' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '重试' })).toBeNull();
    expect(screen.getByRole('button', { name: '重新规划' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '取消任务' })).toBeTruthy();
  });

  it('polls the active plan every three seconds while a conversation is active', async () => {
    vi.useFakeTimers();
    const getActiveTaskPlan = vi.spyOn(agentApi, 'getActiveTaskPlan')
      .mockResolvedValueOnce(apiResponse(basePlan))
      .mockResolvedValueOnce(apiResponse({ ...basePlan, state: 'RUNNING', version: 4 }));

    render(<DynamicTaskPlanPanel />);
    await act(async () => {
      await Promise.resolve();
    });

    expect(getActiveTaskPlan).toHaveBeenCalledWith('conversation-1');
    expect(screen.getAllByText('WAITING_HUMAN')).toHaveLength(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });

    expect(getActiveTaskPlan).toHaveBeenCalledTimes(2);
    expect(screen.getByText('RUNNING')).toBeTruthy();
  });

  it('dispatches a task action and renders the returned plan state', async () => {
    vi.spyOn(agentApi, 'getActiveTaskPlan').mockResolvedValue(apiResponse(basePlan));
    const actOnTaskPlan = vi.spyOn(agentApi, 'actOnTaskPlan').mockResolvedValue(
      apiResponse({ ...basePlan, state: 'RUNNING', version: 4 }),
    );

    render(<DynamicTaskPlanPanel />);
    await screen.findByText('生成一段产品演示视频');

    fireEvent.click(screen.getByRole('button', { name: '批准' }));

    await waitFor(() => {
      expect(actOnTaskPlan).toHaveBeenCalledWith('conversation-1', 'plan-1', 'approve');
    });
    expect(await screen.findByText('RUNNING')).toBeTruthy();
  });
});
