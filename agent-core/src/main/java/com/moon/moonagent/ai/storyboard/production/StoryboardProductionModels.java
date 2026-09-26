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

    public record StoryboardProductionRequest(
            String userId,
            String projectId,
            String conversationId,
            String canvasId,
            String originalScript,
            String creationType,
            String requestedShotCount,
            List<AssetRef> assets,
            List<String> roleHints
    ) {
        public StoryboardProductionRequest {
            originalScript = text(originalScript);
            creationType = text(creationType);
            requestedShotCount = text(requestedShotCount);
            assets = assets == null ? List.of() : List.copyOf(assets);
            roleHints = list(roleHints);
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
            roles = roles == null ? List.of() : List.copyOf(roles);
            assets = assets == null ? List.of() : List.copyOf(assets);
            pseudoShots = pseudoShots == null ? List.of() : List.copyOf(pseudoShots);
            finalShots = finalShots == null ? List.of() : List.copyOf(finalShots);
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
