package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskPlanActionServiceTest {

    private final TaskPlanActionService service = new TaskPlanActionService();

    @Test
    void cancelsPlan() {
        TaskPlan result = service.apply(plan(TaskNodeState.RUNNING, TaskPlanState.RUNNING), "cancel");

        assertThat(result.state()).isEqualTo(TaskPlanState.CANCELLED);
    }

    @Test
    void approvesWaitingNode() {
        TaskPlan result = service.apply(plan(TaskNodeState.WAITING_HUMAN, TaskPlanState.WAITING_HUMAN), "approve");

        assertThat(result.state()).isEqualTo(TaskPlanState.RUNNING);
        assertThat(result.nodes().get(0).state()).isEqualTo(TaskNodeState.READY);
    }

    @Test
    void rejectsWaitingNode() {
        TaskPlan result = service.apply(plan(TaskNodeState.WAITING_HUMAN, TaskPlanState.WAITING_HUMAN), "reject");

        assertThat(result.state()).isEqualTo(TaskPlanState.FAILED);
        assertThat(result.nodes().get(0).state()).isEqualTo(TaskNodeState.FAILED);
    }

    @Test
    void retriesFailedNode() {
        TaskPlan result = service.apply(plan(TaskNodeState.FAILED, TaskPlanState.FAILED), "retry");

        assertThat(result.state()).isEqualTo(TaskPlanState.RUNNING);
        assertThat(result.nodes().get(0).state()).isEqualTo(TaskNodeState.READY);
    }

    @Test
    void approveWithoutWaitingNodeFailsWithoutChangingThePlan() {
        TaskPlan original = plan(TaskNodeState.RUNNING, TaskPlanState.RUNNING);

        assertThatThrownBy(() -> service.apply(original, "approve"))
                .isInstanceOf(InvalidTaskPlanTransitionException.class)
                .hasMessageContaining("no waiting human node");

        assertThat(original.state()).isEqualTo(TaskPlanState.RUNNING);
        assertThat(original.nodes().get(0).state()).isEqualTo(TaskNodeState.RUNNING);
    }

    @Test
    void retryWithoutFailedNodeFailsWithoutChangingThePlan() {
        TaskPlan original = plan(TaskNodeState.RUNNING, TaskPlanState.RUNNING);

        assertThatThrownBy(() -> service.apply(original, "retry"))
                .isInstanceOf(InvalidTaskPlanTransitionException.class)
                .hasMessageContaining("no failed node");

        assertThat(original.state()).isEqualTo(TaskPlanState.RUNNING);
    }

    @Test
    void replanRequiresPlannerBackedControllerFlow() {
        TaskPlan original = plan(TaskNodeState.FAILED, TaskPlanState.FAILED);

        assertThatThrownBy(() -> service.apply(original, "replan"))
                .isInstanceOf(InvalidTaskPlanTransitionException.class)
                .hasMessageContaining("planner");
    }

    private static TaskPlan plan(TaskNodeState nodeState, TaskPlanState planState) {
        TaskNode node = new TaskNode(
                "one", "intent-pic", "one", Map.of(), List.of(),
                nodeState, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", planState, 1,
                20, 3, 2, 0, List.of(node), List.of(), "", "");
    }
}
