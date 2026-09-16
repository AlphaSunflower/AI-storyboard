package com.moon.moonagent.ai.agent.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 画布模式下强制覆盖工具参数中的 projectId/canvasId，防止模型漏传或被其它上下文污染。
 */
public final class CanvasContextToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final CanvasSessionContext context;
    private final AtomicReference<String> activeCanvasId;

    public CanvasContextToolCallback(ToolCallback delegate, CanvasSessionContext context) {
        this(delegate, context, new AtomicReference<>(context == null ? null : context.canvasId()));
    }

    public CanvasContextToolCallback(ToolCallback delegate,
                                     CanvasSessionContext context,
                                     AtomicReference<String> activeCanvasId) {
        this.delegate = delegate;
        this.context = context;
        this.activeCanvasId = activeCanvasId;
    }

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
        String result = delegate.call(injectCanvasScope(toolInput));
        inspectCanvasToolResult(result);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String result = delegate.call(injectCanvasScope(toolInput), toolContext);
        inspectCanvasToolResult(result);
        return result;
    }

    private String injectCanvasScope(String toolInput) {
        if (context == null || !context.isCanvasEditMode()) return toolInput;
        try {
            JsonNode parsed = toolInput == null || toolInput.isBlank()
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(toolInput);
            ObjectNode object = parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed
                    : OBJECT_MAPPER.createObjectNode();
            if ("canvas_get_context".equals(getToolDefinition().name())) {
                object.remove("projectId");
                object.remove("canvasId");
                return OBJECT_MAPPER.writeValueAsString(object);
            }
            object.put("projectId", context.projectId());
            object.put("canvasId", activeCanvasId.get());
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (Exception ignored) {
            return toolInput;
        }
    }

    private void inspectCanvasToolResult(String toolResult) {
        if (context == null || activeCanvasId == null || toolResult == null || toolResult.isBlank()) return;
        ToolDefinition definition = getToolDefinition();
        if (definition == null) return;
        JsonNode root;
        try {
            root = parseToolPayload(toolResult);
        } catch (Exception ignored) {
            // A malformed tool response must not change the active canvas scope.
            return;
        }
        if (root == null) return;
        if ("canvas_get_context".equals(definition.name())) {
            String canvasId = root.path("canvasId").asText("");
            if (!canvasId.isBlank()) activeCanvasId.set(canvasId);
            return;
        }
        if (!"canvas_create_canvas".equals(definition.name())) return;
        if ("CANVAS_ACTIVATION_TIMEOUT".equals(root.path("code").asText())) {
            JsonNode details = root.path("details");
            throw new CanvasActivationRequiredException(
                    details.path("projectId").asText(context.projectId()),
                    details.path("canvasId").asText(activeCanvasId.get()),
                    details.path("title").asText("新画布")
            );
        }
        if (!root.path("activated").asBoolean(false)) return;
        String canvasId = root.path("canvasId").asText("");
        if (!canvasId.isBlank()) activeCanvasId.set(canvasId);
    }

    private static JsonNode parseToolPayload(String toolResult) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(toolResult);
        if (root == null) return null;
        if (root.isArray() && !root.isEmpty()) root = root.get(0);
        if (root != null && root.isObject() && root.has("text")) root = root.path("text");
        while (root != null && root.isTextual()) root = OBJECT_MAPPER.readTree(root.asText());
        return root;
    }
}
