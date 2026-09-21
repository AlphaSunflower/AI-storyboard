import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamChat } from './agent';

describe('streamChat', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sends group and snapshot ids as separate fields', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('', { status: 200 }),
    );

    await streamChat(
      'conversation-1',
      '生成视频',
      [],
      () => undefined,
      [],
      ['group-1'],
      undefined,
      { assetGroupSnapshotIds: ['snapshot-1'] },
    );

    const body = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(body.assetGroupIds).toEqual(['group-1']);
    expect(body.assetGroupSnapshotIds).toEqual(['snapshot-1']);
  });
});
