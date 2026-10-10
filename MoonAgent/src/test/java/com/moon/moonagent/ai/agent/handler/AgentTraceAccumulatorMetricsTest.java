package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentTurnMetrics;
import com.moon.moonagent.ai.agent.AgentContextWindowResolver;
import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceAccumulatorMetricsTest {

    @Test
    void forwardsLiveTraceEventsToSequencedSink() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), new SseEmitter());
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        AtomicReference<com.moon.moonagent.ai.agent.AgentTraceEvent> received =
                new AtomicReference<>();
        trace.setEventSink(received::set);

        String id = trace.start("model_reasoning", "agentscope", "模型推理", "");

        assertThat(received.get()).isNotNull();
        assertThat(received.get().id()).isEqualTo(id);
        assertThat(received.get().status()).isEqualTo("node_started");
    }

    @Test
    void enrichesTerminalEventWithoutChangingAccumulatedUsageOnRepeat() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), new SseEmitter());
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        request.setTrace(trace);
        trace.beginModelCall();
        trace.recordUsage(120, 30, 20);

        Map<String, Object> first = trace.enrichTerminalEvent(Map.of("content", "ok"));
        Map<String, Object> second = trace.enrichTerminalEvent(Map.of("content", "ok"));

        AgentTurnMetrics firstMetrics = (AgentTurnMetrics) first.get("metrics");
        AgentTurnMetrics secondMetrics = (AgentTurnMetrics) second.get("metrics");
        assertThat(firstMetrics.inputTokens()).isEqualTo(120L);
        assertThat(firstMetrics.outputTokens()).isEqualTo(30L);
        assertThat(firstMetrics.totalTokens()).isEqualTo(150L);
        assertThat(firstMetrics.contextTokens()).isEqualTo(120L);
        assertThat(firstMetrics.contextWindowTokens())
                .isEqualTo(AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS);
        assertThat(firstMetrics.complete()).isTrue();
        assertThat(secondMetrics.totalTokens()).isEqualTo(150L);
        assertThat(secondMetrics.modelCalls()).isEqualTo(1);
        assertThat(firstMetrics.durationMs()).isGreaterThanOrEqualTo(0L);
    }
}
