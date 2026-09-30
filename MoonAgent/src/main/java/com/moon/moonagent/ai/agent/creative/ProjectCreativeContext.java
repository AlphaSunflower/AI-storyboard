package com.moon.moonagent.ai.agent.creative;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ProjectCreativeContext(
        String projectId,
        String userId,
        String objective,
        String creativeDirection,
        String visualStyle,
        List<Reference> references,
        List<ConstraintLedger.ConstraintItem> hardConstraints,
        List<ConstraintLedger.ConstraintItem> softConstraints,
        List<String> approvedAssetIds,
        List<String> rejectedDirections,
        List<String> feedback,
        Map<String, String> modelPreferences,
        long version,
        OffsetDateTime updatedAt
) {
    public ProjectCreativeContext {
        references = references == null ? List.of() : List.copyOf(references);
        hardConstraints = hardConstraints == null ? List.of() : List.copyOf(hardConstraints);
        softConstraints = softConstraints == null ? List.of() : List.copyOf(softConstraints);
        approvedAssetIds = approvedAssetIds == null ? List.of() : List.copyOf(approvedAssetIds);
        rejectedDirections = rejectedDirections == null ? List.of() : List.copyOf(rejectedDirections);
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
        modelPreferences = modelPreferences == null ? Map.of() : Map.copyOf(modelPreferences);
    }

    public record Reference(
            String assetId,
            String role,
            String url,
            String note
    ) {
        public Reference {
            assetId = text(assetId);
            role = text(role);
            url = text(url);
            note = text(note);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
