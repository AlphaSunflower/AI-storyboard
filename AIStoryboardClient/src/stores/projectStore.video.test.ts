import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../api/ai', () => ({
  aiApi: {
    getTaskStatus: vi.fn(),
    generateVideo: vi.fn(),
  },
}));

vi.mock('../api/projects', () => ({
  projectApi: {
    update: vi.fn().mockResolvedValue({ data: { data: {} } }),
    get: vi.fn().mockResolvedValue({
      data: { data: { id: 'p1', name: '测试项目', status: 'active', scenes: [{ id: 's1', projectId: 'p1', sceneNumber: 1 }] } },
    }),
  },
}));

import { aiApi } from '../api/ai';
import { useProjectStore } from './projectStore';
import { useToastStore } from './toastStore';

describe('projectStore video polling', () => {
  beforeEach(() => {
    useToastStore.setState({ toasts: [] });
    useProjectStore.setState({
      currentProject: { id: 'p1', status: 'draft' } as never,
      scenes: [{ id: 's1', projectId: 'p1', sceneNumber: 1 }] as never,
      videoProgress: {},
      unreadScenes: new Set<string>(),
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    useToastStore.setState({ toasts: [] });
  });

  it('surfaces upstream video moderation failures with a readable reason', async () => {
    vi.mocked(aiApi.getTaskStatus).mockResolvedValue({
      data: {
        data: {
          taskId: 'task-1',
          status: 'failed',
          error: 'output new_sensitive',
        },
      },
    } as never);

    await expect(useProjectStore.getState().pollVideoTask('s1', 'task-1'))
      .rejects.toThrow('视频生成被内容审核拒绝');
    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '分镜 1 生视频失败',
      description: '视频生成被内容审核拒绝（output new_sensitive）。请修改提示词或更换参考素材后重试。',
      variant: 'error',
    });
    expect(useProjectStore.getState().unreadScenes.has('s1')).toBe(true);
  });

  it('confirms a finished video and still marks the scene unread', async () => {
    vi.mocked(aiApi.getTaskStatus).mockResolvedValue({
      data: { data: { taskId: 'task-2', status: 'completed', videoUrl: '/videos/s1.mp4' } },
    } as never);

    await useProjectStore.getState().pollVideoTask('s1', 'task-2');

    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '分镜 1 生视频成功',
      variant: 'success',
    });
    expect(useProjectStore.getState().unreadScenes.has('s1')).toBe(true);
  });
});
