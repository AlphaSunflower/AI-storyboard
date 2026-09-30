package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Starts a durable Canvas Run without pausing the current chat turn.
 */
@Component
@RequiredArgsConstructor
public class AgentScopeCanvasRunTools {

    public static final String CREATE_CANVAS_RUN = "canvas_create_run";

    private final AgentCanvasRunService runs;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                CREATE_CANVAS_RUN,
                "Create a new project canvas and a durable Canvas Run in the background. "
                        + "This never opens or waits for a browser iframe and never pauses the chat turn.",
                AgentScopeTool.schema(Map.of(
                        "title", Map.of("type", "string"),
                        "intent", Map.of("type", "string"),
                        "plan", Map.of("type", "array", "items", Map.of("type", "object"))),
                        List.of("title", "intent")),
                false, false, false,
                (input, param) -> {
                    AgentScopeRunContext context = context(param);
                    if (context == null || context.conversation() == null) {
                        return Map.of(
                                "ok", false,
                                "code", "CONVERSATION_REQUIRED",
                                "message", "缺少当前会话，无法创建画布任务");
                    }
                    String turnId = context.request() == null ? "" : context.request().getTurnId();
                    String toolCallId = toolCallId(param);
                    try {
                        CanvasSessionContext canvasContext = context.request() == null
                                ? null : context.request().getCanvasContext();
                        String existingCanvasId = canvasContext == null
                                ? "" : canvasContext.canvasId();
                        AgentCanvasRunService.CanvasRunView run =
                                existingCanvasId == null || existingCanvasId.isBlank()
                                        ? runs.create(
                                                context.conversation(),
                                                turnId,
                                                toolCallId,
                                                text(input.get("title")),
                                                text(input.get("intent")),
                                                maps(input.get("plan")))
                                        : runs.createExisting(
                                                context.conversation(),
                                                turnId,
                                                toolCallId,
                                                existingCanvasId,
                                                text(input.get("intent")),
                                                text(input.get("title")),
                                                maps(input.get("plan")));
                        if (context.turnState() != null) {
                            context.turnState().setActiveCanvasRun(run.runId(), run.canvasId());
                        }
                        return run.toMap();
                    } catch (IllegalStateException error) {
                        return Map.of(
                                "ok", false,
                                "code", "PROJECT_REQUIRED",
                                "message", error.getMessage());
                    }
                });
    }

    private static AgentScopeRunContext context(ToolCallParam param) {
        return param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

    private static String toolCallId(ToolCallParam param) {
        return param == null || param.getToolUseBlock() == null
                ? "" : param.getToolUseBlock().getId();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }
}
