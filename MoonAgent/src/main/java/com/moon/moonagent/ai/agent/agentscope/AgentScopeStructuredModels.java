package com.moon.moonagent.ai.agent.agentscope;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentScopeStructuredModels {

    private AgentScopeStructuredModels() {
    }

    public record GenerationSpec(
            String mediaType,
            String prompt,
            String size,
            String quality,
            String model,
            String duration,
            String aspectRatio,
            List<String> referenceAssetIds,
            double confidence,
            String rationale
    ) {
        public GenerationSpec {
            referenceAssetIds = referenceAssetIds == null ? List.of() : List.copyOf(referenceAssetIds);
        }
    }

    public record ReferenceSelection(
            List<String> assetIds,
            double confidence,
            String rationale
    ) {
        public ReferenceSelection {
            assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        }
    }

    public record RiskDecision(
            String decision,
            String reason,
            String question
    ) {
    }

    public record MediaActionReview(
            String decision,
            Map<String, Object> repairedInput,
            String question,
            double confidence,
            List<String> reasons
    ) {
        public MediaActionReview {
            decision = decision == null ? "EXECUTE" : decision.trim().toUpperCase();
            repairedInput = repairedInput == null
                    ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(repairedInput));
            question = question == null ? "" : question.trim();
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record SubagentResult(
            String summary,
            List<String> assetIds,
            List<String> unresolved
    ) {
        public SubagentResult {
            assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }
    }

    public record WorkflowDecision(
            String mode,
            boolean serial,
            boolean noSkip,
            boolean requireMarkers,
            List<Integer> exactCounts,
            List<WorkflowStepDraft> steps,
            double confidence,
            String rationale
    ) {
        public WorkflowDecision {
            mode = mode == null ? "" : mode.trim().toUpperCase();
            exactCounts = exactCounts == null ? List.of() : List.copyOf(exactCounts);
            steps = steps == null ? List.of() : List.copyOf(steps);
            rationale = rationale == null ? "" : rationale.trim();
        }
    }

    public record WorkflowStepDraft(
            String key,
            String title,
            String toolName,
            List<String> dependsOn,
            int repeatCount,
            String marker
    ) {
        public WorkflowStepDraft {
            key = key == null ? "" : key.trim();
            title = title == null ? "" : title.trim();
            toolName = toolName == null ? "" : toolName.trim();
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            repeatCount = Math.max(1, repeatCount);
            marker = marker == null ? "" : marker.trim();
        }
    }
}
