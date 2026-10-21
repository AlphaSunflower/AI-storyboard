// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ getStreamTicket: vi.fn() }));

vi.mock('./jobAuth', () => ({
  getStreamTicket: mocks.getStreamTicket,
  getJobToken: () => 'token',
  bootstrapJobAuth: async () => 'token',
}));

class FakeEventSource {
  static CLOSED = 2;
  static instances: FakeEventSource[] = [];
  readyState = 1;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  closed = false;
  private listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();
  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }
  addEventListener(type: string, handler: (event: MessageEvent<string>) => void) {
    const set = this.listeners.get(type) ?? new Set();
    set.add(handler);
    this.listeners.set(type, set);
  }
  removeEventListener(type: string, handler: (event: MessageEvent<string>) => void) {
    this.listeners.get(type)?.delete(handler);
  }
  close() {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }
  emit(type: string, payload: unknown, lastEventId = '') {
    const event = { data: JSON.stringify(payload), lastEventId } as MessageEvent<string>;
    this.listeners.get(type)?.forEach((handler) => handler(event));
  }
}

vi.stubGlobal('EventSource', FakeEventSource);

import { HttpJobAdapter } from './capabilities';

const flush = async () => {
  await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 0));
};

describe('任务事件流订阅', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    FakeEventSource.instances = [];
    mocks.getStreamTicket.mockResolvedValue('ticket-1');
  });

  it('收到终态帧后主动关闭连接，避免 EventSource 自动重连成风暴', async () => {
    const adapter = new HttpJobAdapter('/api/canvas');
    const listener = vi.fn();
    adapter.subscribe('job-1', listener);
    await flush();

    const source = FakeEventSource.instances[0];
    expect(source).toBeTruthy();
    expect(source.closed).toBe(false);

    source.emit('job.completed', { type: 'job.completed', jobId: 'job-1' }, 'job-1:12');

    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ type: 'job.completed', jobId: 'job-1' }));
    expect(source.closed).toBe(true);
  });

  it('连接关闭/断线不再伪造 job.failed（否则结果槽和下游会被误判失败）', async () => {
    const adapter = new HttpJobAdapter('/api/canvas');
    const listener = vi.fn();
    adapter.subscribe('job-1', listener);
    await flush();

    const source = FakeEventSource.instances[0];
    // 服务端终态后正常关闭连接时，浏览器会触发 onerror；这里不再有任何「失败」回调
    expect(source.onerror).toBeUndefined();

    // 连 ticket 都拿不到时同样不能伪造失败：只意味着没有直播事件，终态由轮询兜底
    mocks.getStreamTicket.mockResolvedValue(null);
    adapter.subscribe('job-2', listener);
    await flush();
    expect(listener).not.toHaveBeenCalled();
  });
});
