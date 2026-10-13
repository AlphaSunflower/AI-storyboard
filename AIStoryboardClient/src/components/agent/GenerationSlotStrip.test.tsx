import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { isMediaTask, selectMediaTaskKey } from '../../lib/mediaTaskSlots';
import { GenerationSlotStrip } from './GenerationSlotStrip';

function task(overrides: Partial<AgentTaskView>): AgentTaskView {
  return {
    taskId: 'task-1',
    conversationId: 'conversation-1',
    projectId: 'project-1',
    title: '生成',
    toolName: 'image',
    kind: 'image-batch',
    status: 'running',
    stage: '',
    progress: 50,
    resultSummary: '',
    error: '',
    parentTaskId: '',
    anchorMessageId: '',
    version: 1,
    createdAt: '',
    updatedAt: '',
    assetIds: [],
    resultUrls: [],
    slots: [],
    result: '',
    liveText: '',
    liveThinking: '',
    liveStage: '',
    canCancel: true,
    canRetry: false,
    canFollowUp: false,
    ...overrides,
  } as AgentTaskView;
}

describe('GenerationSlotStrip', () => {
  beforeEach(() => {
    useAgentStore.setState({ tasks: {} });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useAgentStore.setState({ tasks: {} });
  });

  it('lays out one slot per image with placeholder, progress and result in place', () => {
    useAgentStore.setState({
      tasks: {
        'task-1': task({
          progress: 66,
          slots: [
            { index: 0, label: '分镜 1', status: 'completed', prompt: '海', url: '/api/files/images/a.png', assetId: 'a', error: '' },
            { index: 1, label: '分镜 2', status: 'running', prompt: '山', url: '', assetId: '', error: '' },
            { index: 2, label: '分镜 3', status: 'pending', prompt: '云', url: '', assetId: '', error: '' },
            { index: 3, label: '分镜 4', status: 'failed', prompt: '雨', url: '', assetId: '', error: '超时' },
          ],
        }),
      },
    });

    const { container } = render(<GenerationSlotStrip taskIds={['task-1']} />);

    expect(container.querySelectorAll('.gen-slot')).toHaveLength(4);
    expect(container.querySelectorAll('.gen-slot__media img')).toHaveLength(1);
    expect(container.querySelector('[data-status="generating"]')).toBeTruthy();
    expect(container.querySelector('[data-status="queued"]')).toBeTruthy();
    expect(container.querySelector('[data-status="error"]')).toBeTruthy();
  });

  it('offers a retry on a failed slot when the task can be retried', () => {
    const retryTask = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      retryTask: retryTask as never,
      tasks: {
        'task-1': task({
          canRetry: true,
          slots: [
            { index: 0, label: '分镜 1', status: 'failed', prompt: '海', url: '', assetId: '', error: '超时' },
          ],
        }),
      },
    });

    render(<GenerationSlotStrip taskIds={['task-1']} />);
    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(retryTask).toHaveBeenCalledWith('task-1');
  });

  it('renders a single wide slot for a video task', () => {
    useAgentStore.setState({
      tasks: {
        'task-2': task({
          taskId: 'task-2',
          kind: 'video',
          status: 'completed',
          resultUrls: ['/api/files/videos/clip.mp4'],
        }),
      },
    });

    const { container } = render(<GenerationSlotStrip taskIds={['task-2']} />);

    expect(container.querySelectorAll('.gen-slot')).toHaveLength(1);
    expect(container.querySelector('.gen-slot__media video')).toBeTruthy();
    expect(container.querySelector('.refine-frame')?.getAttribute('data-status')).toBe('complete');
  });

  it('renders nothing without media tasks, and keeps the generation center entry when asked', () => {
    const onOpen = vi.fn();
    const { container: empty } = render(<GenerationSlotStrip taskIds={[]} onOpen={onOpen} />);
    expect(empty.querySelector('.gen-slot-strip')).toBeNull();

    useAgentStore.setState({
      tasks: {
        'task-3': task({
          taskId: 'task-3',
          slots: [{ index: 0, label: '分镜 1', status: 'pending', prompt: '海', url: '', assetId: '', error: '' }],
        }),
      },
    });
    const { container } = render(<GenerationSlotStrip taskIds={['task-3']} onOpen={onOpen} />);
    fireEvent.click(screen.getByRole('button', { name: '查看全部' }));

    expect(container.querySelector('.gen-slot-strip')).toBeTruthy();
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('treats only tasks that own media as preset-slot carriers', () => {
    const tasks = {
      'task-1': task({}),
      'task-2': task({ taskId: 'task-2', kind: 'storyboard', slots: [] }),
      'task-3': task({ taskId: 'task-3', kind: 'video', slots: [] }),
    };

    expect(isMediaTask(tasks['task-1'])).toBe(true);
    expect(isMediaTask(tasks['task-2'])).toBe(false);
    expect(isMediaTask(tasks['task-3'])).toBe(true);
    expect(selectMediaTaskKey(tasks, [
      { taskId: 'task-1', title: '图片', kind: 'image-batch' },
      { taskId: 'task-2', title: '分镜', kind: 'storyboard' },
    ])).toBe('task-1');
    expect(selectMediaTaskKey(tasks, undefined)).toBe('');
  });
});
