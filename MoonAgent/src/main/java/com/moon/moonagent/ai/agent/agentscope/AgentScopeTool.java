package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit ToolBase adapter. Avoiding AgentScope's annotation/reflection path
 * keeps Spring AI's Jackson 3 schema generator isolated from AgentScope.
 */
final class AgentScopeTool extends ToolBase {

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
            // 模型可见结果唯一出口：确保是契约信封后严格序列化（null/序列化失败不再退化为 "{}" 或 Object@hash）。
            // 管线内部已经归一过的信封直接复用，只有外部调用方递进来的原始 Map 才在这里补一次归一。
            Map<String, Object> alreadyCanonical = asEnvelope(output);
            Map<String, Object> normalized = ToolResultContract.isCanonical(alreadyCanonical)
                    ? alreadyCanonical
                    : ToolResultContract.normalize(getName(), output);
            return ToolResultBlock.of(
                    param.getToolUseBlock().getId(),
                    getName(),
                    TextBlock.builder().text(ToolResultContract.serialize(normalized)).build());
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

    /** 已归一信封必须是字符串键 Map；其它类型（含 null、List、String）一律交给契约归一。 */
    private static Map<String, Object> asEnvelope(Object output) {
        if (!(output instanceof Map<?, ?> map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties == null ? Map.of() : properties,
                "required", required == null ? List.of() : required,
                "additionalProperties", false);
    }

}
