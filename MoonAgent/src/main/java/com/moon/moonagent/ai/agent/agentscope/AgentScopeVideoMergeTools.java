package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeVideoMergeTools {

    public static final String MERGE_STORYBOARD_VIDEOS = "merge_storyboard_videos";

    private final VideoMergeService mergeService;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                MERGE_STORYBOARD_VIDEOS,
                "Merge approved storyboard video clips into one final video. "
                        + "Preserve the source_urls order and validate the expected duration.",
                AgentScopeTool.schema(Map.of(
                        "source_urls", array("string"),
                        "expected_duration_seconds", Map.of("type", "integer")),
                        List.of("source_urls")),
                false, false, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    List<String> urls = stringList(input.get("source_urls"));
                    Integer expected = integer(input.get("expected_duration_seconds"));
                    String taskId = mergeService.start(
                            conversation.getUserId(), conversation.getId(),
                            conversation.getProjectId(), urls, expected);
                    if (context != null && context.turnState() != null) {
                        context.turnState().addTaskCard(taskId, "合并完整分镜视频", "long-tool");
                        context.turnState().addText("完整成片合并任务已受理。");
                    }
                    return Map.of(
                            "ok", true,
                            "status", "queued",
                            "taskId", taskId,
                            "taskKind", "long-tool",
                            "message", "视频合并任务已受理");
                });
    }

    private static Map<String, Object> array(String itemType) {
        return Map.of("type", "array", "items", Map.of("type", itemType));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
