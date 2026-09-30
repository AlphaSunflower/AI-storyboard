package com.moon.moonagent.ai.agent.agentscope.workflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AgentWorkflowModels {

    private AgentWorkflowModels() {
    }

    public enum Mode {
        AUTO_SKILL,
        USER_DEFINED_WORKFLOW,
        HYBRID
    }

    public enum StepStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record Constraint(
            boolean serial,
            boolean noSkip,
            boolean requireMarkers,
            List<Integer> exactCounts
    ) {
        public Constraint {
            exactCounts = exactCounts == null ? List.of() : List.copyOf(exactCounts);
        }
    }

    public record StepSpec(
            String key,
            String title,
            String toolName,
            List<String> dependsOn,
            int repeatCount,
            String marker
    ) {
        public StepSpec {
            key = text(key);
            title = text(title);
            toolName = text(toolName);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            repeatCount = Math.max(1, repeatCount);
            marker = text(marker);
        }
    }

    public record Plan(
            String runId,
            Mode mode,
            Constraint constraint,
            List<StepView> steps
    ) {
        public Plan {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record StepView(
            String id,
            int index,
            String key,
            String title,
            String toolName,
            StepStatus status,
            List<String> dependsOn,
            int repeatCount,
            int completedCount,
            String marker,
            String error
    ) {
        public StepView {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    public record Resolution(
            Mode mode,
            Constraint constraint,
            List<StepSpec> steps,
            String rationale
    ) {
        public Resolution {
            constraint = constraint == null ? new Constraint(false, false, false, List.of()) : constraint;
            steps = steps == null ? List.of() : List.copyOf(steps);
            rationale = text(rationale);
        }
    }

    public record ActiveWorkflow(
            Plan plan,
            Map<String, Object> metadata,
            OffsetDateTime updatedAt
    ) {
        public ActiveWorkflow {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
