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

    public static final String PREPARE_CANVAS_SESSION = "canvas_prepare_session";

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
            if (requestedCanvasId.isBlank() && !requestedTitle.isBlank()) {
                MorrowCanvas created = canvasService.create(
                        conversation.getUserId(), projectId, requestedTitle);
                return new SessionPreparation(
                        projectId,
                        created.getId(),
                        created.getTitle() == null ? requestedTitle : created.getTitle(),
                        intent,
                        existing.size(),
                        true,
                        List.of(new CanvasChoice(created.getId(),
                                created.getTitle() == null ? requestedTitle : created.getTitle())));
            }
            List<CanvasChoice> choices = existing.stream()
                    .map(canvas -> new CanvasChoice(
                            canvas.getId(),
                            canvas.getTitle() == null ? "画布" : canvas.getTitle()))
                    .toList();
            MorrowCanvas target = requestedCanvasId.isBlank()
                    ? (existing.size() == 1 ? existing.getFirst() : null)
                    : existing.stream()
                    .filter(canvas -> requestedCanvasId.equals(canvas.getId()))
                    .findFirst()
                    .orElse(null);
            return new SessionPreparation(
                    projectId,
                    target == null ? "" : target.getId(),
                    target == null ? "" : target.getTitle(),
                    intent,
                    existing.size(),
                    false,
                    choices);
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
                true,
                List.of(new CanvasChoice(created.getId(), created.getTitle() == null
                        ? title : created.getTitle())));
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
            boolean created,
            List<CanvasChoice> choices
    ) {
    }

    public record CanvasChoice(String canvasId, String title) {
    }
}
