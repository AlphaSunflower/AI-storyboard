import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { AgentTaskDrawer } from './AgentTaskDrawer';

const task: AgentTaskView = {
  taskId: 'task-1',
  conversationId: 'conversation-1',
  projectId: 'project-1',
  title: '批量生成分镜图片',
  toolName: 'generate_image_batch',
  kind: 'image-batch',
  status: 'completed',
  stage: 'completed',
  progress: 100,
  resultSummary: '已生成 1 张图片',
  error: '',
  parentTaskId: '',
  anchorMessageId: 'message-1',
  version: 2,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  assetIds: ['asset-1'],
  resultUrls: ['/one.png'],
  slots: [{
    index: 0,
    label: '分镜1',
    status: 'completed',
    prompt: '第一镜图片提示词',
    url: '/one.png',
    assetId: 'asset-1',
    error: '',
    completedAt: new Date().toISOString(),
  }],
  result: '![生成图片](/one.png)',
  canCancel: false,
  canRetry: false,
  canFollowUp: false,
};

describe('AgentTaskDrawer', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({ tasks: {}, taskDrawerId: null });
  });

  it('labels batch image slots with the target storyboard scene', () => {
    useAgentStore.setState({
      tasks: { [task.taskId]: task },
      taskDrawerId: task.taskId,
    });

    render(<AgentTaskDrawer />);

    expect(screen.getByText(/分镜1/)).toBeTruthy();
  });
});
