package com.moon.xiaoyuecanvas.mcp;

import com.moon.xiaoyuecanvas.bridge.CanvasBrowserMailbox;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasExecuteMcpToolsTest {

    private static final Set<String> REQUIRED_TOOL_NAMES = Set.of(
            "canvas_execute_node",
            "canvas_execute_graph",
            "canvas_get_execution",
            "canvas_cancel_execution");

    @Test
    void exposesExactlyTheBrowserExecutionTools() {
        Set<String> names = Arrays.stream(CanvasExecuteMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(java.util.Objects::nonNull)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertEquals(REQUIRED_TOOL_NAMES, names);
    }

    @Test
    void everyToolKeepsTheInjectedCanvasContextAndLocalAnnotation() {
        Set<String> required = Set.of("userId", "projectId", "canvasId", "runId", "operationKey");
        for (Method method : CanvasExecuteMcpTools.class.getDeclaredMethods()) {
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
    void unwrapsSuccessfulBrowserResultIntoGraphStylePayload() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("result", Map.of("jobId", "job-1"));
        response.put("status", "accepted");
        response.put("revision", 7);

        Map<String, Object> payload = CanvasExecuteMcpTools.toToolPayload(response);

        assertEquals(true, payload.get("ok"));
        assertEquals("job-1", payload.get("jobId"));
        assertEquals("accepted", payload.get("status"));
        assertEquals(7, payload.get("revision"));
    }

    @Test
    void foldsFailureIntoStableErrorEnvelope() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("error", Map.of("code", "BRIDGE_TIMEOUT", "message", "浏览器未在超时时间内响应命令"));

        Map<String, Object> payload = CanvasExecuteMcpTools.toToolPayload(response);

        assertEquals(false, payload.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        assertEquals("BRIDGE_TIMEOUT", error.get("code"));
        assertEquals("浏览器未在超时时间内响应命令", error.get("message"));
    }

    @Test
    void reportsCanvasNotOpenThroughTheToolCall() {
        CanvasExecuteMcpTools tools = new CanvasExecuteMcpTools(new CanvasBrowserMailbox());

        Map<String, Object> payload = tools.canvasExecuteNode(
                "user-1", "project-1", "canvas-1", "run-1", "op-1", "node-1", null, false);

        assertEquals(false, payload.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        assertEquals("CANVAS_NOT_OPEN", error.get("code"));
    }

    @Test
    void roundTripsAnExecuteCommandThroughTheBrowserMailbox() throws Exception {
        CanvasBrowserMailbox mailbox = new CanvasBrowserMailbox();
        mailbox.register("user-1", "tab-1", "project-1", "项目", "canvas-1", "画布");
        CanvasExecuteMcpTools tools = new CanvasExecuteMcpTools(mailbox);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> pending = pool.submit(() -> tools.canvasExecuteNode(
                    "user-1", "project-1", "canvas-1", "run-1", "op-1", "node-1", "generate", false));

            Map<String, Object> command = awaitCommand(mailbox);
            assertEquals("canvas.node.execute", command.get("method"));
            assertEquals("node-1", ((Map<?, ?>) command.get("params")).get("nodeId"));
            assertEquals("generate", ((Map<?, ?>) command.get("params")).get("actionId"));
            assertEquals(true, command.get("dryRun"), "执行类命令必须由服务端强制预览");

            Map<String, Object> browserResponse = new LinkedHashMap<>();
            browserResponse.put("ok", true);
            browserResponse.put("result", Map.of("jobId", "job-42"));
            mailbox.result("user-1", String.valueOf(command.get("requestId")), browserResponse);

            Map<String, Object> payload = pending.get(5, TimeUnit.SECONDS);
            assertEquals(true, payload.get("ok"));
            assertEquals("job-42", payload.get("jobId"));
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
