package com.moon.moonagent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanValidatorTest {

    private final TaskPlanValidator validator = new TaskPlanValidator();

    @Test
    void rejectsUnknownCapabilities() {
        TaskPlan plan = plan(
                node("write", "intent-missing", List.of()),
                List.of());

        TaskPlanValidationResult result = validator.validate(plan, Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("unknown capability: intent-missing");
    }

    @Test
    void rejectsMissingDependencies() {
        TaskPlan plan = plan(
                node("write", "intent-aisplit", List.of("missing")),
                List.of());

        TaskPlanValidationResult result = validator.validate(plan, Map.of(
                "intent-aisplit", descriptor("intent-aisplit")));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("missing dependency: write -> missing");
    }

    @Test
    void rejectsCycles() {
        TaskPlan plan = plan(
                List.of(
                        node("a", "intent-aisplit", List.of("b")),
                        node("b", "intent-pic", List.of("a"))),
                List.of(edge("a", "b"), edge("b", "a")));

        TaskPlanValidationResult result = validator.validate(plan, Map.of(
                "intent-aisplit", descriptor("intent-aisplit"),
                "intent-pic", descriptor("intent-pic")));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.startsWith("cycle detected:"));
    }

    @Test
    void rejectsPlansBeyondNodeBudget() {
        TaskPlan plan = plan(
                List.of(
                        node("one", "intent-aisplit", List.of()),
                        node("two", "intent-pic", List.of()),
                        node("three", "intent-video", List.of())),
                List.of());
        TaskPlan limited = plan.withBudget(2, 3, 2);

        TaskPlanValidationResult result = validator.validate(limited, Map.of(
                "intent-aisplit", descriptor("intent-aisplit"),
                "intent-pic", descriptor("intent-pic"),
                "intent-video", descriptor("intent-video")));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("node budget exceeded: 3 > 2");
    }

    private static TaskPlan plan(TaskNode node, List<TaskEdge> edges) {
        return plan(List.of(node), edges);
    }

    private static TaskPlan plan(List<TaskNode> nodes, List<TaskEdge> edges) {
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.DRAFT, 1,
                20, 3, 2, 0, nodes, edges, "", "");
    }

    private static TaskNode node(String nodeId, String capabilityKey, List<String> dependencies) {
        return new TaskNode(
                nodeId, capabilityKey, nodeId, Map.of(), dependencies,
                TaskNodeState.PENDING, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
    }

    private static TaskEdge edge(String from, String to) {
        return new TaskEdge(from, to);
    }

    private static CapabilityDescriptor descriptor(String key) {
        return new CapabilityDescriptor(
                key, key, "", List.of(), false, HitlPolicy.NONE,
                EvaluationMode.DETERMINISTIC, 3);
    }
}
