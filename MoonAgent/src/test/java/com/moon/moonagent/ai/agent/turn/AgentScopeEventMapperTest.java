package com.moon.moonagent.ai.agent.turn;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
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

    @Test
    void mapsThinkingDataCustomAndSubagentContractEvents() {
        ThinkingBlockStartEvent thinking =
                new ThinkingBlockStartEvent("reply-1", "think-1");
        DataBlockDeltaEvent data =
                new DataBlockDeltaEvent("reply-1", "data-1", "chunk");
        CustomEvent custom =
                new CustomEvent("state_updated", Map.of("task", "running"));
        SubagentExposedEvent subagent =
                new SubagentExposedEvent("sub-1", "creative-planner", "session-1", "Planner");

        assertThat(mapper.type(thinking)).isEqualTo("THINKING_BLOCK_START");
        assertThat(mapper.payload(thinking))
                .containsEntry("replyId", "reply-1")
                .containsEntry("blockId", "think-1");
        assertThat(mapper.type(data)).isEqualTo("DATA_BLOCK_DELTA");
        assertThat(mapper.payload(data))
                .containsEntry("blockId", "data-1")
                .containsEntry("delta", "chunk");
        assertThat(mapper.type(custom)).isEqualTo("CUSTOM");
        assertThat(mapper.payload(custom))
                .containsEntry("name", "state_updated")
                .containsEntry("value", Map.of("task", "running"));
        assertThat(mapper.type(subagent)).isEqualTo("SUBAGENT_EXPOSED");
        assertThat(mapper.payload(subagent))
                .containsEntry("subagentId", "sub-1")
                .containsEntry("label", "Planner");
    }
}

