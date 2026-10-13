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
 * @param modelDurationMs    所有模型调用的推理耗时合计（不含工具执行）
 * @param toolDurationMs     所有工具执行的耗时合计（ToolResult 开始到结束）
 * @param systemPromptTokens 系统提示词估算 token（描述「当前上下文组成」，非累加值）
 * @param toolDefinitionTokens 工具定义估算 token（描述「当前上下文组成」，非累加值）
 * @param messageTokens      对话消息估算 token = contextTokens − 系统提示词 − 工具定义
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
        boolean complete,
        long modelDurationMs,
        long toolDurationMs,
        long systemPromptTokens,
        long toolDefinitionTokens,
        long messageTokens
) {
}
