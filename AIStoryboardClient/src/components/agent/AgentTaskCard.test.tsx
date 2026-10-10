import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentAsset, type AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
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
  liveText: '',
  liveThinking: '',
  liveStage: '',
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

  it('hides the plot approval button after a successful confirmation', async () => {
    const plotReviewTask: AgentTaskView = {
      ...storyboardTask,
      status: 'completed',
      stage: 'waiting_review',
    };
    const approve = vi.spyOn(agentApi, 'approveStoryboard')
      .mockResolvedValue({ data: { data: { taskId: 'task-2', stage: 'PLOT_REVIEW' } } } as never);
    const openTaskDrawer = vi.fn();
    useAgentStore.setState({
      tasks: { [plotReviewTask.taskId]: plotReviewTask },
      openTaskDrawer,
      sendMessage: vi.fn(),
    });

    render(<AgentTaskCard taskId={plotReviewTask.taskId} />);
    fireEvent.click(screen.getByRole('button', { name: '确认剧情' }));

    await waitFor(() => {
      expect(approve).toHaveBeenCalledWith('6efa645a-724d-4459-be90-35aac504e323', {
        stage: 'PLOT_REVIEW',
        decision: 'APPROVE',
      });
    });
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '确认剧情' })).toBeNull();
    });
  });

  it('offers open and download actions for document task results', () => {
    const documentAsset: AgentAsset = {
      id: 'document-1',
      conversationId: 'conversation-1',
      type: 'document',
      url: '/api/files/reports/summary.md',
      prompt: null,
      model: null,
      status: 'completed',
      taskId: storyboardTask.taskId,
      error: null,
      createdAt: new Date().toISOString(),
      resultUrls: '["/api/files/reports/summary.md"]',
    };
    useAgentStore.setState({
      tasks: {
        [storyboardTask.taskId]: {
          ...storyboardTask,
          assetIds: [documentAsset.id],
          resultUrls: [documentAsset.url],
        },
      },
      assets: {
        records: [documentAsset],
        total: 1,
        documentTotal: 1,
        page: 1,
        size: 20,
      },
    });

    render(<AgentTaskCard taskId={storyboardTask.taskId} />);

    fireEvent.click(screen.getByRole('button', { name: /打开 summary.md/ }));
    expect(useDocumentPreviewStore.getState().attachment?.url).toBe(documentAsset.url);
    expect(screen.getByRole('link', { name: /下载 summary.md/ })).toBeTruthy();
  });
});
