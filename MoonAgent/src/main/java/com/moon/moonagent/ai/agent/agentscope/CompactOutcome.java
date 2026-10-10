package com.moon.moonagent.ai.agent.agentscope;

/**
 * 手动压缩会话上下文的结果。
 *
 * <p>{@code status}：{@code COMPACTED} 已压缩 / {@code SKIPPED} 无需压缩 /
 * {@code UNAVAILABLE} 运行时未启用或会话状态缺失。
 */
public record CompactOutcome(
        String status,
        int messagesBefore,
        int messagesAfter,
        int tokensBefore,
        int tokensAfter) {

    public static final CompactOutcome UNAVAILABLE = new CompactOutcome("UNAVAILABLE", 0, 0, 0, 0);
    public static final CompactOutcome SKIPPED = new CompactOutcome("SKIPPED", 0, 0, 0, 0);

    public boolean compacted() {
        return "COMPACTED".equals(status);
    }
}
