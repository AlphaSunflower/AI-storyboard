import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentTurnEventEnvelope } from '../api/agent';
import {
  RESTORE_REPLAY_BATCH,
  TURN_RESUME_CURSOR_KEY,
  TURN_RESUME_CURSOR_LIMIT,
  TURN_RESUME_CURSOR_TTL_MS,
  createTurnResumeStream,
  forgetTurnLastSeq,
  readTurnLastSeq,
  readTurnResumeCursors,
  rememberTurnLastSeq,
} from './turnResume';

function envelope(seq: number, turnId = 'turn-1'): AgentTurnEventEnvelope {
  return { turnId, seq, type: 'agent_event', payload: { seq } };
}

describe('turnResume 续传游标（sessionStorage）', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    window.sessionStorage.clear();
  });

  it('记录 / 读取 / 清理单个 turn 的已渲染序号', () => {
    rememberTurnLastSeq('conversation-1', 'turn-1', 7);

    expect(readTurnLastSeq('conversation-1', 'turn-1')).toBe(7);
    // 不同 turn / 不同会话互不影响
    expect(readTurnLastSeq('conversation-1', 'turn-2')).toBe(0);
    expect(readTurnLastSeq('conversation-2', 'turn-1')).toBe(0);

    rememberTurnLastSeq('conversation-1', 'turn-1', 9);
    expect(readTurnLastSeq('conversation-1', 'turn-1')).toBe(9);

    forgetTurnLastSeq('conversation-1', 'turn-1');
    expect(readTurnLastSeq('conversation-1', 'turn-1')).toBe(0);
  });

  it('非法入参不写入（0 / 负数 / 非数字）', () => {
    rememberTurnLastSeq('conversation-1', 'turn-1', 0);
    rememberTurnLastSeq('conversation-1', 'turn-1', -3);
    rememberTurnLastSeq('conversation-1', 'turn-1', Number.NaN);
    rememberTurnLastSeq('', 'turn-1', 5);
    rememberTurnLastSeq('conversation-1', '', 5);

    expect(readTurnResumeCursors()).toHaveLength(0);
  });

  it('过期游标不返回（TTL 之后视为失效）', () => {
    window.sessionStorage.setItem(TURN_RESUME_CURSOR_KEY, JSON.stringify([{
      conversationId: 'conversation-1',
      turnId: 'turn-1',
      seq: 12,
      updatedAt: Date.now() - TURN_RESUME_CURSOR_TTL_MS - 1,
    }]));

    expect(readTurnLastSeq('conversation-1', 'turn-1')).toBe(0);
  });

  it('游标条数有上限，只保留最近的若干条', () => {
    for (let index = 0; index < TURN_RESUME_CURSOR_LIMIT + 5; index += 1) {
      rememberTurnLastSeq('conversation-1', `turn-${index}`, index + 1);
    }

    const cursors = readTurnResumeCursors();
    expect(cursors.length).toBe(TURN_RESUME_CURSOR_LIMIT);
    // 最早写入的被淘汰，最新的仍在
    expect(readTurnLastSeq('conversation-1', 'turn-4')).toBe(0);
    expect(readTurnLastSeq('conversation-1', 'turn-5')).toBe(6);
    expect(readTurnLastSeq('conversation-1', `turn-${TURN_RESUME_CURSOR_LIMIT + 4}`))
      .toBe(TURN_RESUME_CURSOR_LIMIT + 5);
  });

  it('存储内容损坏时不抛错（按无游标处理）', () => {
    window.sessionStorage.setItem(TURN_RESUME_CURSOR_KEY, '{ not json');
    expect(readTurnResumeCursors()).toEqual([]);
    expect(readTurnLastSeq('conversation-1', 'turn-1')).toBe(0);

    window.sessionStorage.setItem(TURN_RESUME_CURSOR_KEY, JSON.stringify([
      { conversationId: 'conversation-1' },
      { conversationId: 'conversation-1', turnId: 'turn-1', seq: 'x', updatedAt: 1 },
    ]));
    expect(readTurnResumeCursors()).toEqual([]);
  });
});

describe('turnResume 直播通道', () => {
  const options = (
    subscribe: Parameters<typeof createTurnResumeStream>[0]['subscribe'],
  ) => ({ conversationId: 'conversation-1', turnId: 'turn-1', afterSeq: 5, subscribe });

  it('用历史末序号建连，事件按批取走，正常结束进入 finished', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const calls: Array<{ afterSeq: number; signal: AbortSignal }> = [];
    const stream = createTurnResumeStream(options(async (_c, _t, afterSeq, onEvent, signal) => {
      calls.push({ afterSeq, signal });
      onEvent(envelope(6));
      onEvent(envelope(7));
      await gate;
    }));

    expect(calls[0]?.afterSeq).toBe(5);
    expect(calls[0]?.signal.aborted).toBe(false);
    expect(stream.state()).toBe('streaming');
    expect(stream.take().map((item) => item.seq)).toEqual([6, 7]);
    expect(stream.take()).toEqual([]);

    release();
    await vi.waitFor(() => expect(stream.state()).toBe('finished'));
  });

  it('连接失败暴露成 failed，不向调用方抛错', async () => {
    const stream = createTurnResumeStream(options(async () => {
      throw new Error('404');
    }));

    await vi.waitFor(() => expect(stream.state()).toBe('failed'));
    expect(stream.take()).toEqual([]);
  });

  it('waitForEvents 在有新事件或状态变化时被唤醒', async () => {
    let push!: (value: AgentTurnEventEnvelope) => void;
    let finish!: () => void;
    const done = new Promise<void>((resolve) => { finish = resolve; });
    const stream = createTurnResumeStream(options(async (_c, _t, _afterSeq, onEvent) => {
      push = onEvent;
      await done;
    }));

    const waiting = stream.waitForEvents();
    push(envelope(6));
    await waiting;
    expect(stream.take().map((item) => item.seq)).toEqual([6]);

    const waitingForEnd = stream.waitForEvents();
    finish();
    await waitingForEnd;
    await vi.waitFor(() => expect(stream.state()).toBe('finished'));
  });

  it('markFailed 只把 streaming 降级为 failed，stop 之后不会再变', async () => {
    const stream = createTurnResumeStream(options(async () => {
      await new Promise<void>(() => { /* 永不结束，模拟长连接 */ });
    }));

    stream.markFailed();
    expect(stream.state()).toBe('failed');

    stream.stop();
    expect(stream.state()).toBe('failed');
  });

  it('stop 中止连接并把 streaming 收尾为 finished', async () => {
    let aborted = false;
    const stream = createTurnResumeStream(options((_c, _t, _afterSeq, _onEvent, signal) => (
      new Promise<void>((_resolve, reject) => {
        signal.addEventListener('abort', () => {
          aborted = true;
          reject(new DOMException('aborted', 'AbortError'));
        });
      })
    )));

    stream.stop();

    await vi.waitFor(() => expect(aborted).toBe(true));
    expect(stream.state()).toBe('finished');
  });

  it('take 每批不超过 RESTORE_REPLAY_BATCH', () => {
    let push!: (value: AgentTurnEventEnvelope) => void;
    const stream = createTurnResumeStream(options(async (_c, _t, _afterSeq, onEvent) => {
      push = onEvent;
      await new Promise<void>(() => { /* 保持连接 */ });
    }));

    for (let seq = 1; seq <= RESTORE_REPLAY_BATCH + 3; seq += 1) push(envelope(seq));

    expect(stream.take()).toHaveLength(RESTORE_REPLAY_BATCH);
    expect(stream.take()).toHaveLength(3);
  });
});
