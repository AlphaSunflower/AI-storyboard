package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复合旅程工具：一次调用完成「做一条完整片子」的全流程。
 *
 * <p>分支由 {@link AgentScopeJourneyPlanner} 判定，阈值来自
 * {@code ai.agent.agentscope.journey-direct-video-max-seconds}（默认 15 秒）：
 * <ul>
 *   <li>短分支（时长 ≤ 阈值）：生图 → 视频</li>
 *   <li>长分支（时长 &gt; 阈值，或显式要求分镜）：分镜 → 逐镜图片 → 逐镜视频 → 合成</li>
 * </ul>
 *
 * <p>本工具只做三件事，不做任何实际生成：
 * <ol>
 *   <li>判定分支并给出可枚举的步骤清单（同一清单也用于确认卡片）</li>
 *   <li>复用 {@link MediaToolPreflightService} 的语义做媒体侧前置校验，
 *       并按既有约定消耗 {@link AgentScopeTurnState#beginMedia} 媒体预算</li>
 *   <li>交给 {@link AgentScopeAsyncTaskService#startJourneyTask} 建立父任务，
 *       由父任务调度既有 start 方法创建的子任务</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeJourneyTools {

    public static final String PRODUCE_VIDEO_JOURNEY = "produce_video_journey";

    /** 单次旅程的图片数量上限，避免一次确认触发不可控的批量生成。 */
    private static final int MAX_JOURNEY_IMAGES = 8;

    private final AgentScopeAsyncTaskService asyncTasks;
    private final AgentScopeProperties properties;
    private final MediaToolPreflightService preflight;
    private final SessionAssetService sessionAssets;

    public AgentScopeTool tool() {
        Map<String, Object> propertiesMap = new LinkedHashMap<>();
        propertiesMap.put("prompt", Map.of("type", "string"));
        propertiesMap.put("shot_prompts", array("string"));
        propertiesMap.put("source_asset_ids", array("string"));
        propertiesMap.put("source_urls", array("string"));
        propertiesMap.put("style", Map.of("type", "string"));
        propertiesMap.put("negative_prompt", Map.of("type", "string"));
        propertiesMap.put("target_duration_seconds", Map.of("type", "string"));
        propertiesMap.put("aspect_ratio", Map.of("type", "string"));
        propertiesMap.put("resolution", Map.of("type", "string"));
        propertiesMap.put("model", Map.of("type", "string"));
        propertiesMap.put("size", Map.of("type", "string"));
        propertiesMap.put("quality", Map.of("type", "string"));
        propertiesMap.put("image_count", Map.of("type", "integer"));
        propertiesMap.put("want_storyboard", Map.of("type", "boolean"));
        return new AgentScopeTool(
                PRODUCE_VIDEO_JOURNEY,
                "Produce one complete video from the user's concept in a single call. "
                        + "Short clips (target duration <= the configured limit, default 15s) run "
                        + "generate image then generate video; longer clips or an explicit "
                        + "storyboard request run storyboard, per-shot images, per-shot videos and "
                        + "a final merge. The backend decides the branch, starts one durable parent "
                        + "task and runs the listed steps as child tasks. Returns taskId immediately. "
                        + "Call this once per production request; do not also call generate_image, "
                        + "generate_image_batch or generate_video for the same request.",
                AgentScopeTool.schema(propertiesMap, List.of("prompt")),
                false, false, false,
                (input, param) -> start(input, context(param), toolCallId(param)));
    }

    /**
     * 判定分支、校验收据、消耗媒体预算并建立父任务。
     *
     * <p>字段可见性为 package-private，便于聚焦测试直接断言分支选择、
     * 步骤清单与媒体预算消耗，而不必经过 AgentScope 的反射式工具调用。
     */
    Map<String, Object> start(Map<String, Object> input, AgentScopeRunContext context,
                             String toolCallId) {
        AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
        Map<String, Object> values = input == null ? Map.of() : input;
        String prompt = string(values.get("prompt"));
        List<String> shotPrompts = stringList(values.get("shot_prompts"));
        List<String> sourceUrls = stringList(values.get("source_urls"));
        List<String> sourceAssetIds = new LinkedHashSet<>(stringList(values.get("source_asset_ids")))
                .stream().toList();
        String duration = string(values.get("target_duration_seconds"));
        String aspectRatio = string(values.get("aspect_ratio"));
        String resolution = string(values.get("resolution"));
        String model = string(values.get("model"));
        String size = string(values.get("size"));
        String quality = string(values.get("quality"));
        boolean wantStoryboard = Boolean.TRUE.equals(values.get("want_storyboard"));
        int imageCount = requestedImageCount(values, shotPrompts);
        int shotCount = shotPrompts.isEmpty() ? 0 : shotPrompts.size();

        if (prompt.isBlank() && shotPrompts.isEmpty()) {
            return Map.of(
                    "ok", false,
                    "needsClarification", true,
                    "message", "复合旅程缺少提示词：请先向用户确认要生成什么内容。");
        }

        AgentScopeJourneyPlanner.JourneyPlan plan = AgentScopeJourneyPlanner.plan(
                duration, wantStoryboard, properties.getJourneyDirectVideoMaxSeconds(),
                imageCount, shotCount);

        // 媒体侧前置校验：复用 MediaToolPreflightService 的既有语义（批量阈值/提示词/参考资产），
        // 不新增第二套门禁，也不绕过它。
        if (plan.branch() == AgentScopeJourneyPlanner.Branch.SHORT
                && imageCount > properties.getBatchConfirmThreshold()
                && !MediaToolPreflightService.skipClarification(context.request())) {
            return Map.of(
                    "ok", false,
                    "needsClarification", true,
                    "message", "本次旅程要生成 " + imageCount + " 张图片，超过自动执行阈值（"
                            + properties.getBatchConfirmThreshold()
                            + "）。请先用一句聚焦问题向用户确认数量。");
        }
        if (!sourceAssetIds.isEmpty()) {
            Set<String> existing = sessionAssets.existingAssetIds(
                    conversation.getId(), sourceAssetIds);
            List<String> missing = sourceAssetIds.stream()
                    .filter(id -> !existing.contains(id))
                    .toList();
            if (!missing.isEmpty()) {
                return Map.of(
                        "ok", false,
                        "needsClarification", true,
                        "message", "参考资产不存在：" + String.join(", ", missing)
                                + "。请让用户重新选择或重新上传。");
            }
        }

        // 媒体预算：与 generate_image / generate_image_batch / generate_video 同一份 turn state，
        // 保证复合入口不是 preflight/budget 的旁路。
        String budgetError = consumeMediaBudget(context, plan);
        if (budgetError != null) {
            return Map.of("ok", false, "error", budgetError);
        }

        String taskId = asyncTasks.startJourneyTask(
                conversation, prompt, shotPrompts, sourceUrls, size, quality, model,
                aspectRatio, resolution, duration, imageCount, wantStoryboard, plan,
                toolCallId);

        List<Map<String, Object>> stepMaps = plan.steps().stream()
                .map(step -> Map.<String, Object>of(
                        "order", step.order(),
                        "kind", step.kind(),
                        "title", step.title(),
                        "display", step.display(),
                        "count", step.unitCount()))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("status", "queued");
        out.put("taskId", taskId);
        out.put("taskKind", "journey");
        out.put("branch", AgentScopeJourneyPlanner.branchName(plan.branch()));
        out.put("branchLabel", AgentScopeJourneyPlanner.branchLabel(plan.branch()));
        out.put("branchReason", AgentScopeJourneyPlanner.branchReason(plan));
        out.put("thresholdSeconds", plan.thresholdSeconds());
        out.put("targetDurationSeconds", plan.requestedSeconds());
        out.put("steps", stepMaps);
        out.put("stepSummary", plan.summaryLines());
        out.put("sourceUrls", sourceUrls);
        out.put("message", "复合旅程已受理（" + AgentScopeJourneyPlanner.branchLabel(plan.branch())
                + "），任务 ID：" + taskId);

        if (context.turnState() != null) {
            context.turnState().addTaskCard(taskId,
                    "复合旅程：" + AgentScopeJourneyPlanner.branchLabel(plan.branch()), "journey");
            context.turnState().addText("复合旅程已提交，任务 ID：`" + taskId + "`。\n\n"
                    + plan.summaryLines());
        }
        if (context.support() != null && context.request() != null) {
            context.support().sendEvent(context.request(), "task_accepted", Map.of(
                    "taskId", taskId,
                    "taskKind", "journey",
                    "message", "复合旅程已受理（"
                            + AgentScopeJourneyPlanner.branchLabel(plan.branch()) + "）"));
        }
        return out;
    }

    /** 媒体预算：先占图片额度，再占视频额度；视频失败时回滚图片额度，避免半边占用。 */
    private String consumeMediaBudget(AgentScopeRunContext context,
                                      AgentScopeJourneyPlanner.JourneyPlan plan) {
        AgentScopeTurnState turnState = context.turnState();
        if (turnState == null) return null;
        boolean longBranch = plan.branch() == AgentScopeJourneyPlanner.Branch.LONG;
        if (!turnState.beginMedia("image", 1)) {
            return "本轮已经生成或提交过图片，请直接使用已有结果，不要重复发起复合旅程。";
        }
        if (!longBranch) {
            if (!turnState.beginMedia("video", 1)) {
                turnState.rollbackMediaAttempt("image");
                return "本轮已经创建过视频任务，请直接使用已有 taskId，不要重复发起复合旅程。";
            }
        }
        return null;
    }

    private static int requestedImageCount(Map<String, Object> values, List<String> shotPrompts) {
        if (!shotPrompts.isEmpty()) return shotPrompts.size();
        int requested = positiveInt(values.get("image_count"), 1);
        return Math.max(1, Math.min(MAX_JOURNEY_IMAGES, requested));
    }

    private static AgentScopeRunContext context(io.agentscope.core.tool.ToolCallParam param) {
        return param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

    private static String toolCallId(io.agentscope.core.tool.ToolCallParam param) {
        return param == null || param.getToolUseBlock() == null
                ? "" : param.getToolUseBlock().getId();
    }

    private static Map<String, Object> array(String itemType) {
        return Map.of("type", "array", "items", Map.of("type", itemType));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int positiveInt(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(1, number.intValue());
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = string(item);
            if (!text.isBlank()) out.add(text);
        }
        return out;
    }
}
