import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../api/ai', () => ({
  aiApi: {
    getTaskStatus: vi.fn(),
    generateVideo: vi.fn(),
  },
}));

import { aiApi } from '../api/ai';
import { useProjectStore } from './projectStore';

describe('projectStore video polling', () => {
  beforeEach(() => {
    useProjectStore.setState({
      currentProject: { id: 'p1', status: 'draft' } as never,
      scenes: [{ id: 's1', projectId: 'p1', sceneNumber: 1 }] as never,
      videoProgress: {},
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
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
  });
});
