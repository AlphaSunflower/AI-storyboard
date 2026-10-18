package com.moon.moonagent.ai.agent.orchestration;

/**
 * 整张任务图的预算。0 表示不限。
 */
public record SubagentBudget(int maxTokens, long maxDurationMs) {

    public static final SubagentBudget UNLIMITED = new SubagentBudget(0, 0);

    public boolean exhausted(int usedTokens, long elapsedMs) {
        return (maxTokens > 0 && usedTokens >= maxTokens)
                || (maxDurationMs > 0 && elapsedMs >= maxDurationMs);
    }
}
