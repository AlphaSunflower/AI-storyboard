package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentTurnMetrics;
import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 单轮模型调用的线程安全 usage 累积器；不把 cached token 重复加到 total。 */
final class AgentUsageAccumulator {

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger usageCalls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();

    void beginCall() {
        modelCalls.incrementAndGet();
    }

    void recordUsage(Usage usage) {
        if (usage == null) return;
        if (usage instanceof org.springframework.ai.chat.metadata.EmptyUsage) return;
        recordUsage(
                usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(),
                usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens());
    }

    void recordUsage(long input, long output, long cachedInput) {
        usageCalls.incrementAndGet();
        inputTokens.addAndGet(Math.max(0L, input));
        outputTokens.addAndGet(Math.max(0L, output));
        cachedInputTokens.addAndGet(Math.max(0L, cachedInput));
    }

    AgentTurnMetrics snapshot(long durationMs) {
        int calls = modelCalls.get();
        long input = inputTokens.get();
        long output = outputTokens.get();
        return new AgentTurnMetrics(
                input,
                output,
                cachedInputTokens.get(),
                input + output,
                Math.max(0L, durationMs),
                calls,
                calls > 0 && usageCalls.get() == calls);
    }
}
