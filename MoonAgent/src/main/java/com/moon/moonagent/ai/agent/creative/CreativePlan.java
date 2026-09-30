package com.moon.moonagent.ai.agent.creative;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record CreativePlan(
        String runId,
        String mode,
        String status,
        boolean serial,
        boolean noSkip,
        boolean requireMarkers,
        List<Step> steps,
        List<String> hardConstraints,
        List<String> softConstraints,
        OffsetDateTime updatedAt
) {
    public CreativePlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        hardConstraints = hardConstraints == null ? List.of() : List.copyOf(hardConstraints);
        softConstraints = softConstraints == null ? List.of() : List.copyOf(softConstraints);
    }

    public record Step(
            String id,
            int index,
            String title,
            String toolName,
            String status,
            List<String> dependsOn,
            int repeatCount,
            int completedCount,
            String marker,
            String error
    ) {
        public Step {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }
}
