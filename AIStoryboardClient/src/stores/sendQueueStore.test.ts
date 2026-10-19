import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { MAX_QUEUED_SENDS, useSendQueueStore } from './sendQueueStore';

const enqueue = (content: string, conversationId = 'c1') =>
  useSendQueueStore.getState().enqueue({ conversationId, content });

describe('sendQueueStore', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    useSendQueueStore.setState({ queues: {} });
  });

  afterEach(() => {
    window.sessionStorage.clear();
  });

  it('keeps FIFO order per conversation and persists the queue', () => {
    expect(enqueue('第一条')).toBe(true);
    expect(enqueue('第二条')).toBe(true);

    const items = useSendQueueStore.getState().queues.c1;
    expect(items.map((item) => item.content)).toEqual(['第一条', '第二条']);
    expect(window.sessionStorage.getItem('moon.send-queue.v1')).toContain('第一条');

    const first = useSendQueueStore.getState().shift('c1');
    expect(first?.content).toBe('第一条');
    expect(useSendQueueStore.getState().queues.c1.map((item) => item.content)).toEqual(['第二条']);
  });

  it('supports promote, reorder and remove from the queue UI', () => {
    enqueue('A');
    enqueue('B');
    enqueue('C');
    const [a, b, c] = useSendQueueStore.getState().queues.c1;

    useSendQueueStore.getState().promote('c1', c.id);
    expect(useSendQueueStore.getState().queues.c1.map((item) => item.content)).toEqual(['C', 'A', 'B']);

    useSendQueueStore.getState().move('c1', a.id, 1);
    expect(useSendQueueStore.getState().queues.c1.map((item) => item.content)).toEqual(['C', 'B', 'A']);

    useSendQueueStore.getState().remove('c1', b.id);
    expect(useSendQueueStore.getState().queues.c1.map((item) => item.content)).toEqual(['C', 'A']);
  });

  it('rejects new messages after the queue reaches the cap', () => {
    for (let i = 0; i < MAX_QUEUED_SENDS; i += 1) {
      expect(enqueue(`消息 ${i}`)).toBe(true);
    }
    expect(enqueue('超出的消息')).toBe(false);
    expect(useSendQueueStore.getState().queues.c1).toHaveLength(MAX_QUEUED_SENDS);
  });
});
