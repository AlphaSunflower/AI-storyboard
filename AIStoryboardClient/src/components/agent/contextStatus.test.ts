import { describe, expect, it } from 'vitest';
import { summarizeConversationStatus } from './contextStatus';
import type { AgentMessage, AgentTurnMetrics } from '../../api/agent';

function turn(partial: Partial<AgentTurnMetrics>): AgentTurnMetrics {
  return {
    inputTokens: 0,
    outputTokens: 0,
    cachedInputTokens: 0,
    totalTokens: 0,
    durationMs: 0,
    modelCalls: 1,
    complete: true,
    ...partial,
  };
}

function message(
  id: string,
  role: 'user' | 'assistant',
  metrics?: AgentTurnMetrics,
): AgentMessage {
  return {
    id,
    conversationId: 'conversation-1',
    role,
    content: role === 'user' ? '画一个分镜' : '好的',
    createdAt: '2026-01-01T00:00:00Z',
    ...(metrics ? { metadata: { metrics } } : {}),
  };
}

describe('summarizeConversationStatus', () => {
  it('reports no data for an empty conversation so the disclaimer stays', () => {
    const status = summarizeConversationStatus([]);

    expect(status.hasData).toBe(false);
    expect(status.rounds).toBe(0);
    expect(status.steps).toBe(0);
    expect(status.breakdown).toBeNull();
  });

  it('accumulates rounds, steps, tokens and cache hit across the whole session', () => {
    const messages: AgentMessage[] = [
      message('u1', 'user'),
      message('a1', 'assistant', turn({
        inputTokens: 10_000, outputTokens: 500, cachedInputTokens: 8_000,
        totalTokens: 10_500, modelCalls: 3, durationMs: 2_000, modelDurationMs: 1_500,
        toolDurationMs: 400,
      })),
      message('u2', 'user'),
      message('a2', 'assistant', turn({
        inputTokens: 20_000, outputTokens: 1_000, cachedInputTokens: 19_000,
        totalTokens: 21_000, modelCalls: 4, durationMs: 3_000, modelDurationMs: 2_500,
        toolDurationMs: 700,
      })),
    ];

    const status = summarizeConversationStatus(messages);

    expect(status.rounds).toBe(2);
    expect(status.steps).toBe(7);
    expect(status.totalTokens).toBe(31_500);
    expect(status.uncachedInputTokens).toBe(3_000);
    expect(status.cachedInputTokens).toBe(27_000);
    expect(status.outputTokens).toBe(1_500);
    expect(status.cacheHitRatio).toBeCloseTo(27_000 / 30_000, 5);
    expect(status.modelDurationMs).toBe(4_000);
    expect(status.toolDurationMs).toBe(1_100);
    // 输出速度用模型推理耗时：1500 / 4s = 375 tok/s
    expect(status.tokensPerSecond).toBeCloseTo(375, 5);
  });

  it('falls back to wall-clock duration for speed when model timing is missing', () => {
    const messages: AgentMessage[] = [
      message('u1', 'user'),
      message('a1', 'assistant', turn({
        outputTokens: 600, durationMs: 3_000,
      })),
    ];

    const status = summarizeConversationStatus(messages);

    expect(status.modelDurationMs).toBe(0);
    expect(status.tokensPerSecond).toBeCloseTo(200, 5);
  });

  it('includes the live streaming turn in the cumulative totals', () => {
    const messages: AgentMessage[] = [
      message('u1', 'user'),
      message('a1', 'assistant', turn({ inputTokens: 1_000, outputTokens: 100, modelCalls: 2 })),
    ];
    const live = { metrics: turn({ inputTokens: 2_000, outputTokens: 300, modelCalls: 5 }) };

    const status = summarizeConversationStatus(messages, live);

    expect(status.steps).toBe(7);
    expect(status.outputTokens).toBe(400);
  });

  it('derives the context breakdown from the latest turn with context data', () => {
    const messages: AgentMessage[] = [
      message('u1', 'user'),
      message('a1', 'assistant', turn({
        contextTokens: 120_000, contextWindowTokens: 1_000_000,
        systemPromptTokens: 1_500, toolDefinitionTokens: 5_600, messageTokens: 112_900,
      })),
    ];

    const status = summarizeConversationStatus(messages);

    expect(status.contextTokens).toBe(120_000);
    expect(status.contextWindowTokens).toBe(1_000_000);
    expect(status.contextCachedInputTokens).toBe(0);
    expect(status.breakdown).toEqual({
      systemPromptTokens: 1_500,
      toolDefinitionTokens: 5_600,
      messageTokens: 112_900,
    });
  });

  it('returns a null breakdown when the backend omits the estimate fields', () => {
    const messages: AgentMessage[] = [
      message('u1', 'user'),
      message('a1', 'assistant', turn({ contextTokens: 90_000 })),
    ];

    expect(summarizeConversationStatus(messages).breakdown).toBeNull();
  });

  it('falls back to accumulated input tokens when contextTokens is missing', () => {
    const messages: AgentMessage[] = [
      message('u1', 'user'),
      message('a1', 'assistant', turn({ inputTokens: 64_000, outputTokens: 1_000 })),
    ];

    const status = summarizeConversationStatus(messages);

    expect(status.contextTokens).toBe(64_000);
    // 未知模型回退默认窗口 128k → 50%
    expect(status.contextWindowTokens).toBe(128_000);
  });
});
