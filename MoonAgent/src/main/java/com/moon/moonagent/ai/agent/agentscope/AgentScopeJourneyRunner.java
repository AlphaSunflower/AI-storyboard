package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 复合旅程（{@code produce_video_journey}）的父任务编排器。
 *
 * <p>父任务本身不直接生成媒体，只按 {@link AgentScopeJourneyPlanner} 判定的分支
 * 串行调度子任务：
 * <ul>
 *   <li>短分支：生图子任务 → 视频子任务</li>
 *   <li>长分支：分镜子任务 → 逐镜配图子任务 → 逐镜视频子任务 → 合成</li>
 * </ul>
 *
 * <p>所有子任务都通过 {@link AgentScopeAsyncTaskService} 的既有 start 方法创建
 * （与 {@code generate_image_batch} / {@code generate_storyboard_*} 工具走同一条链），
 * 因此权限、审计、任务轨道、沙箱产物发布与幂等策略全部复用，没有第二条生成通道。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeJourneyRunner {

    /** 单步最长等待时间：分镜/逐镜视频都可能跑很久，超时后按失败收口而不是静默挂住。 */
    private static final Duration STEP_TIMEOUT = Duration.ofHours(3);
    /** 合成步骤由父任务直接发起（{@code VideoMergeService} 自建任务行），等待上限单独给。 */
    private static final Duration MERGE_TIMEOUT = Duration.ofHours(1);

    private final AgentScopeAsyncTaskService asyncTasks;
    private final AgentAsyncTaskMapper taskMapper;
    private final AgentConversationMapper conversationMapper;
    private final StoryboardClient storyboardClient;
    private final StoryboardProductionService productionService;
    private final VideoMergeService videoMergeService;

    /** 父任务入口：由 {@code AgentScopeAsyncTaskService} 投递到既有虚拟线程池。 */
    public void run(String parentTaskId) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null || !"queued".equals(parent.getStatus())) return;
        AgentConversation conversation = conversationMapper.selectById(parent.getConversationId());
        if (conversation == null) {
            asyncTasks.failJourneyTask(parentTaskId, "会话不存在");
            return;
        }
        asyncTasks.noteJourneyStep(parentTaskId, "start", 0, 1, "");
        Map<String, Object> request = readMap(parent.getRequestJson());
        boolean longBranch = "long".equals(string(request.get("branch")));
        try {
            MediaOutcome outcome = longBranch
                    ? runLongBranch(parentTaskId, conversation, request)
                    : runShortBranch(parentTaskId, conversation, request);
            if (asyncTasks.parentCancelled(parentTaskId)) {
                asyncTasks.cancelJourneyTask(parentTaskId, "任务已取消");
                return;
            }
            if (!outcome.ok()) {
                asyncTasks.failJourneyTask(parentTaskId,
                        outcome.error().isBlank() ? "复合旅程执行失败" : outcome.error());
                return;
            }
            asyncTasks.completeJourneyTask(parentTaskId, outcome.content(),
                    outcome.assetIds(), outcome.urls());
        } catch (RuntimeException error) {
            log.warn("Journey task failed: parentTaskId={}, error={}", parentTaskId, error.getMessage());
            if (asyncTasks.parentCancelled(parentTaskId)) {
                asyncTasks.cancelJourneyTask(parentTaskId, "任务已取消");
            } else {
                asyncTasks.failJourneyTask(parentTaskId,
                        error.getMessage() == null ? "复合旅程执行失败" : error.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 短分支：生图 → 视频
    // ------------------------------------------------------------------

    private MediaOutcome runShortBranch(String parentTaskId, AgentConversation conversation,
                                        Map<String, Object> request) {
        List<String> prompts = stringList(request.get("prompts"));
        int imageCount = Math.max(1, integer(request.get("imageCount"), 1));
        asyncTasks.noteJourneyStep(parentTaskId, "image", 0, 2, "");
        String imageChildId = asyncTasks.startJourneyImageChild(
                parentTaskId, 1, "image", conversation,
                string(request.get("prompt")), prompts, stringList(request.get("sourceUrls")),
                string(request.get("size")), string(request.get("quality")), imageCount,
                false, "", "", "", "", "");
        asyncTasks.noteJourneyStep(parentTaskId, "image", 0, 2, imageChildId);
        if (!asyncTasks.releaseChildTask(imageChildId)) {
            return MediaOutcome.failure("生图子任务无法启动");
        }
        AgentAsyncTask imageChild = asyncTasks.awaitChildTask(parentTaskId, imageChildId, STEP_TIMEOUT);
        if (imageChild == null) return MediaOutcome.failure("生图子任务超时未完成");
        if (!"completed".equals(imageChild.getStatus())) {
            return MediaOutcome.failure(failureText(imageChild, "生图子任务未成功"));
        }
        List<String> imageUrls = readStringList(imageChild.getResultUrlsJson());
        List<String> imageAssetIds = readStringList(imageChild.getAssetIdsJson());
        if (imageUrls.isEmpty()) return MediaOutcome.failure("生图子任务没有产出图片");
        if (asyncTasks.parentCancelled(parentTaskId)) return MediaOutcome.cancelled();

        asyncTasks.noteJourneyStep(parentTaskId, "video", 1, 2, "");
        String videoChildId = asyncTasks.startJourneyVideoChild(
                parentTaskId, 2, conversation,
                string(request.get("prompt")), imageUrls,
                string(request.get("model")), string(request.get("resolution")),
                string(request.get("aspectRatio")), string(request.get("duration")), "");
        asyncTasks.noteJourneyStep(parentTaskId, "video", 1, 2, videoChildId);
        if (!asyncTasks.releaseChildTask(videoChildId)) {
            return MediaOutcome.failure("视频子任务无法启动");
        }
        AgentAsyncTask videoChild = asyncTasks.awaitChildTask(parentTaskId, videoChildId, STEP_TIMEOUT);
        if (videoChild == null) return MediaOutcome.failure("视频子任务超时未完成");
        if (!"completed".equals(videoChild.getStatus())) {
            return MediaOutcome.failure(failureText(videoChild, "视频子任务未成功"));
        }
        List<String> urls = new ArrayList<>(new LinkedHashSet<>(readStringList(videoChild.getResultUrlsJson())));
        List<String> assetIds = new ArrayList<>(new LinkedHashSet<>(imageAssetIds));
        assetIds.addAll(readStringList(videoChild.getAssetIdsJson()));
        return MediaOutcome.success(
                "复合旅程（生图 → 视频）已完成：本次生成 " + imageUrls.size()
                        + " 张图片并输出 1 个视频。",
                assetIds, urls);
    }

    // ------------------------------------------------------------------
    // 长分支：分镜 → 逐镜图片 → 逐镜视频 → 合成
    // ------------------------------------------------------------------

    private MediaOutcome runLongBranch(String parentTaskId, AgentConversation conversation,
                                       Map<String, Object> request) {
        asyncTasks.noteJourneyStep(parentTaskId, "storyboard", 0, 4, "");
        String runId = ensureStoryboard(parentTaskId, conversation, request);
        if (runId.isBlank()) return MediaOutcome.failure("分镜步骤未产出版本号");
        if (asyncTasks.parentCancelled(parentTaskId)) return MediaOutcome.cancelled();

        List<Scene> scenes = targetScenes(conversation, asyncTasks.journeyData(parentTaskId));
        if (scenes.isEmpty()) return MediaOutcome.failure("分镜步骤没有写入任何镜头");

        // 逐镜配图
        asyncTasks.noteJourneyStep(parentTaskId, "storyboard_image", 1, 4, "");
        List<AgentScopeAsyncTaskService.StoryboardImageTarget> targets = scenes.stream()
                .map(this::imageTarget)
                .toList();
        String imageChildId = asyncTasks.startJourneyStoryboardImageChild(
                parentTaskId, 2, conversation, targets, false, "");
        asyncTasks.noteJourneyStep(parentTaskId, "storyboard_image", 1, 4, imageChildId);
        if (!asyncTasks.releaseChildTask(imageChildId)) {
            return MediaOutcome.failure("逐镜配图子任务无法启动");
        }
        AgentAsyncTask imageChild = asyncTasks.awaitChildTask(parentTaskId, imageChildId, STEP_TIMEOUT);
        if (imageChild == null) return MediaOutcome.failure("逐镜配图子任务超时未完成");
        if (!"completed".equals(imageChild.getStatus())) {
            return MediaOutcome.failure(failureText(imageChild, "逐镜配图子任务未成功"));
        }
        if (asyncTasks.parentCancelled(parentTaskId)) return MediaOutcome.cancelled();

        // 逐镜视频
        asyncTasks.noteJourneyStep(parentTaskId, "storyboard_video", 2, 4, "");
        List<String> sceneIds = scenes.stream().map(Scene::getId).toList();
        String videoChildId = asyncTasks.startJourneyStoryboardVideoChild(
                parentTaskId, 3, conversation, sceneIds, false, "");
        asyncTasks.noteJourneyStep(parentTaskId, "storyboard_video", 2, 4, videoChildId);
        if (!asyncTasks.releaseChildTask(videoChildId)) {
            return MediaOutcome.failure("逐镜视频子任务无法启动");
        }
        AgentAsyncTask videoChild = asyncTasks.awaitChildTask(parentTaskId, videoChildId, STEP_TIMEOUT);
        if (videoChild == null) return MediaOutcome.failure("逐镜视频子任务超时未完成");
        if (!"completed".equals(videoChild.getStatus())) {
            return MediaOutcome.failure(failureText(videoChild, "逐镜视频子任务未成功"));
        }
        if (asyncTasks.parentCancelled(parentTaskId)) return MediaOutcome.cancelled();

        // 合成：VideoMergeService 自建任务行（非本父任务的子行），由父任务直接等待
        asyncTasks.noteJourneyStep(parentTaskId, "merge", 3, 4, "");
        List<String> clipUrls = freshScenes(conversation).stream()
                .filter(scene -> sceneIds.contains(scene.getId()))
                .sorted(Comparator.comparingInt(scene -> scene.getSceneNumber() == null
                        ? 0 : scene.getSceneNumber()))
                .map(Scene::getVideoUrl)
                .filter(url -> url != null && !url.isBlank())
                .toList();
        if (clipUrls.isEmpty()) return MediaOutcome.failure("没有可合成的镜头视频");
        int expectedSeconds = clipUrls.isEmpty() ? 0 : totalSeconds(sceneIds, conversation);
        String mergeTaskId = videoMergeService.start(
                conversation.getUserId(), conversation.getId(), conversation.getProjectId(),
                clipUrls, expectedSeconds > 0 ? expectedSeconds : null);
        asyncTasks.noteJourneyStep(parentTaskId, "merge", 3, 4, mergeTaskId);
        AgentAsyncTask merged = asyncTasks.awaitChildTask(parentTaskId, mergeTaskId, MERGE_TIMEOUT);
        if (merged == null) return MediaOutcome.failure("合成任务超时未完成");
        if (!"completed".equals(merged.getStatus())) {
            return MediaOutcome.failure(failureText(merged, "合成任务未成功"));
        }
        List<String> urls = new ArrayList<>(new LinkedHashSet<>(readStringList(merged.getResultUrlsJson())));
        if (urls.isEmpty()) urls.addAll(clipUrls);
        List<String> assetIds = new ArrayList<>(new LinkedHashSet<>(readStringList(merged.getAssetIdsJson())));
        assetIds.addAll(readStringList(imageChild.getAssetIdsJson()));
        return MediaOutcome.success(
                "复合旅程（分镜 → 逐镜图片 → 逐镜视频 → 合成）已完成：共 "
                        + sceneIds.size() + " 个镜头，已合成成片。",
                assetIds, urls);
    }

    /**
     * 分镜步骤：直接复用既有 {@code startStoryboardTask}（与工具同一入口），
     * 完成后把 runId 与写入的场景 ID 记到父任务上，供后续步骤定位本次产物。
     */
    private String ensureStoryboard(String parentTaskId, AgentConversation conversation,
                                    Map<String, Object> request) {
        Map<String, Object> data = asyncTasks.journeyData(parentTaskId);
        String existingRunId = string(data.get("storyboardRunId"));
        if (!existingRunId.isBlank()) return existingRunId;
        String childTaskId = asyncTasks.startStoryboardChildTask(
                parentTaskId, 1, conversation,
                string(request.get("prompt")),
                string(request.get("size")),
                string(request.get("duration")),
                stringList(request.get("sourceUrls")));
        asyncTasks.noteJourneyStep(parentTaskId, "storyboard", 0, 4, childTaskId);
        if (!asyncTasks.releaseChildTask(childTaskId)) {
            return "";
        }
        AgentAsyncTask child = asyncTasks.awaitChildTask(parentTaskId, childTaskId, STEP_TIMEOUT);
        if (child == null || !"completed".equals(child.getStatus())) return "";
        String runId = string(readMap(child.getResultJson()).get("runId"));
        asyncTasks.noteJourneyData(parentTaskId, "storyboardRunId", runId);
        // 只有本次 run 真正写入项目的镜头才属于这次旅程，避免把历史分镜一起重跑。
        List<String> published = runId.isBlank()
                ? List.of() : productionService.publishedSceneIds(runId);
        asyncTasks.noteJourneyData(parentTaskId, "storyboardSceneIds", published);
        return runId;
    }

    /** 本次旅程写入的镜头：优先用父任务记录的发布结果，缺失时回退到项目分镜里的新镜头。 */
    private List<Scene> targetScenes(AgentConversation conversation, Map<String, Object> journeyData) {
        List<String> published = stringList(journeyData.get("storyboardSceneIds"));
        List<Scene> all = freshScenes(conversation);
        if (published.isEmpty()) return all;
        List<Scene> scoped = all.stream()
                .filter(scene -> published.contains(scene.getId()))
                .toList();
        return scoped.isEmpty() ? all : scoped;
    }

    private List<Scene> freshScenes(AgentConversation conversation) {
        try {
            List<Scene> scenes = storyboardClient
                    .getProjectScenesFresh(conversation.getProjectId());
            if (scenes == null) return List.of();
            return scenes.stream()
                    .sorted(Comparator.comparingInt(scene -> scene.getSceneNumber() == null
                            ? 0 : scene.getSceneNumber()))
                    .toList();
        } catch (RuntimeException error) {
            log.warn("Journey scene lookup failed: projectId={}, error={}",
                    conversation.getProjectId(), error.getMessage());
            return List.of();
        }
    }

    private AgentScopeAsyncTaskService.StoryboardImageTarget imageTarget(Scene scene) {
        String prompt = nonBlank(scene.getImagePrompt(),
                nonBlank(scene.getScriptContent(), "分镜" + scene.getSceneNumber()));
        return new AgentScopeAsyncTaskService.StoryboardImageTarget(
                "分镜" + scene.getSceneNumber(), scene.getId(), scene.getId(), prompt, List.of());
    }

    private int totalSeconds(List<String> sceneIds, AgentConversation conversation) {
        return freshScenes(conversation).stream()
                .filter(scene -> sceneIds.contains(scene.getId()))
                .map(Scene::getDuration)
                .filter(value -> value != null && value > 0)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static String failureText(AgentAsyncTask task, String fallback) {
        if (task == null) return fallback;
        String error = string(task.getError());
        if (!error.isBlank()) return error;
        String summary = string(task.getResultSummary());
        return summary.isBlank() ? fallback : summary;
    }

    /** 一步的产出：成功/失败/取消三态。 */
    private record MediaOutcome(boolean ok, String error, String content,
                                List<String> assetIds, List<String> urls) {

        static MediaOutcome success(String content, List<String> assetIds, List<String> urls) {
            return new MediaOutcome(true, "", content,
                    assetIds == null ? List.of() : List.copyOf(assetIds),
                    urls == null ? List.of() : List.copyOf(urls));
        }

        static MediaOutcome failure(String error) {
            return new MediaOutcome(false, error == null ? "" : error, "", List.of(), List.of());
        }

        static MediaOutcome cancelled() {
            return new MediaOutcome(false, "任务已取消", "", List.of(), List.of());
        }
    }

    private static String nonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .toList();
    }

    private static List<String> readStringList(String json) {
        return stringList(AgentScopeAsyncTaskService.parseJsonList(json));
    }

    private static Map<String, Object> readMap(String json) {
        return AgentScopeAsyncTaskService.parseJsonMap(json);
    }
}
