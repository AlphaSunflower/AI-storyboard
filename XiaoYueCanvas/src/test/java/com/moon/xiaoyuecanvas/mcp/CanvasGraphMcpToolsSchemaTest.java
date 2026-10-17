package com.moon.xiaoyuecanvas.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注入型内部参数（userId/projectId/canvasId/runId/operationKey）绝不能在 MCP schema 里是必填。
 *
 * <p>它们由 MoonAgent 的 {@code CanvasRunToolAdapter} 在模型调用**之后**注入；若 schema 标记必填，
 * AgentScope 会先用模型自己的入参做校验并失败：
 * {@code Parameter validation failed ... 未找到所需属性"projectId"}，模型随后会自己编
 * userId（曾提交 {@code "default"}）与 operationKey。
 */
class CanvasGraphMcpToolsSchemaTest {

    private static Map<String, McpServerFeatures.SyncToolSpecification> toolsByName() {
        SyncMcpToolProvider provider = new SyncMcpToolProvider(List.of(new CanvasGraphMcpTools(null)));
        return provider.getToolSpecifications().stream()
                .collect(java.util.stream.Collectors.toMap(
                        spec -> spec.tool().name(),
                        spec -> spec,
                        (left, right) -> left));
    }

    @Test
    void injectedInternalParamsAreNeverRequired() {
        Map<String, McpServerFeatures.SyncToolSpecification> tools = toolsByName();
        assertFalse(tools.isEmpty(), "画布 MCP 工具应能从注解生成 schema");

        for (McpServerFeatures.SyncToolSpecification spec : tools.values()) {
            Map<String, Object> schema = spec.tool().inputSchema();
            @SuppressWarnings("unchecked")
            List<String> required = schema.get("required") instanceof List<?> list
                    ? (List<String>) list
                    : List.of();
            for (String injected : List.of("userId", "projectId", "canvasId", "runId", "operationKey")) {
                assertFalse(required.contains(injected),
                        spec.tool().name() + " 不应要求模型传 " + injected + "（实际 required=" + required + "）");
            }
        }
    }

    @Test
    void payloadParamsStayRequiredSoTheModelStillSuppliesThem() {
        Map<String, McpServerFeatures.SyncToolSpecification> tools = toolsByName();

        @SuppressWarnings("unchecked")
        List<String> batchRequired = (List<String>) tools.get("canvas_graph_import_media_batch")
                .tool().inputSchema().get("required");
        assertNotNull(batchRequired);
        assertTrue(batchRequired.contains("mediaItems"), "业务入参仍必须由模型提供：required=" + batchRequired);

        @SuppressWarnings("unchecked")
        List<String> slotsRequired = (List<String>) tools.get("canvas_graph_create_result_slots")
                .tool().inputSchema().get("required");
        assertTrue(slotsRequired.contains("args"), "结果槽参数对象仍是必填：required=" + slotsRequired);

        @SuppressWarnings("unchecked")
        List<String> edgesRequired = (List<String>) tools.get("canvas_graph_create_edges")
                .tool().inputSchema().get("required");
        assertTrue(edgesRequired.contains("edges"), "批量连线入参仍是必填：required=" + edgesRequired);

        @SuppressWarnings("unchecked")
        List<String> planRequired = (List<String>) tools.get("canvas_graph_apply_plan")
                .tool().inputSchema().get("required");
        assertTrue(planRequired.contains("plan"), "整图计划入参仍是必填：required=" + planRequired);
        assertFalse(planRequired.contains("continueOnError"), "continueOnError 必须可选：required=" + planRequired);
    }

    /**
     * 新增的两个批量工具必须把注入型参数列为属性（供 MoonAgent 注入）但**不能**要求模型传，
     * 同时业务入参必须是真正的字段级 schema（类型化 record 生成 properties），而不是自由 object。
     */
    @Test
    void batchToolsExposeInjectedParamsAsOptionalPropertiesOnly() {
        Map<String, McpServerFeatures.SyncToolSpecification> tools = toolsByName();
        List<String> injected = List.of("userId", "projectId", "canvasId", "runId", "operationKey");
        for (String name : List.of("canvas_graph_create_edges", "canvas_graph_apply_plan")) {
            McpServerFeatures.SyncToolSpecification spec = tools.get(name);
            assertNotNull(spec, name + " 未暴露给模型");
            Map<String, Object> schema = spec.tool().inputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            List<String> required = schema.get("required") instanceof List<?> list
                    ? (List<String>) list
                    : List.of();
            for (String parameter : injected) {
                assertTrue(properties.containsKey(parameter),
                        name + " 必须保留注入型参数占位：" + parameter);
                assertFalse(required.contains(parameter),
                        name + " 不应要求模型传 " + parameter + "（实际 required=" + required + "）");
            }
        }
    }

    @Test
    void batchToolsExposeFieldLevelSchemasForNestedPayloads() {
        Map<String, McpServerFeatures.SyncToolSpecification> tools = toolsByName();

        @SuppressWarnings("unchecked")
        Map<String, Object> edgeItems = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) tools
                .get("canvas_graph_create_edges").tool().inputSchema().get("properties"))
                .get("edges"))
                .get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> edgeProperties = (Map<String, Object>) edgeItems.get("properties");
        assertTrue(edgeProperties.containsKey("source") && edgeProperties.containsKey("target"),
                "连线元素应暴露 source/target 字段级 schema：" + edgeProperties.keySet());
        List<?> edgeRequired = (List<?>) edgeItems.get("required");
        assertTrue(edgeRequired.containsAll(List.of("source", "target")),
                "source/target 必须是连线元素的必填字段：" + edgeRequired);

        String planSchema = String.valueOf(tools.get("canvas_graph_apply_plan").tool().inputSchema());
        assertTrue(planSchema.contains("refs"), "apply_plan 必须暴露 refs 字段：" + planSchema);
        assertTrue(planSchema.contains("resultSlots"), "apply_plan 必须暴露 resultSlots 字段");
        assertTrue(planSchema.contains("pruneUnreferencedResultSlots"), "apply_plan 必须暴露 pruneUnreferencedResultSlots 字段");
        assertTrue(planSchema.contains("result.<n>"), "refs 的描述必须写清 .result.<n> 写法，避免模型猜");
    }

    /**
     * 内联素材：apply_plan 的节点 schema 必须暴露 media 的字段级契约（mediaId 与 url 二选一），
     * 否则模型只能靠猜，而猜错会白花一整轮。
     */
    @Test
    void applyPlanExposesInlineMediaFieldLevelSchema() {
        String schema = String.valueOf(toolsByName().get("canvas_graph_apply_plan").tool().inputSchema());
        assertTrue(schema.contains("media"), "apply_plan 必须暴露节点级 media 字段：" + schema);
        assertTrue(schema.contains("mediaId"), "media 必须暴露 mediaId 字段");
        assertTrue(schema.contains("url"), "media 必须暴露 url 字段");
        assertTrue(schema.contains("二选一"), "media 的 mediaId/url 必须写清二选一，避免模型两个都给");
        assertTrue(schema.contains("内联素材"), "media 必须有中文 description 说明用途");
    }
}
