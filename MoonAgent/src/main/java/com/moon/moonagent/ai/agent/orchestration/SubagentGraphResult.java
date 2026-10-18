package com.moon.moonagent.ai.agent.orchestration;

import java.util.Map;

/**
 * 整张任务图的执行汇总。父级只拿这个对象，不拿子智能体的完整对话。
 */
public record SubagentGraphResult(
        String task,
        Map<String, SubagentTaskResult> results,
        boolean success,
        int tokensUsed,
        long durationMs,
        boolean budgetExceeded) {

    public SubagentGraphResult {
        results = results == null ? Map.of() : Map.copyOf(results);
    }

    public SubagentTaskResult result(String taskId) {
        return results.get(taskId);
    }
}
