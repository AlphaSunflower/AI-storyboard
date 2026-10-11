package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallContentMiddlewareTest {

    @Test
    void restoresJsonContentWhenStreamParserLeavesItNull() {
        ToolCallContentMiddleware middleware = new ToolCallContentMiddleware();
        ToolUseBlock original = new ToolUseBlock(
                "call-1",
                "agent_spawn",
                Map.of("agent_id", "visual-prompt-director", "task", "优化提示词"),
                null,
                Map.of(),
                null);
        AtomicReference<ActingInput> captured = new AtomicReference<>();

        middleware.onActing(null, null, new ActingInput(List.of(original)), input -> {
            captured.set(input);
            return Flux.empty();
        }).blockLast();

        ToolUseBlock normalized = captured.get().toolCalls().getFirst();
        assertThat(normalized.getContent())
                .contains("\"agent_id\":\"visual-prompt-director\"")
                .contains("\"task\":\"优化提示词\"");
    }

    @Test
    void preservesContentAlreadyPresentOnToolCall() {
        ToolCallContentMiddleware middleware = new ToolCallContentMiddleware();
        ToolUseBlock original = new ToolUseBlock(
                "call-1",
                "generate_video",
                Map.of("prompt", "镜头推进"),
                "{\"prompt\":\"镜头推进\"}",
                Map.of(),
                null);
        AtomicReference<ActingInput> captured = new AtomicReference<>();

        middleware.onActing(null, null, new ActingInput(List.of(original)), input -> {
            captured.set(input);
            return Flux.empty();
        }).blockLast();

        assertThat(captured.get().toolCalls().getFirst()).isSameAs(original);
    }

    @Test
    void countsRewriteOnlyWhenContentIsActuallyRestored() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallContentMiddleware middleware = new ToolCallContentMiddleware(meters);

        ToolUseBlock missingContent = new ToolUseBlock(
                "call-1", "write_file", Map.of("path", "output/a.md"), null, Map.of(), null);
        middleware.onActing(null, null, new ActingInput(List.of(missingContent)),
                input -> Flux.empty()).blockLast();

        ToolUseBlock presentContent = new ToolUseBlock(
                "call-2", "write_file", Map.of("path", "output/a.md"),
                "{\"path\":\"output/a.md\"}", Map.of(), null);
        middleware.onActing(null, null, new ActingInput(List.of(presentContent)),
                input -> Flux.empty()).blockLast();

        assertThat(meters.counter(
                ToolCallContentMiddleware.REWRITE_METRIC, "tool", "write_file").count())
                .isEqualTo(1.0d);
    }
}
