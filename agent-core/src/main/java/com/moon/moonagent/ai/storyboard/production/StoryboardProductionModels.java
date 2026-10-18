package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.StoryboardShot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Shared contracts for the staged storyboard production pipeline.
 */
public final class StoryboardProductionModels {

    private StoryboardProductionModels() {
    }

    public enum Stage {
        PLOT_REVIEW,
        PSEUDO_REVIEW,
        FINALIZING,
        FINAL_REVIEW,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum Phase {
        PSEUDO,
        FINAL
    }

    public enum StoryboardCommitMode {
        APPEND,
        OVERWRITE
    }

    public enum RoleKind {
        CREATIVE,
        DOMAIN
    }

    public record AssetRef(String assetId, String type, String url, String purpose) {
    }

    public record RoleProfile(String id, String label, RoleKind kind, String instructions) {
    }

    public record PlotAssessment(
            boolean complete,
            List<String> missingElements,
            List<String> risks,
            String summary,
            String expansionPlan
    ) {
        public PlotAssessment {
            missingElements = list(missingElements);
            risks = list(risks);
            summary = text(summary);
            expansionPlan = text(expansionPlan);
        }
    }

    public record StoryBible(
            String logline,
            List<String> characters,
            List<String> relationships,
            List<String> worldRules,
            List<String> timeline,
            List<String> continuity,
            List<String> visualStyle
    ) {
        public StoryBible {
            logline = text(logline);
            characters = list(characters);
            relationships = list(relationships);
            worldRules = list(worldRules);
            timeline = list(timeline);
            continuity = list(continuity);
            visualStyle = list(visualStyle);
        }

        public static StoryBible empty() {
            return new StoryBible("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    public record StoryboardSegment(
            String segmentId,
            int ordinal,
            String title,
            String sourceText,
            String boundarySummary
    ) {
        public StoryboardSegment {
            segmentId = text(segmentId);
            title = text(title);
            sourceText = text(sourceText);
            boundarySummary = text(boundarySummary);
        }
    }

    public record PseudoShot(
            String shotId,
            String segmentId,
            int shotOrdinal,
            String narrativePurpose,
            List<String> characters,
            String location,
            String time,
            String conflict,
            String action,
            String emotion,
            List<String> continuity,
            List<String> sourceExcerpt,
            List<String> referenceAssetIds
    ) {
        public PseudoShot {
            shotId = text(shotId);
            segmentId = text(segmentId);
            narrativePurpose = text(narrativePurpose);
            characters = list(characters);
            location = text(location);
            time = text(time);
            conflict = text(conflict);
            action = text(action);
            emotion = text(emotion);
            continuity = list(continuity);
            sourceExcerpt = list(sourceExcerpt);
            referenceAssetIds = list(referenceAssetIds);
        }
    }

    public record OptimizationResult(
            String optimizedScript,
            String expansionScript,
            StoryBible storyBible
    ) {
        public OptimizationResult {
            optimizedScript = text(optimizedScript);
            expansionScript = text(expansionScript);
            storyBible = storyBible == null ? StoryBible.empty() : storyBible;
        }
    }

    public record ScriptChange(
            String issue,
            String originalText,
            String replacement,
            String reason
    ) {
        public ScriptChange {
            issue = text(issue);
            originalText = text(originalText);
            replacement = text(replacement);
            reason = text(reason);
        }
    }

    public record ScriptRepairResult(
            String revisedScript,
            StoryBible storyBible,
            List<ScriptChange> changes
    ) {
        public ScriptRepairResult {
            revisedScript = text(revisedScript);
            storyBible = storyBible == null ? StoryBible.empty() : storyBible;
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /**
     * 分镜生成参数（已按「用户偏好 > 模型自选 > 网关能力」收敛后的最终值）。
     *
     * <p>注意 {@code allowedDurations} 是**当前视频模型真正支持的时长档位**，
     * 由网关 {@code /v1/models} 的 params 解析而来；正式镜头的时长必须收敛到它里面，
     * 而不是各服务各写一份 4..15。
     *
     * <p>{@code ignoreUnknown} 不是防御性摆设：这个对象会被写进异步任务的 requestJson
     * 再由另一个任务回读，字段一旦增减就会踩到 Jackson 默认的「未知字段即失败」，
     * 表现为「参数静默丢失」（实测踩过一次）。
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record StoryboardGenerationParams(
            String imageModel,
            String imageSize,
            String imageQuality,
            Integer imageN,
            String videoModel,
            String videoResolution,
            String videoAspectRatio,
            Integer targetDurationSeconds,
            List<Integer> allowedDurations
    ) {
        public static final StoryboardGenerationParams EMPTY =
                new StoryboardGenerationParams(null, null, null, null, null, null, null, null, List.of());

        public StoryboardGenerationParams {
            imageModel = text(imageModel);
            imageSize = text(imageSize);
            imageQuality = text(imageQuality);
            videoModel = text(videoModel);
            videoResolution = text(videoResolution);
            videoAspectRatio = text(videoAspectRatio);
            allowedDurations = allowedDurations == null ? List.of() : allowedDurations.stream()
                    .filter(value -> value != null && value > 0)
                    .distinct()
                    .sorted()
                    .toList();
        }

        /** 必须是普通 getter 语义之外的工具方法：加 {@code @JsonIgnore} 防止被写成 JSON 字段。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isEmpty() {
            return imageModel.isEmpty() && imageSize.isEmpty() && imageQuality.isEmpty()
                    && imageN == null && videoModel.isEmpty() && videoResolution.isEmpty()
                    && videoAspectRatio.isEmpty() && targetDurationSeconds == null
                    && allowedDurations.isEmpty();
        }

        /** 只保留 allowedDurations，其余置空（用于测试与降级）。 */
        public static StoryboardGenerationParams durationsOnly(List<Integer> durations) {
            return new StoryboardGenerationParams(null, null, null, null, null, null, null, null, durations);
        }
    }

    public record StoryboardProductionRequest(
            String userId,
            String projectId,
            String conversationId,
            String canvasId,
            String originalScript,
            String creationType,
            String requestedShotCount,
            List<AssetRef> assets,
            List<String> roleHints,
            StoryboardGenerationParams generationParams
    ) {
        public StoryboardProductionRequest {
            originalScript = text(originalScript);
            creationType = text(creationType);
            requestedShotCount = text(requestedShotCount);
            assets = assets == null ? List.of() : List.copyOf(assets);
            roleHints = list(roleHints);
            generationParams = generationParams == null ? StoryboardGenerationParams.EMPTY : generationParams;
        }

        /**
         * 向后兼容构造器：不传生成参数时等价于 {@link StoryboardGenerationParams#EMPTY}。
         * 现有调用点与测试因此无需改动。
         */
        public StoryboardProductionRequest(
                String userId, String projectId, String conversationId, String canvasId,
                String originalScript, String creationType, String requestedShotCount,
                List<AssetRef> assets, List<String> roleHints) {
            this(userId, projectId, conversationId, canvasId, originalScript, creationType,
                    requestedShotCount, assets, roleHints, StoryboardGenerationParams.EMPTY);
        }
    }

    public record Approval(
            Stage stage,
            String decision,
            String instruction,
            List<RoleProfile> roles,
            List<PseudoShot> editedPseudoShots
    ) {
        public Approval {
            decision = text(decision);
            instruction = text(instruction);
            roles = roles == null ? List.of() : List.copyOf(roles);
            editedPseudoShots = editedPseudoShots == null ? List.of() : List.copyOf(editedPseudoShots);
        }
    }

    public record ShotGenerationRequest(
            String runId,
            PseudoShot pseudoShot,
            StoryBible storyBible,
            List<AssetRef> assets
    ) {
        public ShotGenerationRequest {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    public record ShotMediaRequest(
            String runId,
            String shotId,
            String mediaType,
            String prompt,
            Map<String, Object> params,
            List<String> referenceAssetIds
    ) {
        public ShotMediaRequest {
            mediaType = text(mediaType);
            prompt = text(prompt);
            params = params == null ? Map.of() : Map.copyOf(params);
            referenceAssetIds = list(referenceAssetIds);
        }
    }

    public record StoryboardRunSnapshot(
            String runId,
            String conversationId,
            Stage stage,
            int version,
            String originalScript,
            String selectedScript,
            String expansionScript,
            PlotAssessment assessment,
            StoryBible storyBible,
            List<RoleProfile> roles,
            List<AssetRef> assets,
            List<PseudoShot> pseudoShots,
            List<StoryboardShot> finalShots,
            String error
    ) {
        public StoryboardRunSnapshot {
            roles = roles == null ? List.of() : List.copyOf(roles);
            assets = assets == null ? List.of() : List.copyOf(assets);
            pseudoShots = pseudoShots == null ? List.of() : List.copyOf(pseudoShots);
            finalShots = finalShots == null ? List.of() : List.copyOf(finalShots);
            error = text(error);
        }

        public StoryboardRunSnapshot(
                String runId,
                Stage stage,
                int version,
                String originalScript,
                String selectedScript,
                String expansionScript,
                PlotAssessment assessment,
                StoryBible storyBible,
                List<RoleProfile> roles,
                List<AssetRef> assets,
                List<PseudoShot> pseudoShots,
                List<StoryboardShot> finalShots,
                String error
        ) {
            this(runId, "", stage, version, originalScript, selectedScript, expansionScript,
                    assessment, storyBible, roles, assets, pseudoShots, finalShots, error);
        }
    }

    public record RunState(
            String runId,
            String userId,
            String projectId,
            String conversationId,
            String canvasId,
            String originalScript,
            String requestedShotCount,
            String selectedScript,
            String expansionScript,
            Stage stage,
            int version,
            PlotAssessment assessment,
            StoryBible storyBible,
            List<RoleProfile> roles,
            List<AssetRef> assets,
            List<PseudoShot> pseudoShots,
            List<StoryboardShot> finalShots,
            String error,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public RunState {
            requestedShotCount = text(requestedShotCount);
            roles = roles == null ? List.of() : List.copyOf(roles);
            assets = assets == null ? List.of() : List.copyOf(assets);
            pseudoShots = pseudoShots == null ? List.of() : List.copyOf(pseudoShots);
            finalShots = finalShots == null ? List.of() : List.copyOf(finalShots);
        }

        public RunState(
                String runId,
                String userId,
                String projectId,
                String conversationId,
                String canvasId,
                String originalScript,
                String selectedScript,
                String expansionScript,
                Stage stage,
                int version,
                PlotAssessment assessment,
                StoryBible storyBible,
                List<RoleProfile> roles,
                List<AssetRef> assets,
                List<PseudoShot> pseudoShots,
                List<StoryboardShot> finalShots,
                String error,
                OffsetDateTime createdAt,
                OffsetDateTime updatedAt
        ) {
            this(runId, userId, projectId, conversationId, canvasId, originalScript, "",
                    selectedScript, expansionScript, stage, version, assessment, storyBible,
                    roles, assets, pseudoShots, finalShots, error, createdAt, updatedAt);
        }

        public StoryboardRunSnapshot snapshot() {
            return new StoryboardRunSnapshot(
                    runId, conversationId, stage, version, originalScript, selectedScript,
                    expansionScript, assessment, storyBible, roles, assets, pseudoShots, finalShots, error);
        }
    }

    public record ShotRecord(
            String runId,
            Phase phase,
            String segmentId,
            String shotId,
            int shotOrdinal,
            String status,
            String payload,
            List<String> referenceAssetIds,
            String taskId,
            String error,
            int attempt,
            String projectSceneId
    ) {
        public ShotRecord {
            referenceAssetIds = list(referenceAssetIds);
            status = text(status);
            payload = text(payload);
            taskId = text(taskId);
            error = text(error);
            projectSceneId = text(projectSceneId);
        }

        public ShotRecord(
                String runId,
                Phase phase,
                String segmentId,
                String shotId,
                int shotOrdinal,
                String status,
                String payload,
                List<String> referenceAssetIds,
                String taskId,
                String error,
                int attempt
        ) {
            this(runId, phase, segmentId, shotId, shotOrdinal, status, payload,
                    referenceAssetIds, taskId, error, attempt, "");
        }
    }

    public record ShotReferenceResolution(
            List<String> expectedAssetIds,
            List<String> assetIds,
            boolean fallbackUsed,
            boolean finalRecordFound,
            boolean pseudoRecordFound
    ) {
        public ShotReferenceResolution {
            expectedAssetIds = list(expectedAssetIds);
            assetIds = list(assetIds);
        }

        public boolean mismatch() {
            return fallbackUsed || expectedAssetIds.size() != assetIds.size();
        }
    }

    /**
     * 阶段 A：极简大纲（一次调用产出的全部内容）。
     *
     * <p>只要求一段剧情梗概 + N 行镜头大纲。大纲行复用 {@link PseudoShot}，但只填
     * {@code shotOrdinal / action（一句画面简述或台词）/ characters / location / referenceAssetIds}，
     * 其余字段留空——景别、运镜、提示词、时长全部留到阶段 B（审批后）由逐镜补全负责。
     *
     * <p>这么切的理由：阶段 A 的输出量直接决定用户等待时间。让模型写「整部剧本 + 13 字段伪分镜」
     * 是 84~253s 的主因；压成 N 行 40 字后，同一段可以进 20~40s。
     */
    public record OutlinePlan(String synopsis, StoryBible storyBible, List<PseudoShot> shots) {
        public OutlinePlan {
            synopsis = text(synopsis);
            storyBible = storyBible == null ? StoryBible.empty() : storyBible;
            shots = shots == null ? List.of() : List.copyOf(shots);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> list(List<String> value) {
        return value == null ? List.of() : value.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }
}
