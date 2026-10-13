package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentTurnMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;

import static org.assertj.core.api.Assertions.assertThat;

class AgentUsageAccumulatorTest {

    @Test
    void aggregatesInputOutputAndCachedTokensWithoutDoubleCountingCache() {
        AgentUsageAccumulator accumulator = new AgentUsageAccumulator();
        accumulator.beginCall();
        accumulator.recordUsage(100, 20, 30);
        accumulator.beginCall();
        accumulator.recordUsage(40, 10, 5);

        AgentTurnMetrics metrics = accumulator.snapshot(1_250L);

        assertThat(metrics.inputTokens()).isEqualTo(140L);
        assertThat(metrics.outputTokens()).isEqualTo(30L);
        assertThat(metrics.cachedInputTokens()).isEqualTo(35L);
        assertThat(metrics.totalTokens()).isEqualTo(170L);
        // 上下文占用取末次调用的 input（40），而非累加值 140
        assertThat(metrics.contextTokens()).isEqualTo(40L);
        assertThat(metrics.modelCalls()).isEqualTo(2);
        assertThat(metrics.durationMs()).isEqualTo(1_250L);
        assertThat(metrics.complete()).isTrue();
    }

    @Test
    void marksUsagePartialWhenAnyModelCallOmitsUsage() {
        AgentUsageAccumulator accumulator = new AgentUsageAccumulator();
        accumulator.beginCall();
        accumulator.recordUsage(80, 20, 0);
        accumulator.beginCall();

        AgentTurnMetrics metrics = accumulator.snapshot(500L);

        assertThat(metrics.totalTokens()).isEqualTo(100L);
        assertThat(metrics.modelCalls()).isEqualTo(2);
        assertThat(metrics.complete()).isFalse();
    }

    @Test
    void exposesDurationEvenWhenNoModelCallUsedTheGateway() {
        AgentUsageAccumulator accumulator = new AgentUsageAccumulator();

        AgentTurnMetrics metrics = accumulator.snapshot(75L, 200_000L);

        assertThat(metrics.totalTokens()).isZero();
        assertThat(metrics.contextTokens()).isZero();
        assertThat(metrics.contextWindowTokens()).isEqualTo(200_000L);
        assertThat(metrics.modelCalls()).isZero();
        assertThat(metrics.complete()).isFalse();
        assertThat(metrics.durationMs()).isEqualTo(75L);
    }

    @Test
    void treatsSpringAiEmptyUsageAsMissingUsage() {
        AgentUsageAccumulator accumulator = new AgentUsageAccumulator();
        accumulator.beginCall();

        accumulator.recordUsage(new EmptyUsage());

        AgentTurnMetrics metrics = accumulator.snapshot(10L);
        assertThat(metrics.totalTokens()).isZero();
        assertThat(metrics.complete()).isFalse();
    }

    @Test
    void aggregatesModelAndToolDurations() {
        AgentUsageAccumulator accumulator = new AgentUsageAccumulator();
        accumulator.addModelDuration(1_200L);
        accumulator.addToolDuration(350L);
        accumulator.addToolDuration(-5L);

        AgentTurnMetrics metrics = accumulator.snapshot(2_000L);

        assertThat(metrics.modelDurationMs()).isEqualTo(1_200L);
        assertThat(metrics.toolDurationMs()).isEqualTo(350L);
    }

    @Test
    void splitsContextIntoSystemToolsAndMessages() {
        AgentUsageAccumulator accumulator = new AgentUsageAccumulator();
        accumulator.beginCall();
        accumulator.recordUsage(100_000, 500, 90_000);

        AgentTurnMetrics metrics = accumulator.snapshot(4_000L, 1_000_000L, 1_500L, 5_600L);

        assertThat(metrics.contextTokens()).isEqualTo(100_000L);
        assertThat(metrics.systemPromptTokens()).isEqualTo(1_500L);
        assertThat(metrics.toolDefinitionTokens()).isEqualTo(5_600L);
        assertThat(metrics.messageTokens()).isEqualTo(92_900L);
        assertThat(metrics.systemPromptTokens()
                + metrics.toolDefinitionTokens()
                + metrics.messageTokens()).isEqualTo(metrics.contextTokens());
    }

    @Test
    void clampsContextSplitWhenPromptAlreadyExceedsOccupancy() {
        AgentUsageAccumulator accumulator = new AgentUsageAccumulator();
        accumulator.beginCall();
        accumulator.recordUsage(4_000, 10, 0);

        AgentTurnMetrics metrics = accumulator.snapshot(100L, 0L, 3_000L, 3_000L);

        assertThat(metrics.systemPromptTokens() + metrics.toolDefinitionTokens())
                .isLessThanOrEqualTo(metrics.contextTokens());
        assertThat(metrics.messageTokens()).isZero();
    }
}
