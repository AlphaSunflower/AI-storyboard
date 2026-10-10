import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AgentAsset, AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
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
  liveText: '',
  liveThinking: '',
  liveStage: '',
  canCancel: false,
  canRetry: false,
  canFollowUp: false,
};

describe('AgentTaskDrawer', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      tasks: {}, taskDrawerId: null, taskStream: {}, taskThinking: {}, taskStreamSeq: {},
    });
  });

  it('labels batch image slots with the target storyboard scene', () => {
    useAgentStore.setState({
      tasks: { [task.taskId]: task },
      taskDrawerId: task.taskId,
    });

    render(<AgentTaskDrawer />);

    expect(screen.getByText(/分镜1/)).toBeTruthy();
  });

  it('streams live task output while the task is running', () => {
    useAgentStore.setState({
      tasks: { [task.taskId]: { ...task, status: 'running', stage: 'script_optimization', progress: 5 } },
      taskDrawerId: task.taskId,
      taskStream: { [task.taskId]: '# 剧情稿\n\n正在优化脚本' },
    });

    render(<AgentTaskDrawer />);

    expect(screen.getByText('实际输出')).toBeTruthy();
    expect(screen.getByText(/正在优化脚本/)).toBeTruthy();
  });

  it('restores the persisted live tail after reload', () => {
    useAgentStore.setState({
      tasks: {
        [task.taskId]: {
          ...task,
          liveText: '# 剧情稿\n\n刷新后恢复的输出',
          liveThinking: '刷新后恢复的思考链',
        },
      },
      taskDrawerId: task.taskId,
      taskStream: {},
      taskThinking: {},
    });

    render(<AgentTaskDrawer />);

    expect(screen.getByText(/刷新后恢复的输出/)).toBeTruthy();
    expect(screen.getByText('刷新后恢复的思考链')).toBeTruthy();
  });

  it('shows the thinking chain with a show/hide toggle', () => {
    useAgentStore.setState({
      tasks: { [task.taskId]: { ...task, status: 'running' } },
      taskDrawerId: task.taskId,
      taskThinking: { [task.taskId]: '先判断这是分镜需求。' },
    });

    render(<AgentTaskDrawer />);

    expect(screen.getByText('思考链')).toBeTruthy();
    expect(screen.getByText('先判断这是分镜需求。')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /思考链/ }));
    expect(screen.queryByText('先判断这是分镜需求。')).toBeNull();
  });

  it('opens and downloads documents returned by a completed task', () => {
    const documentAsset: AgentAsset = {
      id: 'document-1',
      conversationId: 'conversation-1',
      type: 'document',
      url: '/api/files/reports/quarterly.md',
      prompt: null,
      model: null,
      status: 'completed',
      taskId: task.taskId,
      error: null,
      createdAt: new Date().toISOString(),
      resultUrls: '["/api/files/reports/quarterly.md"]',
    };
    useAgentStore.setState({
      tasks: {
        [task.taskId]: {
          ...task,
          assetIds: [documentAsset.id],
          resultUrls: [documentAsset.url],
          result: '季度报告已生成。',
        },
      },
      assets: {
        records: [documentAsset],
        total: 1,
        documentTotal: 1,
        page: 1,
        size: 20,
      },
      taskDrawerId: task.taskId,
    });

    render(<AgentTaskDrawer />);

    fireEvent.click(screen.getByRole('button', { name: /打开 quarterly.md/ }));
    expect(useDocumentPreviewStore.getState().attachment).toMatchObject({
      url: documentAsset.url,
      name: 'quarterly.md',
      mimeType: 'text/markdown',
      kind: 'file',
      assetId: documentAsset.id,
    });
    expect(screen.getByRole('link', { name: /下载 quarterly.md/ })).toBeTruthy();
  });
});
