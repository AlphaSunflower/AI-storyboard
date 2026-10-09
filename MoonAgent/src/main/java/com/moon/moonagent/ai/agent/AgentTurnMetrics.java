package com.moon.moonagent.ai.agent;

/**
 * 一次后端执行段的模型用量与耗时。
 *
 * @param inputTokens        所有模型调用的输入 token 合计
 * @param outputTokens       所有模型调用的输出 token 合计
 * @param cachedInputTokens  输入 token 中命中缓存的合计（不重复计入 totalTokens）
 * @param totalTokens        inputTokens + outputTokens
 * @param contextTokens      本轮末次模型调用的 input token，代表当前上下文窗口占用
 * @param contextWindowTokens 本轮所用模型的上下文窗口大小
 * @param durationMs         后端收到请求到终态事件的墙钟耗时
 * @param modelCalls         本轮发起的模型调用次数
 * @param complete           true 表示所有模型调用都返回了 usage
 */
public record AgentTurnMetrics(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long totalTokens,
        long contextTokens,
        long contextWindowTokens,
        long durationMs,
        int modelCalls,
        boolean complete
) {
}
