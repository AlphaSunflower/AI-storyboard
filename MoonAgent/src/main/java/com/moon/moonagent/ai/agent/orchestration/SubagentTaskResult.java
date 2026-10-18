package com.moon.moonagent.ai.agent.orchestration;

/**
 * 单个子智能体节点的执行结果。
 *
 * @param output     结构化/文本结果；父级只读它，不读子对话全文
 * @param error      失败原因；成功时为 null
 * @param durationMs 执行耗时
 * @param tokens     估算 token 消耗
 */
public record SubagentTaskResult(
        String taskId,
        SubagentTaskStatus status,
        String output,
        String error,
        long durationMs,
        int tokens) {

    public boolean succeeded() {
        return status == SubagentTaskStatus.COMPLETED;
    }

    public static SubagentTaskResult completed(String taskId, String output,
                                               long durationMs, int tokens) {
        return new SubagentTaskResult(taskId, SubagentTaskStatus.COMPLETED,
                output == null ? "" : output, null, durationMs, tokens);
    }

    public static SubagentTaskResult failed(String taskId, String error, long durationMs) {
        return new SubagentTaskResult(taskId, SubagentTaskStatus.FAILED,
                "", error == null ? "unknown" : error, durationMs, 0);
    }

    public static SubagentTaskResult skipped(String taskId, String reason) {
        return new SubagentTaskResult(taskId, SubagentTaskStatus.SKIPPED,
                "", reason, 0, 0);
    }

    public static SubagentTaskResult cancelled(String taskId) {
        return new SubagentTaskResult(taskId, SubagentTaskStatus.CANCELLED,
                "", "cancelled", 0, 0);
    }
}
