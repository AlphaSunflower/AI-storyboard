package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotReferenceResolution;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardCommitMode;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardGenerationParams;
import com.moon.moonagent.ai.storyboard.production.StoryboardPromptAssembly;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AgentScopeStoryboardTools {

    public static final String START_STORYBOARD = "start_storyboard_production";
    public static final String REVISE_STORYBOARD = "revise_storyboard_production";
    public static final String APPROVE_STORYBOARD = "approve_storyboard_production";
    public static final String GENERATE_STORYBOARD_IMAGE = "generate_storyboard_image";
    public static final String GENERATE_STORYBOARD_VIDEO = "generate_storyboard_video";
    public static final String GENERATE_STORYBOARD_VIDEOS = "generate_storyboard_videos";
    public static final String UPSERT_STORYBOARD_SHOT = "upsert_storyboard_shot";
    public static final String LIST_STORYBOARD_DRAFT = "list_storyboard_draft";
    public static final String LIST_PROJECT_STORYBOARD = "list_project_storyboard";
    public static final String READ_PROJECT_SCENE = "read_project_scene";
    public static final String UPDATE_PROJECT_STORYBOARD_SHOT = "update_project_storyboard_shot";
    public static final String DELETE_STORYBOARD_DRAFT_SHOT = "delete_storyboard_draft_shot";
    public static final String REORDER_STORYBOARD_DRAFT = "reorder_storyboard_draft";
    public static final String DELETE_PROJECT_STORYBOARD_SHOT = "delete_project_storyboard_shot";
    public static final String CLEAR_PROJECT_STORYBOARD = "clear_project_storyboard";
    public static final String GENERATE_STORYBOARD_IMAGES = "generate_storyboard_images";

    /** 模型漏传 asset_ids 时自动带入的会话素材上限（与 list_session_assets 的默认上限同量级）。 */
    private static final int AUTO_ASSET_LIMIT = 12;

    private final StoryboardProductionService productionService;
    private final AgentScopeAsyncTaskService asyncTasks;
    private final SessionAssetService sessionAssets;
    private final AgentScopeImageTools imageTools;
    private final AgentScopeVideoTools videoTools;
    private final StoryboardClient storyboardClient;

    @Value("${ai.storyboard.production-v2.enabled:true}")
    private boolean enabled = true;

    /**
     * 生成参数解析（可选依赖：单测用 6 参构造器直接 new，不引入网关）。
     * 为空时按「不带任何参数」处理，行为退化为改造前。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient StoryboardParamResolver paramResolver;

    public List<AgentScopeTool> tools() {
        if (!enabled) return List.of();
        return List.of(
                startTool(),
                reviseTool(),
                approveTool(),
                imageTool(),
                videoTool(),
                batchVideoTool(),
                listDraftTool(),
                listProjectTool(),
                readProjectSceneTool(),
                updateProjectShotTool(),
                upsertShotTool(),
                deleteDraftShotTool(),
                reorderDraftTool(),
                deleteProjectShotTool(),
                clearProjectTool(),
                batchImageTool());
    }

    public AgentScopeTool upsertShotTool() {
        return new AgentScopeTool(
                UPSERT_STORYBOARD_SHOT,
                "Add or update exactly one draft shot. Works in both the thin outline stage "
                        + "(PLOT_REVIEW: only action/characters/location/referenceAssetIds matter) "
                        + "and the legacy pseudo-shot stage. Never accept an array of shots.",
                AgentScopeTool.schema(Map.ofEntries(
                        Map.entry("run_id", Map.of("type", "string")),
                        Map.entry("shot_id", Map.of("type", "string")),
                        Map.entry("segment_id", Map.of("type", "string")),
                        Map.entry("shot_ordinal", Map.of("type", "integer")),
                        Map.entry("narrative_purpose", Map.of("type", "string")),
                        Map.entry("characters", array("string")),
                        Map.entry("location", Map.of("type", "string")),
                        Map.entry("time", Map.of("type", "string")),
                        Map.entry("conflict", Map.of("type", "string")),
                        Map.entry("action", Map.of("type", "string")),
                        Map.entry("emotion", Map.of("type", "string")),
                        Map.entry("continuity", array("string")),
                        Map.entry("source_excerpt", array("string")),
                        Map.entry("reference_asset_ids", array("string"))),
                        List.of("run_id", "shot_id", "shot_ordinal")),
                false, false, false,
                (input, param) -> guard(() -> {
                    PseudoShot shot = new PseudoShot(
                            string(input.get("shot_id")),
                            string(input.get("segment_id")),
                            integer(input.get("shot_ordinal"), 1),
                            string(input.get("narrative_purpose")),
                            stringList(input.get("characters")),
                            string(input.get("location")),
                            string(input.get("time")),
                            string(input.get("conflict")),
                            string(input.get("action")),
                            string(input.get("emotion")),
                            stringList(input.get("continuity")),
                            stringList(input.get("source_excerpt")),
                            stringList(input.get("reference_asset_ids")));
                    StoryboardRunSnapshot snapshot =
                            productionService.upsertShot(string(input.get("run_id")), shot);
                    attachReview(context(param), snapshot);
                    return snapshot;
                }));
    }

    private AgentScopeTool listDraftTool() {
        return new AgentScopeTool(
                LIST_STORYBOARD_DRAFT,
                "List the latest owned storyboard draft for the current conversation. "
                        + "Returns run_id, stage, version and the editable shots.",
                AgentScopeTool.schema(Map.of("run_id", Map.of("type", "string")), List.of()),
                true, true, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    String runId = string(input.get("run_id"));
                    StoryboardRunSnapshot run = runId.isBlank()
                            ? productionService.getLatestOwned(
                            conversation.getUserId(), conversation.getProjectId(), conversation.getId())
                            : productionService.getOwned(
                            runId, conversation.getUserId(), conversation.getProjectId());
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("runId", run.runId());
                    out.put("stage", run.stage().name());
                    out.put("version", run.version());
                    out.put("pseudoShots", run.pseudoShots());
                    out.put("finalShots", run.finalShots());
                    out.put("error", run.error());
                    attachReview(context, run);
                    return out;
                }));
    }

    private AgentScopeTool listProjectTool() {
        return new AgentScopeTool(
                LIST_PROJECT_STORYBOARD,
                "List the approved storyboard scenes in the current project, including scene ids, "
                        + "order, prompts and image/video status.",
                AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> guard(() -> {
                    AgentConversation conversation =
                            AgentScopeSessionTools.requireConversation(context(param));
                    List<Map<String, Object>> scenes = storyboardClient
                            .getProjectScenes(conversation.getProjectId()).stream()
                            .sorted(java.util.Comparator.comparingInt(Scene::getSceneNumber))
                            .map(AgentScopeStoryboardTools::sceneMap)
                            .toList();
                    return Map.of("projectId", conversation.getProjectId(), "count", scenes.size(),
                            "scenes", scenes);
                }));
    }

    private AgentScopeTool readProjectSceneTool() {
        return new AgentScopeTool(
                READ_PROJECT_SCENE,
                "Read every field of one approved storyboard scene in the current project. "
                        + "Locate it by scene_id or scene_number (pass at least one). Use this after a "
                        + "user @-mentions a scene or when the user asks about one specific scene.",
                AgentScopeTool.schema(Map.of(
                        "scene_id", Map.of("type", "string"),
                        "scene_number", Map.of("type", "integer")), List.of()),
                true, true, false,
                (input, param) -> guard(() -> {
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context(param));
                    String sceneId = string(input.get("scene_id"));
                    int sceneNumber = integer(input.get("scene_number"), -1);
                    if (sceneId.isBlank() && sceneNumber <= 0) {
                        throw new IllegalArgumentException(
                                "请至少提供 scene_id 或 scene_number 之一来定位分镜");
                    }
                    // 只允许读当前会话所属项目的分镜；findProjectScene 在项目列表里定位，
                    // 跨项目 scene_id 天然找不到，返回 ok=false 风格的错误对象而不是抛异常。
                    List<Scene> scenes = storyboardClient.getProjectScenes(conversation.getProjectId());
                    Scene target = findProjectScene(input, scenes, conversation.getProjectId());
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("projectId", conversation.getProjectId());
                    out.put("scene", sceneMap(target));
                    return out;
                }));
    }

    private AgentScopeTool updateProjectShotTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scene_id", Map.of("type", "string"));
        properties.put("scene_number", Map.of("type", "integer"));
        properties.put("script_content", Map.of("type", "string"));
        properties.put("image_prompt", Map.of("type", "string"));
        properties.put("video_prompt", Map.of("type", "string"));
        properties.put("negative_prompt", Map.of("type", "string"));
        properties.put("camera_movement", Map.of("type", "string"));
        properties.put("shot_type", Map.of("type", "string"));
        properties.put("sound_design", Map.of("type", "string"));
        properties.put("duration", Map.of("type", "integer"));
        properties.put("image_model", Map.of("type", "string"));
        properties.put("image_size", Map.of("type", "string"));
        properties.put("image_quality", Map.of("type", "string"));
        properties.put("image_n", Map.of("type", "integer"));
        properties.put("video_model", Map.of("type", "string"));
        properties.put("video_resolution", Map.of("type", "string"));
        properties.put("video_aspect_ratio", Map.of("type", "string"));
        return new AgentScopeTool(
                UPDATE_PROJECT_STORYBOARD_SHOT,
                "Update and persist editable parameters for one approved project storyboard scene. "
                        + "Locate it by scene_id or scene_number. Only supplied fields are changed.",
                AgentScopeTool.schema(properties, List.of()),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context(param));
                    List<Scene> scenes = storyboardClient.getProjectScenesFresh(conversation.getProjectId());
                    Scene target = findProjectScene(input, scenes, conversation.getProjectId());
                    Scene patch = updatedScenePatch(input, target);
                    if (patch == null) {
                        throw new IllegalArgumentException("请至少提供一个要修改的分镜参数");
                    }
                    storyboardClient.updateScene(patch);
                    Scene confirmed = storyboardClient.getProjectScenesFresh(conversation.getProjectId()).stream()
                            .filter(scene -> target.getId().equals(scene.getId()))
                            .findFirst()
                            .orElse(target);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("sceneId", target.getId());
                    out.put("sceneNumber", target.getSceneNumber());
                    out.put("updatedFields", updatedFieldNames(input));
                    out.put("scene", sceneMap(confirmed));
                    return out;
                }));
    }

    private AgentScopeTool deleteDraftShotTool() {
        return new AgentScopeTool(
                DELETE_STORYBOARD_DRAFT_SHOT,
                "Delete one shot from the current editable storyboard draft. This never deletes "
                        + "approved project scenes.",
                AgentScopeTool.schema(Map.of(
                        "run_id", Map.of("type", "string"),
                        "shot_id", Map.of("type", "string")),
                        List.of("shot_id")),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    StoryboardRunSnapshot run = resolveRun(input, conversation);
                    String shotId = string(input.get("shot_id"));
                    if (run.stage() == com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage.PSEUDO_REVIEW) {
                        List<PseudoShot> shots = run.pseudoShots().stream()
                                .filter(shot -> !shot.shotId().equals(shotId)).toList();
                        StoryboardRunSnapshot updated = productionService.replaceDraft(
                                run.runId(), conversation.getUserId(),
                                conversation.getProjectId(), run.version(), shots, List.of(), "agent");
                        attachReview(context, updated);
                    } else if (run.stage() == com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage.FINAL_REVIEW) {
                        List<StoryboardShot> shots = run.finalShots().stream()
                                .filter(shot -> !shot.shotId().equals(shotId)).toList();
                        StoryboardRunSnapshot updated = productionService.replaceDraft(
                                run.runId(), conversation.getUserId(),
                                conversation.getProjectId(), run.version(), List.of(), shots, "agent");
                        attachReview(context, updated);
                    } else {
                        throw new IllegalStateException("当前阶段不可删除分镜草稿: " + run.stage());
                    }
                    return Map.of("ok", true, "runId", run.runId(), "deletedShotId", shotId);
                }));
    }

    private AgentScopeTool reorderDraftTool() {
        return new AgentScopeTool(
                REORDER_STORYBOARD_DRAFT,
                "Reorder the current editable storyboard draft using the complete ordered shot_id list.",
                AgentScopeTool.schema(Map.of(
                        "run_id", Map.of("type", "string"),
                        "shot_ids", array("string")),
                        List.of("shot_ids")),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    StoryboardRunSnapshot run = resolveRun(input, conversation);
                    List<String> orderedIds = stringList(input.get("shot_ids"));
                    if (run.stage() == com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage.PSEUDO_REVIEW) {
                        List<PseudoShot> shots = reorderPseudo(run.pseudoShots(), orderedIds);
                        StoryboardRunSnapshot updated = productionService.replaceDraft(
                                run.runId(), conversation.getUserId(),
                                conversation.getProjectId(), run.version(), shots, List.of(), "agent");
                        attachReview(context, updated);
                    } else if (run.stage() == com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage.FINAL_REVIEW) {
                        List<StoryboardShot> shots = reorderFinal(run.finalShots(), orderedIds);
                        StoryboardRunSnapshot updated = productionService.replaceDraft(
                                run.runId(), conversation.getUserId(),
                                conversation.getProjectId(), run.version(), List.of(), shots, "agent");
                        attachReview(context, updated);
                    } else {
                        throw new IllegalStateException("当前阶段不可排序分镜草稿: " + run.stage());
                    }
                    return Map.of("ok", true, "runId", run.runId(), "count", orderedIds.size());
                }));
    }

    private AgentScopeTool deleteProjectShotTool() {
        return new AgentScopeTool(
                DELETE_PROJECT_STORYBOARD_SHOT,
                "Delete one approved project scene by scene_id or scene_number and renumber the "
                        + "remaining scenes. This does not delete storyboard drafts.",
                AgentScopeTool.schema(Map.of(
                        "scene_id", Map.of("type", "string"),
                        "scene_number", Map.of("type", "integer")), List.of()),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentConversation conversation =
                            AgentScopeSessionTools.requireConversation(context(param));
                    List<Scene> scenes = storyboardClient.getProjectScenes(conversation.getProjectId());
                    String sceneId = string(input.get("scene_id"));
                    int sceneNumber = integer(input.get("scene_number"), -1);
                    Scene target = scenes.stream()
                            .filter(scene -> (!sceneId.isBlank() && sceneId.equals(scene.getId()))
                                    || sceneNumber == scene.getSceneNumber())
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("未找到要删除的项目分镜"));
                    storyboardClient.deleteScene(target.getId(), conversation.getProjectId());
                    return Map.of("ok", true, "deletedSceneId", target.getId(),
                            "deletedSceneNumber", target.getSceneNumber());
                }));
    }

    private AgentScopeTool clearProjectTool() {
        return new AgentScopeTool(
                CLEAR_PROJECT_STORYBOARD,
                "Delete every approved project scene. This is destructive, requires user confirmation "
                        + "through the permission flow, and never deletes storyboard drafts.",
                AgentScopeTool.schema(Map.of(), List.of()),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentConversation conversation =
                            AgentScopeSessionTools.requireConversation(context(param));
                    int count = storyboardClient.getProjectScenes(conversation.getProjectId()).size();
                    storyboardClient.deleteProjectScenes(conversation.getProjectId());
                    return Map.of("ok", true, "projectId", conversation.getProjectId(),
                            "deletedCount", count);
                }));
    }

    private AgentScopeTool batchImageTool() {
        return new AgentScopeTool(
                GENERATE_STORYBOARD_IMAGES,
                "Create one asynchronous image batch for approved project storyboard scenes. "
                        + "Omit scene_ids and scene_numbers to target all scenes. By default only missing "
                        + "images are generated; set overwrite=true only when the user explicitly asks "
                        + "to regenerate existing images.",
                AgentScopeTool.schema(Map.of(
                        "scene_ids", array("string"),
                        "scene_numbers", array("integer"),
                        "overwrite", Map.of("type", "boolean"),
                        "confirmed", Map.of("type", "boolean")), List.of()),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    List<String> requestedIds = stringList(input.get("scene_ids"));
                    List<Integer> requestedNumbers = integerList(input.get("scene_numbers"));
                    boolean filtered = !requestedIds.isEmpty() || !requestedNumbers.isEmpty();
                    List<Scene> selected = storyboardClient.getProjectScenes(conversation.getProjectId()).stream()
                            .sorted(java.util.Comparator.comparingInt(Scene::getSceneNumber))
                            .filter(scene -> !filtered
                                    || requestedIds.contains(scene.getId())
                                    || requestedNumbers.contains(scene.getSceneNumber()))
                            .toList();
                    boolean overwrite = Boolean.TRUE.equals(input.get("overwrite"));
                    if (overwrite && !Boolean.TRUE.equals(input.get("confirmed"))
                            && !confirmationBypassed(context)) {
                        return Map.of(
                                "ok", false,
                                "needsConfirmation", true,
                                "message", "重新生成会覆盖已有分镜图片，请先向用户确认，再以 confirmed=true 调用。");
                    }
                    List<AgentScopeAsyncTaskService.StoryboardImageTarget> targets = new ArrayList<>();
                    for (Scene scene : selected) {
                        if (!overwrite && hasImage(scene)) {
                            continue;
                        }
                        List<String> sourceUrls = sceneReferenceImageUrls(scene);
                        // 衔接说明只在送进模型前合并（库里的两列保持独立，便于单独审阅）
                        String prompt = StoryboardPromptAssembly.withContinuity(
                                nonBlank(scene.getImagePrompt(),
                                        nonBlank(scene.getScriptContent(), "分镜" + scene.getSceneNumber())),
                                scene.getContinuityNote());
                        targets.add(new AgentScopeAsyncTaskService.StoryboardImageTarget(
                                "分镜" + scene.getSceneNumber(), scene.getId(), scene.getId(),
                                prompt, sourceUrls));
                    }
                    if (targets.isEmpty()) {
                        return Map.of("ok", true, "queued", false, "count", 0,
                                "message", overwrite ? "没有可重新生成的分镜图片" : "所有所选分镜都已有图片");
                    }
                    String taskId = asyncTasks.startStoryboardImageTask(
                            conversation, targets, overwrite, toolCallId(param));
                    if (context != null && context.turnState() != null) {
                        context.turnState().addTaskCard(
                                taskId, "批量生成分镜图片 " + targets.size() + " 张", "image-batch");
                        context.turnState().markMediaStarted("image");
                    }
                    if (context != null && context.support() != null && context.request() != null) {
                        context.support().sendEvent(context.request(), "task_accepted", Map.of(
                                "taskId", taskId,
                                "taskKind", "image-batch",
                                "message", "批量生成分镜图片任务已受理，正在生成 "
                                        + targets.size() + " 张图片"));
                    }
                    return Map.of("ok", true, "queued", true, "taskId", taskId,
                            "taskKind", "image-batch", "count", targets.size());
                }));
    }

    private AgentScopeTool startTool() {
        return new AgentScopeTool(
                START_STORYBOARD,
                "Start the staged storyboard pipeline as an asynchronous task. Returns a taskId "
                        + "immediately; the runtime produces a thin shot outline (A) for approval, "
                        + "then expands each shot and writes it to the project (B). If the project "
                        + "already has scenes, return needsChoice=true and let the user choose append, "
                        + "overwrite or delete through choose_storyboard_commit_mode before starting. "
                        + "Pass asset_ids whenever the session already has reference images/asset "
                        + "library entries: those assets are what keeps characters and locations "
                        + "consistent across shots, and every shot binds the subset it needs. When "
                        + "asset_ids and reference_urls are both omitted the server falls back to the "
                        + "session assets (assetsAutoInjected=true). "
                        + "Optional generation params (omit to let the gateway defaults decide): "
                        + "video_model / video_resolution / aspect_ratio / image_model / image_size / "
                        + "image_quality / image_n / target_duration. Values outside what the chosen "
                        + "model supports are clamped and reported back in paramNotes — tell the user "
                        + "about every note, never silently change their request.",
                AgentScopeTool.schema(Map.ofEntries(
                        Map.entry("prompt", Map.of("type", "string")),
                        Map.entry("asset_ids", array("string")),
                        Map.entry("reference_urls", array("string")),
                        Map.entry("role_hints", array("string")),
                        Map.entry("creation_type", Map.of("type", "string")),
                        Map.entry("requested_shot_count", Map.of("type", "string")),
                        Map.entry("commit_mode", Map.of("type", "string")),
                        Map.entry("video_model", Map.of("type", "string")),
                        Map.entry("video_resolution", Map.of("type", "string")),
                        Map.entry("aspect_ratio", Map.of("type", "string")),
                        Map.entry("image_model", Map.of("type", "string")),
                        Map.entry("image_size", Map.of("type", "string")),
                        Map.entry("image_quality", Map.of("type", "string")),
                        Map.entry("image_n", Map.of("type", "integer")),
                        Map.entry("target_duration", Map.of("type", "integer"))),
                        List.of("prompt")),
                false, false, false,
                (input, param) -> {
                    AgentScopeRunContext context = context(param);
                    if (context != null && context.turnState() != null) {
                        context.turnState().markStoryboardRequested();
                    }
                    return guard(() -> {
                        AgentConversation conversation =
                                AgentScopeSessionTools.requireConversation(context);
                        List<String> assetIds = stringList(input.get("asset_ids"));
                        List<String> requestedUrls = stringList(input.get("reference_urls"));
                        // 注入型兜底：模型漏传 asset_ids 时自动带上会话素材。
                        // 不靠「模型自觉传参」是既有教训——画布工具注入 userId/projectId 同理。
                        // 只有显式也没给 reference_urls 时才兜底，避免用户明确指定素材时被会话素材顶掉。
                        boolean autoInjected = false;
                        List<SessionAssetRef> chosen;
                        if (assetIds.isEmpty() && requestedUrls.isEmpty()) {
                            chosen = autoSessionAssets(conversation.getId());
                            autoInjected = !chosen.isEmpty();
                        } else {
                            chosen = sessionAssets.refsByIds(conversation.getId(), assetIds);
                        }
                        List<AssetRef> assets = new ArrayList<>();
                        for (SessionAssetRef ref : chosen) {
                            // purpose 承载素材语义标签（「角色｜林知夏：短发女性…」）：
                            // 模型就是靠这一列判断「这镜该不该用这张图」，留空等于没素材。
                            assets.add(new AssetRef(ref.assetId(), "reference", ref.url(), ref.label()));
                        }
                        for (String url : requestedUrls) {
                            assets.add(new AssetRef("reference-" + (assets.size() + 1), "image", url, ""));
                        }
                        int existingSceneCount = storyboardClient
                                .getProjectScenesFresh(conversation.getProjectId()).size();
                        String rawCommitMode = string(input.get("commit_mode"))
                                .trim().toLowerCase(java.util.Locale.ROOT);
                        if ("delete".equals(rawCommitMode) || "删除".equals(rawCommitMode)
                                || "删除全部".equals(rawCommitMode)) {
                            if (context != null && context.turnState() != null) {
                                context.turnState().takePendingStoryboardCommitCount();
                            }
                            storyboardClient.deleteProjectScenes(conversation.getProjectId());
                            return Map.of(
                                    "ok", true,
                                    "deleted", true,
                                    "existingSceneCount", existingSceneCount,
                                    "message", "已删除项目全部分镜，本次未写入新分镜");
                        }
                        StoryboardCommitMode commitMode = commitMode(input.get("commit_mode"));
                        if (existingSceneCount > 0 && commitMode == null) {
                            if (context != null && context.turnState() != null) {
                                context.turnState().markStoryboardCommitPending(existingSceneCount);
                            }
                            return Map.of(
                                    "ok", false,
                                    "needsChoice", true,
                                    "existingSceneCount", existingSceneCount,
                                    "message", "当前项目已有 " + existingSceneCount
                                            + " 个分镜。请调用 choose_storyboard_commit_mode，"
                                            + "让用户选择追加到末尾、覆盖全部分镜或删除全部分镜。");
                        }
                        if (context != null && context.turnState() != null) {
                            context.turnState().takePendingStoryboardCommitCount();
                        }
                        if (commitMode == null) commitMode = StoryboardCommitMode.APPEND;
                        // 生成参数：用户偏好 > 模型入参 > 网关能力默认值；越界值收敛并回传 notes
                        int shotCount = parseShotCount(input.get("requested_shot_count"));
                        StoryboardParamResolver.Resolved resolvedParams = resolveParams(context, input, shotCount);
                        StoryboardProductionRequest request = new StoryboardProductionRequest(
                                conversation.getUserId(), conversation.getProjectId(), conversation.getId(), "",
                                string(input.get("prompt")), string(input.get("creation_type")),
                                string(input.get("requested_shot_count")), assets,
                                stringList(input.get("role_hints")), resolvedParams.params());
                        String taskId = asyncTasks.startPlotReviewStoryboardTask(
                                conversation, request, toolCallId(param), commitMode);
                        if (taskId == null || taskId.isBlank()) {
                            // 没有 taskId 就不能告诉模型「已受理」，否则它会去轮询一个不存在的任务
                            throw new IllegalStateException("分镜任务创建失败，请稍后重试");
                        }
                        if (context != null && context.turnState() != null) {
                            context.turnState().addTaskCard(taskId, "生成镜头大纲", "storyboard");
                            context.turnState().addText("分镜生成任务已受理，任务 ID：`" + taskId + "`。");
                        }
                        if (context != null && context.support() != null && context.request() != null) {
                            context.support().sendEvent(context.request(), "task_accepted", Map.of(
                                    "taskId", taskId,
                                    "taskKind", "long-tool",
                                    "message", "分镜生成任务已受理，正在生成镜头大纲，确认后逐镜补全"));
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("ok", true);
                        result.put("status", "queued");
                        result.put("taskId", taskId);
                        result.put("taskKind", "long-tool");
                        result.put("commitMode", commitMode.name());
                        result.put("existingSceneCount", existingSceneCount);
                        result.put("assetCount", assets.size());
                        result.put("assetsAutoInjected", autoInjected);
                        result.put("appliedParams", describeParams(resolvedParams.params()));
                        result.put("paramNotes", resolvedParams.notes());
                        result.put("paramsUnverified", resolvedParams.unverified());
                        result.put("message", "分镜任务已受理：先出镜头大纲等你确认，确认后逐镜补全并写入项目");
                        return result;
                    });
                });
    }

    /** 把工具入参 + 用户偏好交给解析器；解析器不可用（单测/降级）时返回空参数。 */
    private StoryboardParamResolver.Resolved resolveParams(AgentScopeRunContext context,
                                                           Map<String, Object> input, int shotCount) {
        StoryboardParamResolver resolver = paramResolver;
        if (resolver == null) {
            return new StoryboardParamResolver.Resolved(
                    StoryboardGenerationParams.EMPTY, List.of(), true);
        }
        Map<String, String> requested = new LinkedHashMap<>();
        putIfPresent(requested, "videoModel", input.get("video_model"));
        putIfPresent(requested, "videoResolution", input.get("video_resolution"));
        putIfPresent(requested, "aspectRatio", input.get("aspect_ratio"));
        putIfPresent(requested, "imageModel", input.get("image_model"));
        putIfPresent(requested, "imageSize", input.get("image_size"));
        putIfPresent(requested, "imageQuality", input.get("image_quality"));
        putIfPresent(requested, "imageN", input.get("image_n"));
        putIfPresent(requested, "targetDuration", input.get("target_duration"));
        // 用户把总时长写进提示词（"总时长 30 秒"）时模型经常不翻译成参数，这里补一层兜底
        if (!requested.containsKey("targetDuration")) {
            Integer fromPrompt = totalSecondsFromPrompt(String.valueOf(input.getOrDefault("prompt", "")));
            if (fromPrompt != null) requested.put("targetDuration", String.valueOf(fromPrompt));
        }
        AgentGenerationPreferences preferences = context != null && context.request() != null
                ? context.request().getGenerationPreferences()
                : AgentGenerationPreferences.none();
        return resolver.resolve(requested, preferences, shotCount);
    }

    private static void putIfPresent(Map<String, String> target, String key, Object value) {
        if (value == null) return;
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) target.put(key, text);
    }

    /**
     * 从用户提示词里抓「总时长 N 秒」。
     *
     * <p>实测：用户说"总时长 30 秒"，模型只把它当成创作约束，不会翻译成 {@code target_duration}
     * 参数（连续两轮都是 null），结果四镜各 4s、合计 16s，与用户要的 30s 不符。
     * 所以这里做一条保守的正则兜底：必须带「总时长/全长/总长/时长/一共」这类总括词，
     * 避免把「每一镜 5 秒」误读成总时长。
     */
    static Integer totalSecondsFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        java.util.regex.Matcher matcher = TOTAL_SECONDS.matcher(prompt);
        if (!matcher.find()) return null;
        try {
            int seconds = Integer.parseInt(matcher.group(1));
            return seconds >= 4 && seconds <= 3600 ? seconds : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 「总时长 30 秒 / 全长30s / 时长：60秒 / 一共约 20 S」。 */
    private static final java.util.regex.Pattern TOTAL_SECONDS = java.util.regex.Pattern.compile(
            "(?:总时长|总长|全长|时长|一共|总共)\\s*(?:为|是|约|:|：)?\\s*(\\d{1,4})\\s*(?:秒|s|S)");

    /** 只把有值的参数回给模型，避免一串 null 干扰它写说明。 */
    private static Map<String, String> describeParams(StoryboardGenerationParams params) {
        Map<String, String> applied = new LinkedHashMap<>();
        if (params == null) return applied;
        putIfPresent(applied, "videoModel", params.videoModel());
        putIfPresent(applied, "videoResolution", params.videoResolution());
        putIfPresent(applied, "aspectRatio", params.videoAspectRatio());
        putIfPresent(applied, "imageModel", params.imageModel());
        putIfPresent(applied, "imageSize", params.imageSize());
        putIfPresent(applied, "imageQuality", params.imageQuality());
        putIfPresent(applied, "imageN", params.imageN());
        putIfPresent(applied, "targetDuration", params.targetDurationSeconds());
        return applied;
    }

    /** requested_shot_count 可能是 "4" / "四" / 空；解析不出返回 0（按叙事节拍决定）。 */
    private static int parseShotCount(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 会话素材兜底（图片优先，上限 {@link #AUTO_ASSET_LIMIT}），带语义标签。
     *
     * <p>模型漏传 {@code asset_ids} 是常态（历史上 47 次调用 0 次传过），而资产一旦没进
     * {@link StoryboardProductionRequest#assets()}，伪分镜阶段的「可用资产」就是空的，
     * 后续逐镜绑定与写库关联全是空转。这里把「会话里有素材」这个事实直接兑现成参数。
     */
    private List<SessionAssetRef> autoSessionAssets(String conversationId) {
        try {
            List<SessionAssetRef> refs = new ArrayList<>();
            for (SessionAssetRef ref : sessionAssets.list(conversationId, "image", AUTO_ASSET_LIMIT)) {
                if (ref == null || ref.assetId() == null || ref.assetId().isBlank()) continue;
                if (refs.stream().anyMatch(existing -> ref.assetId().equals(existing.assetId()))) continue;
                refs.add(ref);
            }
            return refs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static StoryboardCommitMode commitMode(Object value) {
        String normalized = string(value).toLowerCase(java.util.Locale.ROOT);
        if ("append".equals(normalized) || "追加".equals(normalized)
                || "追加到末尾".equals(normalized)) {
            return StoryboardCommitMode.APPEND;
        }
        if ("overwrite".equals(normalized) || "replace".equals(normalized)
                || "覆盖".equals(normalized) || "覆盖全部".equals(normalized)) {
            return StoryboardCommitMode.OVERWRITE;
        }
        return null;
    }

    private AgentScopeTool reviseTool() {
        return new AgentScopeTool(
                REVISE_STORYBOARD,
                "Revise the current storyboard production stage. Use shot_id when revising one pseudo shot.",
                AgentScopeTool.schema(Map.of(
                        "run_id", Map.of("type", "string"),
                        "stage", Map.of("type", "string"),
                        "instruction", Map.of("type", "string"),
                        "shot_id", Map.of("type", "string")), List.of("run_id", "stage", "instruction")),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation =
                            AgentScopeSessionTools.requireConversation(context);
                    String runId = string(input.get("run_id"));
                    if (runId.isBlank()) {
                        runId = productionService.getLatestOwned(
                                conversation.getUserId(),
                                conversation.getProjectId(),
                                conversation.getId()).runId();
                    }
                    String stage = stage(input.get("stage")).name();
                    String taskId = asyncTasks.startStoryboardRevisionTask(
                            conversation,
                            runId,
                            stage,
                            string(input.get("instruction")),
                            string(input.get("shot_id")),
                            toolCallId(param));
                    if (context != null && context.turnState() != null) {
                        context.turnState().addTaskCard(taskId, "修订分镜", "storyboard");
                        context.turnState().addText("分镜修订任务已受理，任务 ID：`" + taskId + "`。");
                    }
                    if (context != null && context.support() != null && context.request() != null) {
                        context.support().sendEvent(context.request(), "task_accepted", Map.of(
                                "taskId", taskId,
                                "taskKind", "long-tool",
                                "message", "分镜修订任务已受理，正在按修改要求重做剧情或分镜草稿"));
                    }
                    return Map.of(
                            "ok", true,
                            "status", "queued",
                            "taskId", taskId,
                            "taskKind", "long-tool",
                            "message", "分镜修订任务已受理");
                }));
    }

    private AgentScopeTool approveTool() {
        return new AgentScopeTool(
                APPROVE_STORYBOARD,
                "Legacy compatibility only. Do not use this for tasks started by "
                        + "start_storyboard_production because the new pipeline generates and writes the "
                        + "project storyboard itself. For historical runs, approve PLOT_REVIEW or "
                        + "FINAL_REVIEW as an asynchronous task and return its taskId immediately.",
                AgentScopeTool.schema(Map.of(
                        "run_id", Map.of("type", "string"),
                        "stage", Map.of("type", "string"),
                        "decision", Map.of("type", "string")), List.of("stage")),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    String runId = string(input.get("run_id"));
                    if (runId.isBlank()) {
                        runId = productionService.getLatestOwned(
                                conversation.getUserId(),
                                conversation.getProjectId(),
                                conversation.getId()).runId();
                    }
                    String taskId = asyncTasks.startStoryboardApprovalTask(
                            conversation,
                            runId,
                            string(input.get("stage")),
                            string(input.get("decision")),
                            toolCallId(param));
                    if (context != null && context.turnState() != null) {
                        context.turnState().addTaskCard(
                                taskId,
                                "PLOT_REVIEW".equalsIgnoreCase(string(input.get("stage")))
                                        ? "生成正式镜头清单"
                                        : "FINAL_REVIEW".equalsIgnoreCase(string(input.get("stage")))
                                        ? "写入项目分镜" : "生成正式分镜",
                                "storyboard");
                        context.turnState().addText("分镜生成任务已受理，任务 ID：`" + taskId + "`。");
                    }
                    if (context != null && context.support() != null && context.request() != null) {
                        context.support().sendEvent(context.request(), "task_accepted", Map.of(
                                "taskId", taskId,
                                "taskKind", "long-tool",
                                "message", "分镜生成任务已受理，完成后会自动显示结果"));
                    }
                    return Map.of(
                            "ok", true,
                            "status", "queued",
                            "taskId", taskId,
                            "taskKind", "long-tool",
                            "message", "分镜生成任务已受理，完成后会自动显示结果");
                }));
    }

    private AgentScopeTool imageTool() {
        return new AgentScopeTool(
                GENERATE_STORYBOARD_IMAGE,
                "Generate one image for one approved storyboard shot. Always pass searchable run_id and shot_id.",
                AgentScopeTool.schema(Map.of(
                        "run_id", Map.of("type", "string"),
                        "shot_id", Map.of("type", "string"),
                        "prompt", Map.of("type", "string"),
                        "size", Map.of("type", "string"),
                        "quality", Map.of("type", "string"),
                        "n", Map.of("type", "string")), List.of("run_id", "shot_id")),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    StoryboardRunSnapshot run = productionService.getOwned(
                            string(input.get("run_id")), conversation.getUserId(), conversation.getProjectId());
                    if (run.stage() != com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage.COMPLETED) {
                        throw new IllegalStateException("正式分镜尚未审核并写入项目，不能开始生图");
                    }
                    StoryboardShot shot = findShot(run, string(input.get("shot_id")));
                    String sceneId = productionService.projectSceneId(run.runId(), shot.shotId());
                    if (sceneId.isBlank()) {
                        throw new IllegalStateException("该镜头尚未映射到项目分镜");
                    }
                    ShotReferenceResolution referenceResolution =
                            productionService.referenceAssetIds(run.runId(), shot.shotId());
                    List<String> refIds = referenceResolution.assetIds();
                    List<String> refUrls = sessionAssets.urlsByIds(conversation.getId(), refIds);
                    Map<String, Object> result = imageTools.generateImageForStoryboard(
                            nonBlank(string(input.get("prompt")), shot.imagePrompt()),
                            refIds, refUrls, string(input.get("size")), string(input.get("quality")),
                            string(input.get("n")), sceneId, context);
                    Map<String, Object> out = new LinkedHashMap<>(result);
                    out.put("runId", run.runId());
                    out.put("shotId", shot.shotId());
                    out.put("referenceAudit", referenceAudit(referenceResolution, refUrls.size()));
                    if (referenceResolution.fallbackUsed()) out.put("referenceFallback", true);
                    return out;
                }));
    }

    private AgentScopeTool videoTool() {
        return new AgentScopeTool(
                GENERATE_STORYBOARD_VIDEO,
                "Create one asynchronous video task for one approved project storyboard scene. "
                        + "Choose the reference mode yourself without asking the user. If omitted, "
                        + "the tool automatically uses no references for none, first frame for one, "
                        + "first/last frame for two, and full reference mode for three or more. "
                        + "Pass reference_mode explicitly when the user stated the intended role.",
                AgentScopeTool.schema(Map.ofEntries(
                        Map.entry("run_id", Map.of("type", "string")),
                        Map.entry("shot_id", Map.of("type", "string")),
                        Map.entry("scene_id", Map.of("type", "string")),
                        Map.entry("scene_number", Map.of("type", "integer")),
                        Map.entry("reference_mode", Map.of("type", "string")),
                        Map.entry("reference_asset_ids", array("string")),
                        Map.entry("reference_urls", array("string")),
                        Map.entry("prompt", Map.of("type", "string")),
                        Map.entry("model", Map.of("type", "string")),
                        Map.entry("resolution", Map.of("type", "string")),
                        Map.entry("aspect_ratio", Map.of("type", "string")),
                        Map.entry("duration", Map.of("type", "string"))), List.of()),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    VideoTarget target = resolveVideoTarget(input, conversation);
                    List<String> availableUrls = availableVideoReferenceUrls(
                            input, target, conversation);
                    String requestedMode = string(input.get("reference_mode"))
                            .trim().toLowerCase(java.util.Locale.ROOT);
                    boolean explicitReferences = !requestedMode.isBlank()
                            || !stringList(input.get("reference_asset_ids")).isEmpty()
                            || !stringList(input.get("reference_urls")).isEmpty();
                    if (!explicitReferences) {
                        String taskId = asyncTasks.startStoryboardVideoTask(
                                conversation, List.of(target.sceneId()), false, toolCallId(param));
                        if (context != null && context.turnState() != null) {
                            context.turnState().addTaskCard(taskId, "生成分镜视频", "long-tool");
                            context.turnState().addText(
                                    "分镜视频任务已受理，将在后台自动生成并写回分镜。");
                        }
                        if (context != null && context.support() != null && context.request() != null) {
                            context.support().sendEvent(context.request(), "task_accepted", Map.of(
                                    "taskId", taskId,
                                    "taskKind", "long-tool",
                                    "message", "分镜视频任务已受理，正在后台自动生成"));
                        }
                        return Map.of(
                                "ok", true,
                                "status", "queued",
                                "taskId", taskId,
                                "taskKind", "long-tool",
                                "sceneId", target.sceneId(),
                                "sceneNumber", target.sceneNumber(),
                                "message", "分镜视频任务已受理");
                    }
                    boolean automatic = requestedMode.isBlank();
                    String referenceMode = automatic
                            ? automaticReferenceMode(availableUrls)
                            : requestedMode;
                    List<ReferenceImage> roleImages = referenceImagesForMode(
                            referenceMode, availableUrls);
                    Map<String, Object> result = videoTools.generateVideoForStoryboard(
                            nonBlank(string(input.get("prompt")), target.defaultPrompt()),
                            roleImages, string(input.get("model")), string(input.get("resolution")),
                            string(input.get("aspect_ratio")), string(input.get("duration")),
                            target.sceneId(), context);
                    Map<String, Object> out = new LinkedHashMap<>(result);
                    out.put("sceneId", target.sceneId());
                    out.put("sceneNumber", target.sceneNumber());
                    if (!target.runId().isBlank()) out.put("runId", target.runId());
                    if (!target.shotId().isBlank()) out.put("shotId", target.shotId());
                    out.put("referenceMode", referenceMode);
                    out.put("referenceDecision", automatic ? "auto" : "explicit");
                    out.put("referenceAudit", Map.of(
                            "availableReferenceCount", availableUrls.size(),
                            "appliedReferenceCount", roleImages.size(),
                            "roles", roleImages.stream().map(image -> image.role().name()).toList()));
                    return out;
                }));
    }

    private AgentScopeTool batchVideoTool() {
        return new AgentScopeTool(
                GENERATE_STORYBOARD_VIDEOS,
                "Create one asynchronous batch job for approved project storyboard videos. "
                        + "By default process only scenes without a completed video. "
                        + "Set overwrite=true only when the user explicitly asks to regenerate all videos.",
                AgentScopeTool.schema(Map.of(
                        "scene_ids", array("string"),
                        "scene_numbers", array("integer"),
                        "overwrite", Map.of("type", "boolean")), List.of()),
                false, false, false,
                (input, param) -> guard(() -> {
                    AgentScopeRunContext context = context(param);
                    AgentConversation conversation =
                            AgentScopeSessionTools.requireConversation(context);
                    List<String> requestedIds = stringList(input.get("scene_ids"));
                    List<Integer> requestedNumbers = integerList(input.get("scene_numbers"));
                    boolean filtered = !requestedIds.isEmpty() || !requestedNumbers.isEmpty();
                    boolean overwrite = Boolean.TRUE.equals(input.get("overwrite"));
                    List<String> targets = storyboardClient
                            .getProjectScenesFresh(conversation.getProjectId()).stream()
                            .sorted(java.util.Comparator.comparingInt(Scene::getSceneNumber))
                            .filter(scene -> !filtered
                                    || requestedIds.contains(scene.getId())
                                    || requestedNumbers.contains(scene.getSceneNumber()))
                            .filter(scene -> overwrite || needsStoryboardVideo(scene))
                            .map(Scene::getId)
                            .toList();
                    if (targets.isEmpty()) {
                        return Map.of(
                                "ok", true,
                                "queued", false,
                                "count", 0,
                                "message", overwrite
                                        ? "没有可重新生成的分镜视频"
                                        : "所有所选分镜都已有完成视频");
                    }
                    String taskId = asyncTasks.startStoryboardVideoTask(
                            conversation, targets, overwrite, toolCallId(param));
                    if (context != null && context.turnState() != null) {
                        context.turnState().addTaskCard(
                                taskId, "批量生成分镜视频 " + targets.size() + " 个", "long-tool");
                        context.turnState().addText(
                                "分镜视频批量任务已受理，共 " + targets.size()
                                        + " 个分镜，将在后台自动生成。");
                    }
                    if (context != null && context.support() != null && context.request() != null) {
                        context.support().sendEvent(context.request(), "task_accepted", Map.of(
                                "taskId", taskId,
                                "taskKind", "long-tool",
                                "message", "分镜视频批量任务已受理，正在后台自动生成 "
                                        + targets.size() + " 个视频"));
                    }
                    return Map.of(
                            "ok", true,
                            "status", "queued",
                            "taskId", taskId,
                            "taskKind", "long-tool",
                            "count", targets.size(),
                            "message", "分镜视频批量任务已受理");
                }));
    }

    private static boolean needsStoryboardVideo(Scene scene) {
        if (scene == null) return false;
        if (scene.getVideoUrl() == null || scene.getVideoUrl().isBlank()) return true;
        String status = string(scene.getVideoStatus()).toLowerCase(java.util.Locale.ROOT);
        return "failed".equals(status) || "cancelled".equals(status) || "canceled".equals(status);
    }

    private static StoryboardShot findShot(StoryboardRunSnapshot run, String shotId) {
        return run.finalShots().stream().filter(shot -> shot.shotId().equals(shotId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("镜头尚未完成正式生成: " + shotId));
    }

    private VideoTarget resolveVideoTarget(Map<String, Object> input,
                                           AgentConversation conversation) {
        String runId = string(input.get("run_id"));
        String shotId = string(input.get("shot_id"));
        if (!runId.isBlank() && !shotId.isBlank()) {
            StoryboardRunSnapshot run = productionService.getOwned(
                    runId, conversation.getUserId(), conversation.getProjectId());
            StoryboardShot shot = findShot(run, shotId);
            String sceneId = productionService.projectSceneId(run.runId(), shot.shotId());
            Scene scene = storyboardClient.getProjectScenesFresh(conversation.getProjectId()).stream()
                    .filter(candidate -> candidate.getId().equals(sceneId))
                    .findFirst()
                    .orElse(null);
            return new VideoTarget(
                    run.runId(), shot.shotId(), sceneId,
                    scene == null ? shot.shotOrdinal() : scene.getSceneNumber(),
                    scene, nonBlank(shot.videoPrompt(), shot.sceneDescription()));
        }
        Scene scene = findProjectScene(
                input, storyboardClient.getProjectScenesFresh(conversation.getProjectId()),
                conversation.getProjectId());
        return new VideoTarget(
                "", "", scene.getId(), scene.getSceneNumber(), scene,
                nonBlank(scene.getVideoPrompt(), scene.getScriptContent()));
    }

    private List<String> availableVideoReferenceUrls(
            Map<String, Object> input,
            VideoTarget target,
            AgentConversation conversation) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (target.scene() != null) {
            addSceneImages(urls, target.scene());
            for (Map<String, Object> reference :
                    storyboardClient.getSceneReferences(target.sceneId())) {
                addImageReference(urls, reference);
            }
        }
        if (!target.runId().isBlank() && !target.shotId().isBlank()) {
            ShotReferenceResolution resolution =
                    productionService.referenceAssetIds(target.runId(), target.shotId());
            sessionAssets.urlsByIds(conversation.getId(), resolution.assetIds())
                    .forEach(urls::add);
        }
        sessionAssets.urlsByIds(
                        conversation.getId(), stringList(input.get("reference_asset_ids")))
                .forEach(urls::add);
        stringList(input.get("reference_urls")).forEach(urls::add);
        return List.copyOf(urls);
    }

    private static List<ReferenceImage> referenceImagesForMode(
            String mode, List<String> urls) {
        List<String> available = urls == null ? List.of() : urls;
        return switch (mode) {
            case "none", "no_reference", "without_reference" -> List.of();
            case "first_frame" -> List.of(ReferenceImage.ofFirstFrame(
                    firstReference(available, "首帧")));
            case "last_frame" -> List.of(ReferenceImage.ofLastFrame(
                    lastReference(available, "尾帧")));
            case "first_last_frame" -> {
                if (available.size() < 2) {
                    throw new IllegalArgumentException("首尾帧模式需要至少 2 张参考图");
                }
                yield List.of(
                        ReferenceImage.ofFirstFrame(available.get(0)),
                        ReferenceImage.ofLastFrame(available.get(1)));
            }
            case "reference", "full_reference" -> available.stream()
                    .map(ReferenceImage::ofReference)
                    .toList();
            default -> throw new IllegalArgumentException("不支持的参考图模式: " + mode);
        };
    }

    private static String automaticReferenceMode(List<String> urls) {
        int count = urls == null ? 0 : urls.size();
        if (count == 0) return "no_reference";
        if (count == 1) return "first_frame";
        if (count == 2) return "first_last_frame";
        return "reference";
    }

    private static String firstReference(List<String> urls, String mode) {
        if (urls.isEmpty()) throw new IllegalArgumentException(mode + "模式需要至少 1 张参考图");
        return urls.getFirst();
    }

    private static String lastReference(List<String> urls, String mode) {
        if (urls.isEmpty()) throw new IllegalArgumentException(mode + "模式需要至少 1 张参考图");
        return urls.getLast();
    }

    private static void addSceneImages(LinkedHashSet<String> urls, Scene scene) {
        if (scene.getImageUrls() != null && !scene.getImageUrls().isBlank()) {
            for (String url : scene.getImageUrls().split(",")) {
                String trimmed = url.trim();
                if (!trimmed.isBlank()) urls.add(trimmed);
            }
        }
        if (scene.getImageUrl() != null && !scene.getImageUrl().isBlank()) {
            urls.add(scene.getImageUrl().trim());
        }
    }

    private static void addImageReference(LinkedHashSet<String> urls,
                                          Map<String, Object> reference) {
        if (reference == null) return;
        Object type = reference.get("type");
        if (type != null && !String.valueOf(type).isBlank()
                && !"image".equalsIgnoreCase(String.valueOf(type))) {
            return;
        }
        Object url = reference.get("url");
        if (url != null && !String.valueOf(url).isBlank()) {
            urls.add(String.valueOf(url).trim());
        }
    }

    private static Scene findProjectScene(Map<String, Object> input, List<Scene> scenes,
                                          String projectId) {
        String sceneId = string(input.get("scene_id"));
        int sceneNumber = integer(input.get("scene_number"), -1);
        if (sceneId.isBlank() && sceneNumber <= 0) {
            throw new IllegalArgumentException("请提供 scene_id 或 scene_number");
        }
        return scenes.stream()
                .filter(scene -> projectId.equals(scene.getProjectId()))
                .filter(scene -> (!sceneId.isBlank() && sceneId.equals(scene.getId()))
                        || (sceneNumber > 0 && sceneNumber == scene.getSceneNumber()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到指定的项目分镜"));
    }

    private static Scene updatedScenePatch(Map<String, Object> input, Scene target) {
        Scene patch = new Scene();
        patch.setId(target.getId());
        patch.setProjectId(target.getProjectId());
        boolean changed = false;
        if (input.containsKey("script_content")) {
            patch.setScriptContent(textValue(input.get("script_content")));
            changed = true;
        }
        if (input.containsKey("image_prompt")) {
            patch.setImagePrompt(textValue(input.get("image_prompt")));
            changed = true;
        }
        if (input.containsKey("video_prompt")) {
            patch.setVideoPrompt(textValue(input.get("video_prompt")));
            changed = true;
        }
        if (input.containsKey("negative_prompt")) {
            patch.setNegativePrompt(textValue(input.get("negative_prompt")));
            changed = true;
        }
        if (input.containsKey("camera_movement")) {
            patch.setCameraMovement(textValue(input.get("camera_movement")));
            changed = true;
        }
        if (input.containsKey("shot_type")) {
            patch.setShotType(textValue(input.get("shot_type")));
            changed = true;
        }
        if (input.containsKey("sound_design")) {
            patch.setSoundDesign(textValue(input.get("sound_design")));
            changed = true;
        }
        if (input.containsKey("duration")) {
            patch.setDuration(nullablePositiveInteger(input.get("duration"), "duration"));
            changed = true;
        }
        if (input.containsKey("image_model")) {
            patch.setImageModel(textValue(input.get("image_model")));
            changed = true;
        }
        if (input.containsKey("image_size")) {
            patch.setImageSize(textValue(input.get("image_size")));
            changed = true;
        }
        if (input.containsKey("image_quality")) {
            patch.setImageQuality(textValue(input.get("image_quality")));
            changed = true;
        }
        if (input.containsKey("image_n")) {
            patch.setImageN(nullablePositiveInteger(input.get("image_n"), "image_n"));
            changed = true;
        }
        if (input.containsKey("video_model")) {
            patch.setVideoModel(textValue(input.get("video_model")));
            changed = true;
        }
        if (input.containsKey("video_resolution")) {
            patch.setVideoResolution(textValue(input.get("video_resolution")));
            changed = true;
        }
        if (input.containsKey("video_aspect_ratio")) {
            patch.setVideoAspectRatio(textValue(input.get("video_aspect_ratio")));
            changed = true;
        }
        return changed ? patch : null;
    }

    private static List<String> updatedFieldNames(Map<String, Object> input) {
        return List.of(
                        "script_content", "image_prompt", "video_prompt", "negative_prompt",
                        "camera_movement", "shot_type", "sound_design", "duration",
                        "image_model", "image_size", "image_quality", "image_n",
                        "video_model", "video_resolution", "video_aspect_ratio")
                .stream()
                .filter(input::containsKey)
                .toList();
    }

    private static String textValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static Integer nullablePositiveInteger(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        int parsed = integer(value, -1);
        if (parsed <= 0) {
            throw new IllegalArgumentException(field + " 必须是正整数");
        }
        return parsed;
    }

    private record VideoTarget(
            String runId,
            String shotId,
            String sceneId,
            int sceneNumber,
            Scene scene,
            String defaultPrompt
    ) {
    }

    private StoryboardRunSnapshot resolveRun(Map<String, Object> input,
                                             AgentConversation conversation) {
        String runId = string(input.get("run_id"));
        return runId.isBlank()
                ? productionService.getLatestOwned(
                conversation.getUserId(), conversation.getProjectId(), conversation.getId())
                : productionService.getOwned(
                runId, conversation.getUserId(), conversation.getProjectId());
    }

    private static void attachReview(AgentScopeRunContext context,
                                     StoryboardRunSnapshot run) {
        if (context == null || context.request() == null || run == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>(context.request().getTurnMetadata());
        metadata.put("kind", "storyboard-review");
        metadata.put("storyboardReview", Map.of(
                "runId", run.runId(),
                "stage", run.stage().name(),
                "version", run.version(),
                "pending", false));
        context.request().setTurnMetadata(metadata);
    }

    /**
     * 项目分镜的字段口径（{@code list_project_storyboard} 与 {@code read_project_scene} 共用一份）。
     */
    private static Map<String, Object> sceneMap(Scene scene) {
        Map<String, Object> item = new LinkedHashMap<>();
        List<String> imageUrls = sceneImageUrls(scene);
        boolean hasVideo = scene.getVideoUrl() != null && !scene.getVideoUrl().isBlank();
        item.put("sceneId", scene.getId());
        item.put("sceneNumber", scene.getSceneNumber());
        item.put("scriptContent", scene.getScriptContent());
        item.put("imagePrompt", scene.getImagePrompt());
        item.put("videoPrompt", scene.getVideoPrompt());
        item.put("negativePrompt", scene.getNegativePrompt());
        item.put("shotType", scene.getShotType());
        item.put("cameraMovement", scene.getCameraMovement());
        item.put("soundDesign", scene.getSoundDesign());
        item.put("duration", scene.getDuration());
        item.put("imageCount", imageUrls.size());
        item.put("videoCount", hasVideo ? 1 : 0);
        item.put("hasImage", !imageUrls.isEmpty());
        item.put("hasVideo", hasVideo);
        item.put("needsImage", imageUrls.isEmpty());
        item.put("needsVideo", !hasVideo);
        item.put("imageStatus", scene.getImageStatus());
        item.put("videoStatus", scene.getVideoStatus());
        return item;
    }

    private static List<PseudoShot> reorderPseudo(List<PseudoShot> shots, List<String> orderedIds) {
        if (orderedIds.size() != shots.size()) {
            throw new IllegalArgumentException("排序列表必须包含全部 shot_id");
        }
        Map<String, PseudoShot> byId = shots.stream()
                .collect(java.util.stream.Collectors.toMap(PseudoShot::shotId, shot -> shot));
        List<PseudoShot> result = new ArrayList<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            PseudoShot shot = byId.get(orderedIds.get(index));
            if (shot == null) throw new IllegalArgumentException("未知 shot_id: " + orderedIds.get(index));
            result.add(new PseudoShot(
                    shot.shotId(), shot.segmentId(), index + 1, shot.narrativePurpose(),
                    shot.characters(), shot.location(), shot.time(), shot.conflict(), shot.action(),
                    shot.emotion(), shot.continuity(), shot.sourceExcerpt(), shot.referenceAssetIds()));
        }
        return List.copyOf(result);
    }

    private static List<StoryboardShot> reorderFinal(List<StoryboardShot> shots,
                                                     List<String> orderedIds) {
        if (orderedIds.size() != shots.size()) {
            throw new IllegalArgumentException("排序列表必须包含全部 shot_id");
        }
        Map<String, StoryboardShot> byId = shots.stream()
                .collect(java.util.stream.Collectors.toMap(StoryboardShot::shotId, shot -> shot));
        List<StoryboardShot> result = new ArrayList<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            StoryboardShot shot = byId.get(orderedIds.get(index));
            if (shot == null) throw new IllegalArgumentException("未知 shot_id: " + orderedIds.get(index));
            result.add(new StoryboardShot(
                    shot.shotId(), shot.segmentId(), index + 1, shot.sceneDescription(),
                    shot.shotType(), shot.cameraMovement(), shot.durationSeconds(),
                    shot.imagePrompt(), shot.videoPrompt(), shot.negativePrompt(),
                    shot.referenceHints()));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> referenceAudit(ShotReferenceResolution resolution,
                                                       int appliedCount) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("expectedReferenceCount", resolution.expectedAssetIds().size());
        audit.put("resolvedReferenceCount", resolution.assetIds().size());
        audit.put("appliedReferenceCount", appliedCount);
        audit.put("fallbackUsed", resolution.fallbackUsed());
        audit.put("finalRecordFound", resolution.finalRecordFound());
        audit.put("pseudoRecordFound", resolution.pseudoRecordFound());
        audit.put("mismatch", resolution.mismatch() || appliedCount != resolution.assetIds().size());
        return audit;
    }

    private static Stage stage(Object value) {
        try {
            return Stage.valueOf(string(value).toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("无效分镜阶段: " + value);
        }
    }

    /**
     * 工具成功返回 Map 时直接摊平返回（保留内层 {@code ok}，内层 {@code ok=false} 时外层同样为 false），
     * 只有非 Map 返回值才包一层 {@code result}；这样 {@code ok:false} 不会被
     * {@code {ok:true, result:{ok:false}}} 伪装成成功。
     */
    private static Map<String, Object> guard(java.util.concurrent.Callable<Object> callable) {
        try {
            Object value = callable.call();
            Map<String, Object> out = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, item) -> out.put(String.valueOf(key), item));
                return out;
            }
            out.put("ok", true);
            out.put("result", value);
            return out;
        } catch (Exception e) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("ok", false);
            failed.put("message",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return failed;
        }
    }

    private static Map<String, Object> array(String itemType) {
        return Map.of("type", "array", "items", Map.of("type", itemType));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .map(item -> integer(item, -1))
                .filter(item -> item > 0)
                .toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim()).toList();
    }

    private static boolean hasImage(Scene scene) {
        return !sceneImageUrls(scene).isEmpty();
    }

    private static List<String> sceneImageUrls(Scene scene) {
        LinkedHashMap<String, Boolean> urls = new LinkedHashMap<>();
        if (scene.getImageUrls() != null && !scene.getImageUrls().isBlank()) {
            for (String url : scene.getImageUrls().split(",")) {
                String trimmed = url.trim();
                if (!trimmed.isBlank()) urls.put(trimmed, Boolean.TRUE);
            }
        }
        if (scene.getImageUrl() != null && !scene.getImageUrl().isBlank()) {
            urls.put(scene.getImageUrl().trim(), Boolean.TRUE);
        }
        return List.copyOf(urls.keySet());
    }

    private List<String> sceneReferenceImageUrls(Scene scene) {
        LinkedHashMap<String, Boolean> urls = new LinkedHashMap<>();
        for (Map<String, Object> reference : storyboardClient.getSceneReferences(scene.getId())) {
            if (reference == null) continue;
            Object type = reference.get("type");
            if (type != null && !String.valueOf(type).isBlank()
                    && !"image".equalsIgnoreCase(String.valueOf(type))) {
                continue;
            }
            Object url = reference.get("url");
            if (url != null && !String.valueOf(url).isBlank()) {
                urls.put(String.valueOf(url).trim(), Boolean.TRUE);
            }
        }
        return List.copyOf(urls.keySet());
    }

    private static AgentScopeRunContext context(io.agentscope.core.tool.ToolCallParam param) {
        return param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

    private static boolean confirmationBypassed(AgentScopeRunContext context) {
        return context != null
                && MediaToolPreflightService.skipClarification(context.request());
    }

    private static String toolCallId(io.agentscope.core.tool.ToolCallParam param) {
        return param == null || param.getToolUseBlock() == null
                ? "" : param.getToolUseBlock().getId();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
