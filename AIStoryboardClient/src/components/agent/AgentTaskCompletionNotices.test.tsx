import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useAgentStore } from '../../stores/agentStore';
import { AgentTaskCompletionNotices } from './AgentTaskCompletionNotices';

describe('AgentTaskCompletionNotices', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      taskNotices: {},
      sendMessage: useAgentStore.getInitialState().sendMessage,
      openTaskDrawer: useAgentStore.getInitialState().openTaskDrawer,
      focusTask: useAgentStore.getInitialState().focusTask,
      dismissTaskNotice: useAgentStore.getInitialState().dismissTaskNotice,
    });
    vi.restoreAllMocks();
  });

  it('opens the completed storyboard task drawer without starting the old review flow', async () => {
    const openTaskDrawer = vi.fn();
    const sendMessage = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      taskNotices: {
        'conversation-1': [{
          taskId: 'task-1',
          title: '剧情生成、诊断与分镜',
          conversationId: 'conversation-1',
          createdAt: new Date().toISOString(),
          storyboardRunId: '6efa645a-724d-4459-be90-35aac504e323',
        }],
      },
      openTaskDrawer,
      sendMessage,
    });

    render(<AgentTaskCompletionNotices conversationId="conversation-1" />);
    fireEvent.click(screen.getByText('任务已完成，查看结果'));

    await waitFor(() => expect(openTaskDrawer).toHaveBeenCalledWith('task-1'));
    expect(sendMessage).not.toHaveBeenCalled();
  });
});
