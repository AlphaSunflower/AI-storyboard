package com.moon.moonagent.ai.agent.turn;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeEventMapperTest {

    private final AgentScopeEventMapper mapper = new AgentScopeEventMapper();

    @Test
    void mapsTextDeltasToStableEnvelopePayload() {
        TextBlockDeltaEvent event =
                new TextBlockDeltaEvent("reply-1", "block-1", "hello");

        assertThat(mapper.type(event)).isEqualTo("TEXT_BLOCK_DELTA");
        assertThat(mapper.payload(event))
                .containsEntry("replyId", "reply-1")
                .containsEntry("blockId", "block-1")
                .containsEntry("delta", "hello");
    }

    @Test
    void mapsToolCallIdentity() {
        ToolCallStartEvent event =
                new ToolCallStartEvent("reply-1", "call-1", "canvas_get_context");

        assertThat(mapper.type(event)).isEqualTo("TOOL_CALL_START");
        assertThat(mapper.payload(event))
                .containsEntry("toolCallId", "call-1")
                .containsEntry("toolName", "canvas_get_context");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsModelUsageWithContextOccupancyAndWindow() {
        ModelCallEndEvent event = new ModelCallEndEvent(
                "reply-1", "moon", "", new ChatUsage(960, 120, 640, 1.0));

        Map<String, Object> usage = (Map<String, Object>) mapper
                .payload(event, 200_000L)
                .get("usage");

        assertThat(usage)
                .containsEntry("inputTokens", 960)
                .containsEntry("outputTokens", 120)
                .containsEntry("cachedTokens", 640)
                .containsEntry("contextTokens", 960)
                .containsEntry("contextWindowTokens", 200_000L);
    }
}

