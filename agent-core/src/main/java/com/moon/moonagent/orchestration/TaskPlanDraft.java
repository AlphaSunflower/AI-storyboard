package com.moon.moonagent.orchestration;

import java.util.List;

public record TaskPlanDraft(
        String goal,
        List<TaskNode> nodes,
        List<TaskEdge> edges
) {
    public TaskPlanDraft {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
