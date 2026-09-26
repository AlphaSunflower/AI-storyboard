package com.moon.moonagent.ai.agent.turn;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import org.junit.jupiter.api.Test;

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
}

