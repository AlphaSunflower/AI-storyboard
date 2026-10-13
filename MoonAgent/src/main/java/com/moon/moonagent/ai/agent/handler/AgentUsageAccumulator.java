package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentTurnMetrics;
import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单轮模型调用的线程安全 usage 累积器；不把 cached token 重复加到 total。
 *
 * <p>除聚合用量外，额外记录本轮的「上下文占用」= 末次模型调用的 input token
 * （每次调用都携带完整历史，故末次调用最接近当前窗口占用），
 * 以及模型推理耗时 / 工具执行耗时，供底部状态栏展示。
 */
final class AgentUsageAccumulator {

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger usageCalls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong contextTokens = new AtomicLong();
    private final AtomicLong modelDurationMs = new AtomicLong();
    private final AtomicLong toolDurationMs = new AtomicLong();
    /** toolCallId → 工具开始执行的纳秒时间戳，用于配对计算工具耗时。 */
    private final ConcurrentHashMap<String, Long> toolStartNanos = new ConcurrentHashMap<>();

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
        contextTokens.set(Math.max(0L, input));
    }

    /** 累计一次模型推理耗时（毫秒）。 */
    void addModelDuration(long millis) {
        if (millis > 0L) modelDurationMs.addAndGet(millis);
    }

    /** 累计一次工具执行耗时（毫秒）。 */
    void addToolDuration(long millis) {
        if (millis > 0L) toolDurationMs.addAndGet(millis);
    }

    /** 工具开始执行（ToolResult 开始）。 */
    void beginToolCall(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) return;
        toolStartNanos.put(toolCallId, System.nanoTime());
    }

    /** 工具执行结束（ToolResult 结束）：累计该次执行耗时。 */
    void endToolCall(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) return;
        Long start = toolStartNanos.remove(toolCallId);
        if (start == null) return;
        addToolDuration((System.nanoTime() - start) / 1_000_000L);
    }

    AgentTurnMetrics snapshot(long durationMs) {
        return snapshot(durationMs, 0L, 0L, 0L);
    }

    AgentTurnMetrics snapshot(long durationMs, long contextWindowTokens) {
        return snapshot(durationMs, contextWindowTokens, 0L, 0L);
    }

    /**
     * 生成不可变指标快照。
     *
     * <p>上下文三色明细：系统提示词与工具定义为外部估算值，对话消息取
     * 「本轮上下文占用 − 系统提示词 − 工具定义」的余量，保证三段之和等于已用上下文。
     *
     * @param systemPromptTokens   系统提示词估算 token
     * @param toolDefinitionTokens 工具定义估算 token
     */
    AgentTurnMetrics snapshot(long durationMs, long contextWindowTokens,
                              long systemPromptTokens, long toolDefinitionTokens) {
        int calls = modelCalls.get();
        long input = inputTokens.get();
        long output = outputTokens.get();
        long context = contextTokens.get();
        long system = Math.max(0L, systemPromptTokens);
        long tools = Math.max(0L, toolDefinitionTokens);
        long messages = Math.max(0L, context - system - tools);
        if (context > 0L && system + tools > context) {
            // 上下文占用小于提示词本身的极端情况：按比例压缩，避免三色溢出
            double factor = (double) context / (double) (system + tools);
            system = Math.round(system * factor);
            tools = Math.max(0L, context - system);
            messages = Math.max(0L, context - system - tools);
        }
        return new AgentTurnMetrics(
                input,
                output,
                cachedInputTokens.get(),
                input + output,
                context,
                Math.max(0L, contextWindowTokens),
                Math.max(0L, durationMs),
                calls,
                calls > 0 && usageCalls.get() == calls,
                modelDurationMs.get(),
                toolDurationMs.get(),
                system,
                tools,
                messages);
    }
}
