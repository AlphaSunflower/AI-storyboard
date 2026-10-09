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
}
