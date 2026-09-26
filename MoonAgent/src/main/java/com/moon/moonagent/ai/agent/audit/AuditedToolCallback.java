package com.moon.moonagent.ai.agent.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;
import java.util.function.Supplier;

@RequiredArgsConstructor
public final class AuditedToolCallback implements ToolCallback {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback delegate;
    private final ToolExecutionTemplate executionTemplate;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return execute(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return execute(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String execute(String toolInput, Supplier<String> invocation) {
        return executionTemplate.execute(
                getToolDefinition().name(),
                "MCP",
                "MCP",
                parse(toolInput),
                invocation);
    }

    private static Map<String, Object> parse(String input) {
        if (input == null || input.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = JSON.readValue(input, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of("inputPresent", true, "inputLength", input.length());
        }
    }
}
