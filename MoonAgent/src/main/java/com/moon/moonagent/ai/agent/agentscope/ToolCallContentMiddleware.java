package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AgentScope 2.0.3 may leave ToolUseBlock.content null for streamed or
 * provider-specific tool calls. ToolValidator then fails before invoking the
 * tool. Restore the canonical JSON argument string from the parsed input map.
 */
final class ToolCallContentMiddleware implements MiddlewareBase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        List<ToolUseBlock> normalized = new ArrayList<>(input.toolCalls().size());
        boolean changed = false;
        for (ToolUseBlock call : input.toolCalls()) {
            if (call == null || call.getContent() != null && !call.getContent().isBlank()) {
                normalized.add(call);
                continue;
            }
            normalized.add(new ToolUseBlock(
                    call.getId(),
                    call.getName(),
                    call.getInput(),
                    argumentsJson(call.getInput()),
                    call.getMetadata(),
                    call.getState()));
            changed = true;
        }
        return next.apply(changed ? new ActingInput(normalized) : input);
    }

    private static String argumentsJson(Map<String, Object> input) {
        try {
            return OBJECT_MAPPER.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
