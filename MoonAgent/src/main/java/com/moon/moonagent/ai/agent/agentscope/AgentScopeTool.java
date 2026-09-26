package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Explicit ToolBase adapter. Avoiding AgentScope's annotation/reflection path
 * keeps Spring AI's Jackson 3 schema generator isolated from AgentScope.
 */
final class AgentScopeTool extends ToolBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    interface Handler {
        Object call(Map<String, Object> input, ToolCallParam param);
    }

    private final Handler handler;

    AgentScopeTool(String name, String description, Map<String, Object> schema,
                   boolean readOnly, boolean concurrencySafe, boolean externalTool,
                   Handler handler) {
        super(ToolBase.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .readOnly(readOnly)
                .concurrencySafe(concurrencySafe)
                .externalTool(externalTool));
        this.handler = handler;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        if (isExternalTool()) return Mono.empty();
        return Mono.fromCallable(() -> {
            Object output = handler.call(param.getInput() == null ? Map.of() : param.getInput(), param);
            return ToolResultBlock.of(
                    param.getToolUseBlock().getId(),
                    getName(),
                    TextBlock.builder().text(toJson(output)).build());
        });
    }

    AgentScopeTool withHandler(Handler next) {
        return new AgentScopeTool(
                getName(), getDescription(), getParameters(),
                isReadOnly(), isConcurrencySafe(), isExternalTool(), next);
    }

    Object invoke(Map<String, Object> input, ToolCallParam param) {
        return handler.call(input == null ? Map.of() : input, param);
    }

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties == null ? Map.of() : properties,
                "required", required == null ? List.of() : required,
                "additionalProperties", false);
    }

    private static String toJson(Object value) {
        if (value == null) return "{}";
        if (value instanceof String text) return text;
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

}
