package com.moon.xiaoyuecanvas.mcp;

import com.moon.xiaoyuecanvas.bridge.CanvasBrowserMailbox;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasGroupMcpToolsTest {

    @Test
    void exposesOnlyTheGroupingTool() {
        Set<String> names = Arrays.stream(CanvasGroupMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of("canvas_group_nodes"), names);
    }

    @Test
    void keepsInjectedCanvasContextAndLocalAnnotation() {
        Set<String> required = Set.of("userId", "projectId", "canvasId", "runId", "operationKey");
        for (Method method : CanvasGroupMcpTools.class.getDeclaredMethods()) {
            if (method.getAnnotation(McpTool.class) == null) continue;
            assertTrue(method.getAnnotation(Tool.class) != null, method.getName() + " missing @Tool");
            Set<String> parameters = Arrays.stream(method.getParameters())
                    .map(java.lang.reflect.Parameter::getName)
                    .collect(Collectors.toSet());
            assertTrue(parameters.containsAll(required), method.getName() + " missing " + required);
            assertEquals(Map.class, method.getReturnType(), method.getName() + " must return Map");
        }
    }

    @Test
    void reportsCanvasNotOpenThroughTheToolCall() {
        CanvasGroupMcpTools tools = new CanvasGroupMcpTools(new CanvasBrowserMailbox());

        Map<String, Object> payload = tools.canvasGroupNodes(
                "user-1", "project-1", "canvas-1", "run-1", "op-1", List.of("n1", "n2"), "赛亚人转场");

        assertEquals(false, payload.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        assertEquals("CANVAS_NOT_OPEN", error.get("code"));
    }

    @Test
    void roundTripsGroupingThroughTheBrowserMailboxWithoutForcedPreview() throws Exception {
        CanvasBrowserMailbox mailbox = new CanvasBrowserMailbox();
        mailbox.register("user-1", "tab-1", "project-1", "项目", "canvas-1", "画布");
        CanvasGroupMcpTools tools = new CanvasGroupMcpTools(mailbox);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> pending = pool.submit(() -> tools.canvasGroupNodes(
                    "user-1", "project-1", "canvas-1", "run-1", "op-1",
                    List.of("n1", "n2", "n3"), "赛亚人转场"));

            Map<String, Object> command = awaitCommand(mailbox);
            assertEquals("canvas.group.create", command.get("method"));
            assertEquals(false, command.get("dryRun"), "分组只做组织，不应被强制预览");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) command.get("params");
            assertEquals(List.of("n1", "n2", "n3"), params.get("nodeIds"));
            assertEquals("赛亚人转场", params.get("title"));

            Map<String, Object> browserResponse = new LinkedHashMap<>();
            browserResponse.put("ok", true);
            browserResponse.put("status", "applied");
            browserResponse.put("result", Map.of("groupId", "group-7", "title", "赛亚人转场",
                    "nodeIds", List.of("n1", "n2", "n3")));
            mailbox.result("user-1", String.valueOf(command.get("requestId")), browserResponse);

            Map<String, Object> payload = pending.get(5, TimeUnit.SECONDS);
            assertEquals(true, payload.get("ok"));
            assertEquals("group-7", payload.get("groupId"));
            assertEquals("赛亚人转场", payload.get("title"));
            assertEquals("applied", payload.get("status"));
        } finally {
            pool.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> awaitCommand(CanvasBrowserMailbox mailbox) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            Map<String, Object> polled = mailbox.poll("user-1", "tab-1");
            if (polled.containsKey("command")) return (Map<String, Object>) polled.get("command");
            Thread.sleep(10);
        }
        throw new AssertionError("命令没有进入队列");
    }
}
