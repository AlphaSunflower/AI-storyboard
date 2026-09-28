package com.moon.moonagent.ai.agent.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AgentScopeEventMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String type(AgentEvent event) {
        return event == null || event.getType() == null
                ? "unknown" : event.getType().getValue();
    }

    public Map<String, Object> payload(AgentEvent event) {
        if (event == null) return Map.of();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getId());
        payload.put("createdAt", event.getCreatedAt());
        payload.put("source", event.getSource());
        if (event.getMetadata() != null && !event.getMetadata().isEmpty()) {
            payload.put("metadata", event.getMetadata());
        }

        if (event instanceof TextBlockDeltaEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("blockId", value.getBlockId());
            payload.put("delta", value.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("blockId", value.getBlockId());
            payload.put("delta", value.getDelta());
        } else if (event instanceof TextBlockStartEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("blockId", value.getBlockId());
        } else if (event instanceof TextBlockEndEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("blockId", value.getBlockId());
        } else if (event instanceof ToolCallStartEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("toolCallId", value.getToolCallId());
            payload.put("toolName", value.getToolCallName());
        } else if (event instanceof ToolCallEndEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("toolCallId", value.getToolCallId());
            payload.put("toolName", value.getToolCallName());
        } else if (event instanceof ToolResultStartEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("toolCallId", value.getToolCallId());
            payload.put("toolName", value.getToolCallName());
        } else if (event instanceof ToolResultTextDeltaEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("toolCallId", value.getToolCallId());
            payload.put("toolName", value.getToolCallName());
            payload.put("delta", value.getDelta());
        } else if (event instanceof ToolResultEndEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("toolCallId", value.getToolCallId());
            payload.put("toolName", value.getToolCallName());
            payload.put("state", value.getState() == null ? "" : value.getState().name());
        } else if (event instanceof ModelCallStartEvent value) {
            payload.put("replyId", value.getReplyId());
        } else if (event instanceof ModelCallEndEvent value) {
            payload.put("replyId", value.getReplyId());
            if (value.getUsage() != null) {
                payload.put("usage", Map.of(
                        "inputTokens", value.getUsage().getInputTokens(),
                        "outputTokens", value.getUsage().getOutputTokens(),
                        "cachedTokens", value.getUsage().getCachedTokens()));
            }
        } else if (event instanceof RequireUserConfirmEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("toolCalls", value.getToolCalls());
        } else if (event instanceof RequireExternalExecutionEvent value) {
            payload.put("replyId", value.getReplyId());
            payload.put("toolCalls", value.getToolCalls());
        } else if (event instanceof AgentResultEvent value) {
            payload.put("result", value.getResult() == null
                    ? Map.of() : value.getResult().getTextContent());
        } else if (event instanceof AgentStartEvent || event instanceof AgentEndEvent
                || event instanceof ExceedMaxItersEvent) {
            // Envelope fields are sufficient for lifecycle events.
        } else {
            payload.putAll(generic(event));
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> generic(AgentEvent event) {
        try {
            return JSON.convertValue(event, Map.class);
        } catch (Exception e) {
            return Map.of("value", String.valueOf(event));
        }
    }
}

