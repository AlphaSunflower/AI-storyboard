package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps one business implementation while exposing it through both tool APIs.
 */
public final class SpringAiToolAdapter extends ToolBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback callback;

    public SpringAiToolAdapter(ToolCallback callback) {
        super(ToolBase.builder()
                .name(callback.getToolDefinition().name())
                .description(callback.getToolDefinition().description())
                .inputSchema(parseSchema(callback.getToolDefinition().inputSchema()))
                .readOnly(false)
                .concurrencySafe(false));
        this.callback = callback;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            String input = JSON.writeValueAsString(
                    param.getInput() == null ? Map.of() : param.getInput());
            String output = callback.call(input);
            return ToolResultBlock.of(
                    param.getToolUseBlock().getId(),
                    getName(),
                    TextBlock.builder().text(output == null ? "" : output).build());
        });
    }

    private static Map<String, Object> parseSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            Map<String, Object> parsed = JSON.readValue(
                    schema, new TypeReference<LinkedHashMap<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }
}
