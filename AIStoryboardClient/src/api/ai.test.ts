import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./client', () => ({
  default: {
    post: vi.fn().mockResolvedValue({ data: {} }),
  },
}));

import client from './client';
import { aiApi } from './ai';

describe('aiApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('allows storyboard generation enough time for reference understanding and shot planning', async () => {
    await aiApi.generateScript({
      projectId: 'p1',
      scriptText: '清晨，阳光落在木质窗棂上。',
      creationType: 'movie',
      aspectRatio: '16:9',
    });

    expect(client.post).toHaveBeenCalledWith(
      '/ai/generate-script',
      expect.objectContaining({ projectId: 'p1' }),
      { timeout: 300_000 },
    );
  });
});
