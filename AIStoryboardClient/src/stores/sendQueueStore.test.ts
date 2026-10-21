import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { MAX_QUEUED_SENDS, useSendQueueStore } from './sendQueueStore';

const enqueue = (content: string, conversationId = 'c1') =>
  useSendQueueStore.getState().enqueue({ conversationId, content });

describe('sendQueueStore', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    useSendQueueStore.setState({ queues: {}, holds: {} });
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

  it('takes one queued message by id for immediate sending', () => {
    enqueue('A');
    enqueue('B');
    const b = useSendQueueStore.getState().queues.c1[1];

    const taken = useSendQueueStore.getState().take('c1', b.id);
    expect(taken?.content).toBe('B');
    expect(useSendQueueStore.getState().queues.c1.map((item) => item.content)).toEqual(['A']);
    expect(useSendQueueStore.getState().take('c1', b.id)).toBeUndefined();
  });

  it('restores an immediately-sent message at its original position', () => {
    enqueue('A');
    enqueue('B');
    enqueue('C');
    const b = useSendQueueStore.getState().queues.c1[1];
    const taken = useSendQueueStore.getState().take('c1', b.id);
    expect(taken).toBeTruthy();

    useSendQueueStore.getState().restore(taken!, 1);
    expect(useSendQueueStore.getState().queues.c1.map((item) => item.content))
      .toEqual(['A', 'B', 'C']);
  });

  it('edits queued content in place and keeps the attachment snapshot', () => {
    useSendQueueStore.getState().enqueue({
      conversationId: 'c1',
      content: '旧正文',
      picUrls: ['/api/files/a.png'],
      attachments: [{ url: '/api/files/a.png', name: 'a.png', kind: 'image', mimeType: 'image/png' }],
    });
    const item = useSendQueueStore.getState().queues.c1[0];

    expect(useSendQueueStore.getState().update('c1', item.id, ' 新正文 ')).toBe(true);
    const updated = useSendQueueStore.getState().queues.c1[0];
    expect(updated.content).toBe('新正文');
    expect(updated.opts?.attachments).toHaveLength(1);
    expect(updated.opts?.picUrls).toEqual(['/api/files/a.png']);
    expect(updated.editedAt).toBeGreaterThan(0);
    expect(window.sessionStorage.getItem('moon.send-queue.v1')).toContain('新正文');

    expect(useSendQueueStore.getState().update('c1', item.id, '   ')).toBe(false);
    expect(useSendQueueStore.getState().update('c1', 'missing', '任意')).toBe(false);
  });

  it('holds automatic flushing until every holder releases', () => {
    expect(useSendQueueStore.getState().holds.c1).toBeUndefined();
    useSendQueueStore.getState().holdQueue('c1');
    useSendQueueStore.getState().holdQueue('c1');
    expect(useSendQueueStore.getState().holds.c1).toBe(2);

    useSendQueueStore.getState().releaseQueue('c1');
    expect(useSendQueueStore.getState().holds.c1).toBe(1);

    useSendQueueStore.getState().releaseQueue('c1');
    expect(useSendQueueStore.getState().holds.c1).toBeUndefined();

    // 多余的 release 不能把计数压成负数
    useSendQueueStore.getState().releaseQueue('c1');
    expect(useSendQueueStore.getState().holds.c1).toBeUndefined();
  });
});
