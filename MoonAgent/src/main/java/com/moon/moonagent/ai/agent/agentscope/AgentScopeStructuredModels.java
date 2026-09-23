package com.moon.moonagent.ai.agent.agentscope;

import java.util.List;

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
}
