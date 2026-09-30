package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.service.MorrowCanvasService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Local canvas lifecycle tools. Unlike canvas_* MCP commands, these execute
 * before a browser canvas exists and can prepare the iframe binding.
 */
@Component
@RequiredArgsConstructor
public class AgentScopeCanvasTools {

    public static final String PREPARE_CANVAS_SESSION = "prepare_canvas_session";

    private final MorrowCanvasService canvasService;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                PREPARE_CANVAS_SESSION,
                "Prepare and open a project canvas when no responsive canvas window is connected. "
                        + "This only creates canvas metadata and pauses for the canvas iframe; it never creates nodes.",
                AgentScopeTool.schema(Map.of(
                        "intent", Map.of("type", "string"),
                        "canvas_title", Map.of("type", "string"),
                        "canvas_id", Map.of("type", "string")), List.of("intent")),
                false, true, true,
                (input, param) -> Map.of());
    }

    public SessionPreparation prepare(AgentConversation conversation, Map<String, Object> input) {
        if (conversation == null || conversation.getUserId() == null
                || conversation.getUserId().isBlank()) {
            throw new IllegalArgumentException("缺少会话用户，无法准备画布");
        }
        String projectId = conversation.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("当前会话尚未绑定项目，无法准备画布");
        }
        String intent = text(input == null ? null : input.get("intent"));
        String requestedTitle = text(input == null ? null : input.get("canvas_title"));
        String requestedCanvasId = text(input == null ? null : input.get("canvas_id"));

        List<MorrowCanvas> existing = canvasService.listByProject(
                conversation.getUserId(), projectId);
        if (existing != null && !existing.isEmpty()) {
            MorrowCanvas target = requestedCanvasId.isBlank()
                    ? existing.getFirst()
                    : existing.stream()
                    .filter(canvas -> requestedCanvasId.equals(canvas.getId()))
                    .findFirst()
                    .orElse(existing.getFirst());
            return new SessionPreparation(
                    projectId,
                    target.getId(),
                    target.getTitle() == null ? "画布" : target.getTitle(),
                    intent,
                    existing.size(),
                    false);
        }

        String title = requestedTitle.isBlank() ? "AI 设计画布" : requestedTitle;
        MorrowCanvas created = canvasService.create(
                conversation.getUserId(), projectId, title);
        return new SessionPreparation(
                projectId,
                created.getId(),
                created.getTitle() == null ? title : created.getTitle(),
                intent,
                0,
                true);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record SessionPreparation(
            String projectId,
            String canvasId,
            String canvasTitle,
            String intent,
            int existingCanvasCount,
            boolean created
    ) {
    }
}
