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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    void sinkSuppressesLegacyDirectTraceEmission() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), emitter);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        trace.setEventSink(event -> { });

        trace.start("model_reasoning", "agentscope", "模型推理", "");

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void recordsThinkingDeltasAsARestorableReasoningSnapshot() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), new SseEmitter());
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);

        trace.recordThinkingDelta("先确认用户意图。");
        trace.recordThinkingDelta("再选择技能。");

        assertThat(trace.reasoningSnapshot()).isNotNull();
        assertThat(trace.reasoningSnapshot().details())
                .contains("先确认用户意图。")
                .contains("再选择技能。");
        assertThat(trace.reasoningSnapshot().source()).isEqualTo("agentscope");
    }

    @Test
    void separatesMultipleThinkingBlocksInThePersistedReasoningSnapshot() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), new SseEmitter());
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);

        trace.recordThinkingStart();
        trace.recordThinkingDelta("first block");
        trace.recordThinkingEnd();
        trace.recordThinkingStart();
        trace.recordThinkingDelta("second block");
        trace.recordThinkingEnd();

        assertThat(trace.reasoningSnapshot().details())
                .isEqualTo("first block\n\nsecond block");
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

    @Test
    void carriesModelTimeAndContextBreakdownIntoMetrics() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), new SseEmitter());
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        trace.setContextBreakdown(1_500L, 5_600L);
        trace.beginModelCall();
        trace.recordUsage(50_000, 400, 40_000, 1_750L);
        trace.beginToolCall("call-1");
        trace.endToolCall("call-1");

        AgentTurnMetrics metrics = trace.turnMetrics();

        assertThat(metrics.modelDurationMs()).isEqualTo(1_750L);
        assertThat(metrics.toolDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(metrics.systemPromptTokens()).isEqualTo(1_500L);
        assertThat(metrics.toolDefinitionTokens()).isEqualTo(5_600L);
        assertThat(metrics.messageTokens()).isEqualTo(50_000L - 1_500L - 5_600L);
    }
}
