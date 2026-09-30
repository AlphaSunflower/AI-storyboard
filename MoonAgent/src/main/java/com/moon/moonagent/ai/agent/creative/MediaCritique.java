package com.moon.moonagent.ai.agent.creative;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record MediaCritique(
        String id,
        String projectId,
        String conversationId,
        String turnId,
        String itemId,
        String mediaType,
        String assetUrl,
        String decision,
        boolean hardConstraintPass,
        Map<String, Double> scores,
        List<String> violations,
        List<String> reasons,
        int repairAttempt,
        OffsetDateTime createdAt
) {
    public MediaCritique {
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        violations = violations == null ? List.of() : List.copyOf(violations);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean repairRequired() {
        return "REPAIR".equalsIgnoreCase(decision) || "ASK".equalsIgnoreCase(decision);
    }
}
