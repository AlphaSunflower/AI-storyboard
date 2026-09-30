package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.agent.creative.TimelineManifest;
import com.moon.moonagent.ai.agent.creative.TimelineManifestService;
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
    private final StoryboardClient storyboardClient;
    private final TimelineManifestService timelineManifests;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                MERGE_STORYBOARD_VIDEOS,
                "Merge approved storyboard video clips into one final video. "
                        + "Preserve the source_urls order and validate the expected duration.",
                AgentScopeTool.schema(Map.of(
                        "manifest_id", Map.of("type", "string"),
                        "source_urls", array("string"),
                        "expected_duration_seconds", Map.of("type", "integer")),
                        List.of()),
                false, false, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    List<String> urls = stringList(input.get("source_urls"));
                    Integer expected = integer(input.get("expected_duration_seconds"));
                    String manifestId = string(input.get("manifest_id"));
                    if (!manifestId.isBlank()) {
                        TimelineManifest manifest = timelineManifests.get(
                                conversation.getProjectId(), conversation.getId(), manifestId);
                        if (manifest == null) {
                            throw new IllegalArgumentException("时间线不存在或不属于当前会话");
                        }
                        urls = manifest.sourceUrls();
                        if (expected == null || expected <= 0) {
                            expected = manifest.totalDurationSeconds();
                        }
                    }
                    if (urls.isEmpty()) {
                        List<Scene> scenes = storyboardClient
                                .getProjectScenesFresh(conversation.getProjectId()).stream()
                                .filter(scene -> scene.getVideoUrl() != null
                                        && !scene.getVideoUrl().isBlank())
                                .sorted(java.util.Comparator.comparingInt(Scene::getSceneNumber))
                                .toList();
                        urls = scenes.stream().map(Scene::getVideoUrl).toList();
                        if (expected == null || expected <= 0) {
                            expected = scenes.stream()
                                    .map(Scene::getDuration)
                                    .filter(value -> value != null && value > 0)
                                    .mapToInt(Integer::intValue)
                                    .sum();
                        }
                    }
                    if (urls.isEmpty()) {
                        throw new IllegalStateException("时间线中没有可用视频片段");
                    }
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

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
