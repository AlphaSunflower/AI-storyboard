package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.entity.AgentConversation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class AgentScopeImageTools {

    public static final String GENERATE_IMAGE = "generate_image";
    public static final String GENERATE_IMAGE_BATCH = "generate_image_batch";

    private final AgentGenerationService generationService;
    private final SessionAssetService sessionAssets;
    private final AgentScopeAssetGroupService assetGroups;
    private final AgentScopeAsyncTaskService asyncTasks;
    private final AgentScopeProperties properties;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                GENERATE_IMAGE,
                "Generate or edit one image. For an explicit image request, call this immediately "
                        + "with one complete prompt; do not create a plan or options first.",
                AgentScopeTool.schema(Map.of(
                        "prompt", Map.of("type", "string"),
                        "source_asset_ids", Map.of("type", "array",
                                "items", Map.of("type", "string")),
                        "source_urls", Map.of("type", "array",
                                "items", Map.of("type", "string")),
                        "size", Map.of("type", "string"),
                        "quality", Map.of("type", "string"),
                        "n", Map.of("type", "string")), List.of("prompt")),
                false, false, false,
                (input, param) -> generateImage(
                        string(input.get("prompt")),
                        stringList(input.get("source_asset_ids")),
                        stringList(input.get("source_urls")),
                        string(input.get("size")),
                        string(input.get("quality")),
                        string(input.get("n")),
                        context(param)));
    }

    public AgentScopeTool batchTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string"));
        properties.put("prompts", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("source_asset_ids",
                Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("source_urls",
                Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("size", Map.of("type", "string"));
        properties.put("quality", Map.of("type", "string"));
        properties.put("n", Map.of("type", "string"));
        properties.put("follow_up_video", Map.of("type", "boolean"));
        properties.put("video_prompt", Map.of("type", "string"));
        properties.put("video_duration", Map.of("type", "string"));
        properties.put("video_aspect_ratio", Map.of("type", "string"));
        properties.put("video_resolution", Map.of("type", "string"));
        return new AgentScopeTool(
                GENERATE_IMAGE_BATCH,
                "Create an asynchronous batch image task. Use for multiple images. The tool "
                        + "returns taskId immediately and the final images are appended when ready. "
                        + "Pass one prompt per requested image in prompts[]. Set follow_up_video "
                        + "to true only when the user explicitly asks for a video.",
                AgentScopeTool.schema(properties, List.of("prompt")),
                false, false, false,
                (input, param) -> generateImageBatch(
                        string(input.get("prompt")),
                        stringList(input.get("prompts")),
                        stringList(input.get("source_asset_ids")),
                        stringList(input.get("source_urls")),
                        string(input.get("size")),
                        string(input.get("quality")),
                        string(input.get("n")),
                        Boolean.TRUE.equals(input.get("follow_up_video")),
                        string(input.get("video_prompt")),
                        string(input.get("video_duration")),
                        string(input.get("video_aspect_ratio")),
                        string(input.get("video_resolution")),
                        context(param)));
    }

    public Map<String, Object> generateImage(
            String prompt,
            List<String> sourceAssetIds,
            List<String> sourceUrls,
            String size,
            String quality,
            String n,
            AgentScopeRunContext context) {
        return generateImage(prompt, sourceAssetIds, sourceUrls, size, quality, n, context, false);
    }

    Map<String, Object> generateImageForStoryboard(
            String prompt,
            List<String> sourceAssetIds,
            List<String> sourceUrls,
            String size,
            String quality,
            String n,
            String sceneId,
            AgentScopeRunContext context) {
        return generateImage(prompt, sourceAssetIds, sourceUrls, size, quality, n,
                sceneId, context, true);
    }

    private Map<String, Object> generateImage(
            String prompt,
            List<String> sourceAssetIds,
            List<String> sourceUrls,
            String size,
            String quality,
            String n,
            AgentScopeRunContext context,
            boolean storyboardScoped) {
        return generateImage(prompt, sourceAssetIds, sourceUrls, size, quality, n,
                null, context, storyboardScoped);
    }

    private Map<String, Object> generateImage(
            String prompt,
            List<String> sourceAssetIds,
            List<String> sourceUrls,
            String size,
            String quality,
            String n,
            String sceneId,
            AgentScopeRunContext context,
            boolean storyboardScoped) {
        AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
        if (!storyboardScoped && context.turnState() != null
                && context.turnState().isStoryboardRequested()) {
            throw new IllegalStateException(
                    "本轮已进入分镜制作流程，不能改用通用生图；请先完成正式分镜列表，再按 shot_id 生成镜头图片。");
        }
        if (context.turnState() != null && context.turnState().hasStartedMedia("image")) {
            throw new IllegalStateException(
                    "本轮已经成功生成或提交过图片，请直接使用已有结果回复，不要重复调用。");
        }
        List<String> sources = mergedSources(
                conversation, sourceAssetIds, sourceUrls, context);
        int count = AgentScopeSessionTools.positiveInt(n, 1);
        if (count > 1) {
            return startBatchImage(
                    conversation, prompt, List.of(), sources, size, quality, count, context);
        }
        Map<String, Object> result = generationService.generateImage(
                conversation, sceneId, prompt, null, size, quality,
                1,
                sources.isEmpty() ? null : "edit", sources, null);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("ok", true);
        out.put("referencedAssetIds", sourceAssetIds == null ? List.of() : sourceAssetIds);
        List<?> urls = result.get("imageUrls") instanceof List<?> list ? list : List.of();
        if (context.turnState() != null) {
            for (Object url : urls) {
                String imageUrl = String.valueOf(url);
                context.turnState().addImage(imageUrl, "生成图片");
                if (context.request() != null && context.support() != null) {
                    context.support().sendMessage(
                            context.request(), "![生成图片](" + imageUrl + ")\n\n");
                }
            }
            context.turnState().markMediaStarted("image");
        }
        return out;
    }

    public Map<String, Object> generateImageBatch(
            String prompt,
            List<String> prompts,
            List<String> sourceAssetIds,
            List<String> sourceUrls,
            String size,
            String quality,
            String n,
            boolean followUpVideo,
            String videoPrompt,
            String videoDuration,
            String videoAspectRatio,
            String videoResolution,
            AgentScopeRunContext context) {
        AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
        if (context.turnState() != null && context.turnState().isStoryboardRequested()) {
            throw new IllegalStateException(
                    "本轮已进入分镜制作流程，不能改用通用批量生图；请先完成正式分镜列表，再按 shot_id 生成镜头图片。");
        }
        if (context.turnState() != null && context.turnState().hasStartedMedia("image")) {
            throw new IllegalStateException(
                    "本轮已经成功生成或提交过图片，请直接使用已有结果，不要重复调用。");
        }
        List<String> sources = mergedSources(
                conversation, sourceAssetIds, sourceUrls, context);
        List<String> requestedPrompts = prompts == null ? List.of() : prompts.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        int count = requestedPrompts.isEmpty()
                ? Math.max(2, AgentScopeSessionTools.positiveInt(n, 2))
                : requestedPrompts.size();
        return startBatchImage(
                conversation, prompt, requestedPrompts, sources, size, quality, count,
                followUpVideo, videoPrompt, videoDuration, videoAspectRatio, videoResolution,
                context);
    }

    private Map<String, Object> startBatchImage(
            AgentConversation conversation,
            String prompt,
            List<String> prompts,
            List<String> sources,
            String size,
            String quality,
            int count,
            AgentScopeRunContext context) {
        return startBatchImage(
                conversation, prompt, prompts, sources, size, quality, count,
                false, "", "", "", "", context);
    }

    private Map<String, Object> startBatchImage(
            AgentConversation conversation,
            String prompt,
            List<String> prompts,
            List<String> sources,
            String size,
            String quality,
            int count,
            boolean followUpVideo,
            String videoPrompt,
            String videoDuration,
            String videoAspectRatio,
            String videoResolution,
            AgentScopeRunContext context) {
        if (!confirmationBypassed(context)
                && properties.isDirectMediaGeneration()
                && count > properties.getBatchConfirmThreshold()) {
            return Map.of(
                    "ok", false,
                    "needsConfirmation", true,
                    "error", "批量生图数量为 " + count + "，超过自动执行阈值。"
                            + "请先用一句聚焦问题向用户确认数量。");
        }
        if (followUpVideo && context != null && context.turnState() != null
                && !context.turnState().tryPlanFollowUpVideo()) {
            return Map.of(
                    "ok", false,
                    "alreadyPlanned", true,
                    "error", "首尾帧视频已由当前批量任务链式创建，请直接使用已有任务，不要重复调用。");
        }
        String taskId = asyncTasks.startBatchImageTask(
                conversation, prompt, prompts, sources, size, quality, count,
                followUpVideo, videoPrompt, videoDuration, videoAspectRatio, videoResolution,
                context == null || context.request() == null
                        ? "" : context.request().getCheckpointAction());
        if (context != null && context.support() != null && context.request() != null) {
            context.support().sendEvent(context.request(), "task_accepted", Map.of(
                    "taskId", taskId,
                    "taskKind", "image-batch",
                    "message", "批量生图任务已受理，正在生成 " + count + " 张图片"));
            context.support().sendMessage(context.request(),
                    "批量生图任务已提交，任务 ID：`" + taskId + "`，正在生成中。\n\n");
        }
        if (context != null && context.turnState() != null) {
            context.turnState().addText("批量生图任务已提交，任务 ID：`" + taskId + "`，正在生成中。");
            context.turnState().addTaskCard(
                    taskId,
                    count > 1 ? "批量生成 " + count + " 张图片" : "生成图片",
                    "image");
            context.turnState().markMediaStarted("image");
        }
        return Map.of(
                "ok", true,
                "mediaType", "image",
                "status", "queued",
                "taskId", taskId,
                "taskKind", "image-batch",
                "count", count,
                "referencedAssetIds", sources);
    }

    private List<String> mergedSources(
            AgentConversation conversation,
            List<String> sourceAssetIds,
            List<String> sourceUrls,
            AgentScopeRunContext context) {
        List<String> sources = new java.util.ArrayList<>(
                sessionAssets.urlsByIds(conversation.getId(), sourceAssetIds));
        assetGroups.imageContentUrls(conversation).forEach(url -> addUrl(sources, url));
        if (context != null && context.request() != null) {
            List<String> turnAssetUrls = sessionAssets.urlsByIds(
                    conversation.getId(), context.request().getAssetIds());
            turnAssetUrls.forEach(url -> addUrl(sources, url));
            if (context.request().getPicUrls() != null) {
                context.request().getPicUrls().forEach(url -> addUrl(sources, url));
            }
        }
        if (sourceUrls != null) sourceUrls.forEach(url -> addUrl(sources, url));
        return sources;
    }

    private static void addUrl(List<String> target, String url) {
        if (url == null || url.isBlank()) return;
        String key = canonicalMediaKey(url);
        boolean exists = target.stream().anyMatch(existing -> key.equals(canonicalMediaKey(existing)));
        if (!exists) target.add(url);
    }

    private static String canonicalMediaKey(String url) {
        if (url == null || url.isBlank()) return "";
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        return path.contains("/api/library/media/") ? path : url;
    }

    @SuppressWarnings("unchecked")
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

    private static boolean confirmationBypassed(AgentScopeRunContext context) {
        return context != null
                && MediaToolPreflightService.skipClarification(context.request());
    }

}
