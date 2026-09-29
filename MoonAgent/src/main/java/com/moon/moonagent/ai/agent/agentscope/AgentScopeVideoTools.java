package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.storyboard.VideoGenerationPolicy;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.entity.AgentConversation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class AgentScopeVideoTools {

    public static final String GENERATE_VIDEO = "generate_video";

    private final AgentRuntimeSupport support;
    private final SessionAssetService sessionAssets;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                GENERATE_VIDEO,
                "Create an asynchronous video task. For an explicit video request, call this "
                        + "immediately with one complete prompt; return the task id without waiting.",
                AgentScopeTool.schema(Map.of(
                        "prompt", Map.of("type", "string"),
                        "reference_asset_ids", Map.of("type", "array",
                                "items", Map.of("type", "string")),
                        "reference_urls", Map.of("type", "array",
                                "items", Map.of("type", "string")),
                        "resolution", Map.of("type", "string"),
                        "aspect_ratio", Map.of("type", "string"),
                        "duration", Map.of("type", "string")), List.of("prompt")),
                false, false, false,
                (input, param) -> generateVideo(
                        string(input.get("prompt")),
                        stringList(input.get("reference_asset_ids")),
                        stringList(input.get("reference_urls")),
                        string(input.get("resolution")),
                        string(input.get("aspect_ratio")),
                        string(input.get("duration")),
                        context(param)));
    }

    public Map<String, Object> generateVideo(
            String prompt,
            List<String> referenceAssetIds,
            List<String> referenceUrls,
            String resolution,
            String aspectRatio,
            String duration,
            AgentScopeRunContext context) {
        return generateVideo(prompt, referenceAssetIds, referenceUrls, resolution,
                aspectRatio, duration, context, false);
    }

    Map<String, Object> generateVideoForStoryboard(
            String prompt,
            List<String> referenceAssetIds,
            List<String> referenceUrls,
            String resolution,
            String aspectRatio,
            String duration,
            AgentScopeRunContext context) {
        return generateVideo(prompt, referenceAssetIds, referenceUrls, resolution,
                aspectRatio, duration, context, true);
    }

    Map<String, Object> generateVideoForStoryboard(
            String prompt,
            List<ReferenceImage> referenceImages,
            String resolution,
            String aspectRatio,
            String duration,
            AgentScopeRunContext context) {
        return generateVideoForStoryboard(prompt, referenceImages, resolution,
                aspectRatio, duration, null, context);
    }

    Map<String, Object> generateVideoForStoryboard(
            String prompt,
            List<ReferenceImage> referenceImages,
            String resolution,
            String aspectRatio,
            String duration,
            String sceneId,
            AgentScopeRunContext context) {
        return startVideo(prompt, List.of(), referenceImages, resolution,
                aspectRatio, duration, sceneId, context, true);
    }

    private Map<String, Object> generateVideo(
            String prompt,
            List<String> referenceAssetIds,
            List<String> referenceUrls,
            String resolution,
            String aspectRatio,
            String duration,
            AgentScopeRunContext context,
            boolean storyboardScoped) {
        List<String> references = new java.util.ArrayList<>(
                sessionAssets.urlsByIds(AgentScopeSessionTools.requireConversation(context).getId(),
                        referenceAssetIds));
        if (referenceUrls != null) {
            referenceUrls.stream()
                    .filter(url -> url != null && !url.isBlank())
                    .forEach(url -> {
                        if (!references.contains(url)) references.add(url);
                    });
        }
        return startVideo(prompt, referenceAssetIds,
                references.stream().map(ReferenceImage::ofReference).toList(),
                resolution, aspectRatio, duration, null, context, storyboardScoped);
    }

    private Map<String, Object> startVideo(
            String prompt,
            List<String> referenceAssetIds,
            List<ReferenceImage> referenceImages,
            String resolution,
            String aspectRatio,
            String duration,
            String sceneId,
            AgentScopeRunContext context,
            boolean storyboardScoped) {
        AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
        if (!storyboardScoped && context.turnState() != null
                && context.turnState().isStoryboardRequested()) {
            throw new IllegalStateException(
                    "本轮已进入分镜制作流程，不能改用通用视频生成；请先完成正式分镜列表，再按 shot_id 生成镜头视频。");
        }
        String originalRequest = context.request() == null ? null : context.request().getContent();
        if (storyboardScoped) {
            VideoGenerationPolicy.requireStoryboardShotAllowed(duration);
        } else {
            VideoGenerationPolicy.requireDirectAllowed(duration, originalRequest);
        }
        if (context.turnState() != null && context.turnState().isFollowUpVideoPlanned()) {
            throw new IllegalStateException(
                    "批量图片完成后会自动创建首尾帧视频，请等待图片任务完成，不要单独重复创建。");
        }
        if (context.turnState() != null && context.turnState().hasStartedMedia("video")) {
            throw new IllegalStateException(
                    "本轮已经创建过视频任务，请直接使用已有 taskId 回复，不要重复调用。");
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (resolution != null && !resolution.isBlank()) params.put("resolution", resolution);
        List<ReferenceImage> roleImages = referenceImages == null
                ? List.of() : List.copyOf(referenceImages);
        String taskId = storyboardScoped
                ? support.startStoryboardShotVideoGenerationAsyncWithRole(
                        context.request(), sceneId, prompt, duration, aspectRatio, roleImages,
                        java.util.List.of(), params)
                : support.startVideoGenerationAsyncWithRole(
                        context.request(), prompt, duration, aspectRatio, roleImages,
                        java.util.List.of(), params);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("视频任务创建失败");
        }
        if (context.turnState() != null) {
            context.turnState().markMediaStarted("video");
            String message = "视频任务已提交，任务 ID：`" + taskId + "`，正在生成中。";
            context.turnState().addText(message);
            context.turnState().addTaskCard(
                    taskId,
                    storyboardScoped ? "生成分镜视频" : "生成视频",
                    "video");
            if (context.request() != null && context.support() != null) {
                context.support().sendMessage(context.request(), message + "\n\n");
            }
        }
        return Map.of(
                "ok", true,
                "mediaType", "video",
                "status", "queued",
                "taskId", taskId,
                "taskKind", "video",
                "referencedAssetIds", referenceAssetIds == null ? List.of() : referenceAssetIds);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf).toList();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static AgentScopeRunContext context(io.agentscope.core.tool.ToolCallParam param) {
        return param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

}
