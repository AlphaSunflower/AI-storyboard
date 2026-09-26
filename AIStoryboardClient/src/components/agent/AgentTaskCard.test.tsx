import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { AgentTaskCard } from './AgentTaskCard';

const storyboardTask: AgentTaskView = {
  taskId: 'task-1',
  conversationId: 'conversation-1',
  projectId: 'project-1',
  title: '剧情生成、诊断与分镜',
  toolName: 'start_storyboard_production',
  kind: 'long-tool',
  status: 'completed',
  stage: 'completed',
  progress: 100,
  resultSummary: '分镜已完成',
  result: '- run_id: `6efa645a-724d-4459-be90-35aac504e323`',
  error: '',
  parentTaskId: '',
  anchorMessageId: 'message-1',
  version: 2,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  assetIds: [],
  resultUrls: [],
  slots: [],
  canCancel: false,
  canRetry: false,
  canFollowUp: false,
};

describe('AgentTaskCard', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      tasks: {},
      sendMessage: useAgentStore.getInitialState().sendMessage,
      openTaskDrawer: useAgentStore.getInitialState().openTaskDrawer,
    });
    vi.restoreAllMocks();
  });

  it('opens the task drawer instead of routing a completed storyboard into review', () => {
    const openTaskDrawer = vi.fn();
    const sendMessage = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      tasks: { [storyboardTask.taskId]: storyboardTask },
      openTaskDrawer,
      sendMessage,
    });

    render(<AgentTaskCard taskId={storyboardTask.taskId} />);
    fireEvent.click(screen.getByText(storyboardTask.title).closest('button')!);

    expect(openTaskDrawer).toHaveBeenCalledWith(storyboardTask.taskId);
    expect(sendMessage).not.toHaveBeenCalled();
  });
});
