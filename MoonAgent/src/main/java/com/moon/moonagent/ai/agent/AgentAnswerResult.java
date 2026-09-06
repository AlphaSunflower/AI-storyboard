package com.moon.moonagent.ai.agent;

/**
 * 流式回答结果：正文 + 推理摘要（reasoning）。
 *
 * @param content    完整正文
 * @param reasoning  推理摘要快照（模型未返回 reasoning 时为 trace 摘要）
 * @param source     来源（模型名 / trace 摘要）
 */
public record AgentAnswerResult(
    String content,
    AgentReasoningSnapshot reasoning,
    String source
) {
    public static AgentAnswerResult of(String content, AgentReasoningSnapshot reasoning, String source) {
        return new AgentAnswerResult(content == null ? "" : content, reasoning,
                source == null ? (reasoning == null ? null : reasoning.source()) : source);
    }
}
