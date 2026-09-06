package com.moon.moonagent.ai.agent;

/**
 * 一轮编排的推理摘要快照（用于 SSE reasoning_delta / reasoning 事件与 metadata 落库）。
 *
 * @param summary    推理摘要（短文本）
 * @param details    推理明细（截断至 20000 字符）
 * @param durationMs 耗时（毫秒，可空）
 * @param source     来源（LLM 模型名 / trace 摘要 / other 等）
 */
public record AgentReasoningSnapshot(
    String summary,
    String details,
    Long durationMs,
    String source
) {
    private static final int MAX_DETAILS = 20000;

    public AgentReasoningSnapshot {
        if (details != null && details.length() > MAX_DETAILS) {
            details = details.substring(0, MAX_DETAILS);
        }
    }
}
