package com.moon.moonagent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanSchedulerTest {

    private final TaskPlanScheduler scheduler = new TaskPlanScheduler();

    @Test
    void marksRootNodesReadyAndSelectsTheFirstReadyNode() {
        TaskPlan plan = plan(
                node("plan", "intent-aisplit", TaskNodeState.PENDING, List.of(), 0),
                node("image", "intent-pic", TaskNodeState.PENDING, List.of("plan"), 0));

        TaskPlan advanced = scheduler.advance(plan);

        assertThat(advanced.nodes().get(0).state()).isEqualTo(TaskNodeState.READY);
        assertThat(advanced.nodes().get(1).state()).isEqualTo(TaskNodeState.PENDING);
        assertThat(scheduler.nextReady(advanced)).get().extracting(TaskNode::nodeId).isEqualTo("plan");
    }

    @Test
    void unlocksDependentsAfterCompletion() {
        TaskPlan plan = plan(
                node("plan", "intent-aisplit", TaskNodeState.READY, List.of(), 0),
                node("image", "intent-pic", TaskNodeState.PENDING, List.of("plan"), 0));

        TaskPlan completed = scheduler.completeNode(plan, "plan", "written");
        TaskPlan advanced = scheduler.advance(completed);

        assertThat(advanced.nodes().get(1).state()).isEqualTo(TaskNodeState.READY);
    }

    @Test
    void retriesFailedNodeUntilBudgetIsExhausted() {
        TaskPlan plan = plan(
                node("one", "intent-pic", TaskNodeState.READY, List.of(), 1));

        TaskPlan retried = scheduler.failNode(plan, "one", "network");

        assertThat(retried.nodes().get(0).state()).isEqualTo(TaskNodeState.READY);
        assertThat(retried.nodes().get(0).attemptCount()).isEqualTo(2);
        assertThat(retried.nodes().get(0).error()).isEqualTo("network");

        TaskPlan failed = scheduler.failNode(retried, "one", "network");

        assertThat(failed.nodes().get(0).state()).isEqualTo(TaskNodeState.FAILED);
        assertThat(failed.nodes().get(0).attemptCount()).isEqualTo(3);
    }

    @Test
    void skipsDependentsWhenNodeFailsPermanently() {
        TaskPlan plan = plan(
                node("one", "intent-pic", TaskNodeState.READY, List.of(), 2),
                node("two", "intent-video", TaskNodeState.PENDING, List.of("one"), 0));

        TaskPlan failed = scheduler.advance(scheduler.failNode(plan, "one", "fatal"));

        assertThat(failed.nodes().get(0).state()).isEqualTo(TaskNodeState.FAILED);
        assertThat(failed.nodes().get(1).state()).isEqualTo(TaskNodeState.SKIPPED);
    }

    @Test
    void replanReplacesOnlyUnfinishedNodesAndPreservesCompletedArtifacts() {
        TaskPlan plan = plan(
                node("one", "intent-aisplit", TaskNodeState.COMPLETED, List.of(), 1),
                node("two", "intent-pic", TaskNodeState.PENDING, List.of("one"), 0));
        TaskNode replacement = node("two", "intent-video", TaskNodeState.PENDING, List.of("one"), 0);

        TaskPlan replanned = scheduler.replan(plan, List.of(replacement), List.of(new TaskEdge("one", "two")));

        assertThat(replanned.nodes()).extracting(TaskNode::nodeId).containsExactly("one", "two");
        assertThat(replanned.nodes().get(0).state()).isEqualTo(TaskNodeState.COMPLETED);
        assertThat(replanned.nodes().get(1).capabilityKey()).isEqualTo("intent-video");
        assertThat(replanned.version()).isEqualTo(2);
        assertThat(replanned.replanCount()).isEqualTo(1);
    }

    @Test
    void recoversInterruptedRunningNodeAsReady() {
        TaskPlan plan = plan(
                node("one", "intent-pic", TaskNodeState.RUNNING, List.of(), 1));

        TaskPlan recovered = scheduler.recoverInterrupted(plan);

        assertThat(recovered.state()).isEqualTo(TaskPlanState.RUNNING);
        assertThat(recovered.nodes().get(0).state()).isEqualTo(TaskNodeState.READY);
        assertThat(recovered.nodes().get(0).attemptCount()).isEqualTo(1);
    }

    private static TaskPlan plan(TaskNode... nodes) {
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.READY, 1,
                20, 3, 2, 0, List.of(nodes), List.of(), "", "");
    }

    private static TaskNode node(String nodeId, String capabilityKey, TaskNodeState state,
                                 List<String> dependencies, int attempts) {
        return new TaskNode(
                nodeId, capabilityKey, nodeId, Map.of(), dependencies,
                state, attempts, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
    }
}
