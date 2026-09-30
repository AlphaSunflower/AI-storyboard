package com.moon.xiaoyuecanvas.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasGraphMcpToolsTest {

    private static final Set<String> REQUIRED_TOOL_NAMES = Set.of(
            "canvas_graph_get_context",
            "canvas_graph_get_state",
            "canvas_graph_list_nodes",
            "canvas_graph_get_node",
            "canvas_graph_list_edges",
            "canvas_graph_list_node_types",
            "canvas_graph_create_node",
            "canvas_graph_create_nodes",
            "canvas_graph_update_node",
            "canvas_graph_create_edge",
            "canvas_graph_import_media",
            "canvas_graph_import_media_batch",
            "canvas_graph_create_result_slots",
            "canvas_graph_mark_result_slots",
            "canvas_graph_layout_graph");

    @Test
    void exposesExactlyTheRequiredMcpTools() {
        Set<String> names = Arrays.stream(CanvasGraphMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(REQUIRED_TOOL_NAMES, names);
    }

    @Test
    void everyToolHasRequiredInternalContextParameters() {
        Set<String> required = Set.of("userId", "projectId", "canvasId", "runId", "operationKey");
        for (Method method : CanvasGraphMcpTools.class.getDeclaredMethods()) {
            if (method.getAnnotation(McpTool.class) == null) continue;
            Set<String> parameters = Arrays.stream(method.getParameters())
                    .map(java.lang.reflect.Parameter::getName)
                    .collect(Collectors.toSet());
            assertTrue(parameters.containsAll(required), method.getName() + " missing " + required);
            assertTrue(method.getReturnType().equals(Map.class), method.getName() + " must return Map");
        }
    }

    @Test
    void everyMcpToolAlsoHasLocalToolAnnotationForSharedScanners() {
        for (Method method : CanvasGraphMcpTools.class.getDeclaredMethods()) {
            if (method.getAnnotation(McpTool.class) == null) continue;
            assertTrue(method.getAnnotation(Tool.class) != null, method.getName() + " missing @Tool");
        }
    }
}
