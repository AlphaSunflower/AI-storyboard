/**
 * /chat 底部状态栏的聚合口径（纯函数，无副作用）。
 *
 * 口径（全对话累计）：
 *   轮 = 用户提问次数（与消息导航行边栏同一折叠规则）；
 *   步 = 各轮 modelCalls 之和（loop 循环步数）；
 *   用量 / 缓存命中 = 全对话累加；
 *   tok/s = 输出 token ÷ 模型推理耗时（缺失 modelDurationMs 时回退整轮墙钟耗时）。
 *
 * 上下文占用与三色明细描述「当前上下文组成」，取最近一轮，不做累加。
 */

import type { AgentMessage, AgentTurnMetrics } from '../../api/agent';
import { buildChatRailItems } from '../../lib/chatMessageRail';
import { DEFAULT_CONTEXT_WINDOW_TOKENS, contextWindowTokensFor } from './contextWindow';

/** 上下文组成三色明细（估算 token）。 */
export interface ContextBreakdown {
  systemPromptTokens: number;
  toolDefinitionTokens: number;
  messageTokens: number;
}

/** 底部状态栏展示所需的全部派生数据。 */
export interface ConversationStatus {
  /** 用户提问轮数 */
  rounds: number;
  /** 累计 loop 步数（含流式中未完成的一轮） */
  steps: number;
  /** 累计 token（输入 + 输出） */
  totalTokens: number;
  /** 未命中缓存的输入 token */
  uncachedInputTokens: number;
  /** 命中缓存读取的输入 token */
  cachedInputTokens: number;
  outputTokens: number;
  /** 缓存命中率 0..1 */
  cacheHitRatio: number;
  /** 输出速度（token/秒）；无法计算时为 0 */
  tokensPerSecond: number;
  /** 累计模型推理耗时（毫秒） */
  modelDurationMs: number;
  /** 累计工具执行耗时（毫秒） */
  toolDurationMs: number;
  /** 当前上下文窗口占用（最近一轮，后端权威值） */
  contextTokens: number;
  /** 当前上下文占用中命中缓存前缀的 input token（最近一轮，与 contextTokens 同口径） */
  contextCachedInputTokens: number;
  /** 当前模型上下文窗口大小 */
  contextWindowTokens: number;
  /** 上下文组成明细；后端未提供估算字段时为 null */
  breakdown: ContextBreakdown | null;
  /** 是否存在可展示的真实用量数据（无数据时回退免责声明） */
  hasData: boolean;
}

/** 流式进行中的实时用量（generating[conversationId]）。 */
export interface LiveUsageInput {
  metrics?: AgentTurnMetrics;
  /** turn_started 提前下发的模型窗口。 */
  contextWindowTokens?: number;
}

function nonNegative(value: number | undefined): number {
  return Number.isFinite(value) && (value ?? 0) > 0 ? (value as number) : 0;
}

function hasBreakdown(metrics: AgentTurnMetrics | undefined): boolean {
  if (!metrics) return false;
  return nonNegative(metrics.systemPromptTokens) > 0
    || nonNegative(metrics.toolDefinitionTokens) > 0
    || nonNegative(metrics.messageTokens) > 0;
}

/**
 * 聚合整个会话的用量与当前上下文组成。
 *
 * @param messages           当前会话已加载的消息
 * @param live               流式中的实时用量（可选）
 */
export function summarizeConversationStatus(
  messages: readonly AgentMessage[],
  live?: LiveUsageInput,
): ConversationStatus {
  const rounds = buildChatRailItems(messages).length;

  const completed: AgentTurnMetrics[] = [];
  for (const message of messages) {
    const metrics = message.metadata?.metrics;
    if (metrics) completed.push(metrics);
  }
  const liveMetrics = live?.metrics;

  let steps = 0;
  let totalTokens = 0;
  let inputTokens = 0;
  let cachedInputTokens = 0;
  let outputTokens = 0;
  let modelDurationMs = 0;
  let toolDurationMs = 0;
  let wallDurationMs = 0;

  const accumulate = (metrics: AgentTurnMetrics) => {
    const input = nonNegative(metrics.inputTokens);
    const output = nonNegative(metrics.outputTokens);
    steps += nonNegative(metrics.modelCalls);
    inputTokens += input;
    outputTokens += output;
    cachedInputTokens += nonNegative(metrics.cachedInputTokens);
    totalTokens += nonNegative(metrics.totalTokens) > 0
      ? nonNegative(metrics.totalTokens)
      : input + output;
    modelDurationMs += nonNegative(metrics.modelDurationMs);
    toolDurationMs += nonNegative(metrics.toolDurationMs);
    wallDurationMs += nonNegative(metrics.durationMs);
  };

  completed.forEach(accumulate);
  if (liveMetrics) accumulate(liveMetrics);

  const hasData = completed.length > 0 || !!liveMetrics;
  const cacheHitRatio = inputTokens > 0
    ? Math.min(1, cachedInputTokens / inputTokens)
    : 0;
  const speedDenominatorMs = modelDurationMs > 0 ? modelDurationMs : wallDurationMs;
  const tokensPerSecond = speedDenominatorMs > 0
    ? outputTokens / (speedDenominatorMs / 1000)
    : 0;

  // 上下文占用：取最近一轮（流式优先），与后端 contextTokens 同语义
  const baseline = [...completed].reverse().find((metrics) => nonNegative(metrics.contextTokens) > 0)
    ?? [...completed].reverse()[0];
  const liveContext = nonNegative(liveMetrics?.contextTokens);
  const baseContext = nonNegative(baseline?.contextTokens);
  // 兼容旧后端：无 contextTokens 时回退到「最近一轮输入 + 流式输入」的估算口径
  const contextTokens = Math.max(liveContext, baseContext)
    || (nonNegative(baseline?.inputTokens) + nonNegative(liveMetrics?.inputTokens));
  const contextWindowTokens = nonNegative(liveMetrics?.contextWindowTokens)
    || nonNegative(live?.contextWindowTokens)
    || nonNegative(baseline?.contextWindowTokens)
    || contextWindowTokensFor()
    || DEFAULT_CONTEXT_WINDOW_TOKENS;

  const breakdownSource = liveContext > 0 ? liveMetrics : baseline;
  const breakdown = hasBreakdown(breakdownSource)
    ? {
        systemPromptTokens: nonNegative(breakdownSource?.systemPromptTokens),
        toolDefinitionTokens: nonNegative(breakdownSource?.toolDefinitionTokens),
        messageTokens: nonNegative(breakdownSource?.messageTokens),
      }
    : null;

  return {
    rounds,
    steps,
    totalTokens,
    uncachedInputTokens: Math.max(0, inputTokens - cachedInputTokens),
    cachedInputTokens,
    outputTokens,
    cacheHitRatio,
    tokensPerSecond,
    modelDurationMs,
    toolDurationMs,
    contextTokens,
    contextCachedInputTokens: nonNegative(breakdownSource?.cachedInputTokens),
    contextWindowTokens,
    breakdown,
    hasData,
  };
}
