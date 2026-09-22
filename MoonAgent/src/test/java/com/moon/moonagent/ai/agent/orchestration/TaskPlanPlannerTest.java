package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.CapabilityDescriptor;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskEdge;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanDraft;
import com.moon.moonagent.orchestration.TaskPlanState;
import com.moon.moonagent.orchestration.TaskPlanValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanPlannerTest {

    @Test
    void repairsInvalidDraftOnceUsingValidatorErrors() {
        List<List<String>> repairErrors = new ArrayList<>();
        TaskPlanSynthesizer synthesizer = (request, capabilities, errors) -> {
            repairErrors.add(errors);
            if (errors.isEmpty()) {
                return new TaskPlanDraft("goal", List.of(node("bad", "intent-missing")), List.of());
            }
            return new TaskPlanDraft("goal", List.of(node("plan", "intent-aisplit")), List.of());
        };
        TaskPlanPlanner planner = new TaskPlanPlanner(synthesizer, new TaskPlanValidator());

        TaskPlan plan = planner.plan(request(), capabilities());

        assertThat(plan.state()).isEqualTo(TaskPlanState.READY);
        assertThat(plan.nodes()).extracting(TaskNode::capabilityKey).containsExactly("intent-aisplit");
        assertThat(repairErrors).hasSize(2);
        assertThat(repairErrors.get(1)).contains("unknown capability: intent-missing");
    }

    @Test
    void fallsBackToConfirmedSingleCapabilityPlanAfterSecondFailure() {
        TaskPlanSynthesizer synthesizer = (request, capabilities, errors) ->
                new TaskPlanDraft("goal", List.of(node("bad", "intent-missing")), List.of());
        TaskPlanPlanner planner = new TaskPlanPlanner(synthesizer, new TaskPlanValidator());

        TaskPlan plan = planner.plan(request(), capabilities());

        assertThat(plan.state()).isEqualTo(TaskPlanState.WAITING_HUMAN);
        assertThat(plan.nodes()).hasSize(1);
        assertThat(plan.nodes().get(0).capabilityKey()).isEqualTo("intent-aisplit");
        assertThat(plan.nodes().get(0).hitlPolicy()).isEqualTo(HitlPolicy.BEFORE);
    }

    private static TaskPlanningRequest request() {
        return new TaskPlanningRequest(
                "conversation-1", "先做分镜，再生成图片", "intent-aisplit",
                20, 3, 2);
    }

    private static Map<String, CapabilityDescriptor> capabilities() {
        return Map.of(
                "intent-aisplit", descriptor("intent-aisplit"),
                "intent-pic", descriptor("intent-pic"));
    }

    private static CapabilityDescriptor descriptor(String key) {
        return new CapabilityDescriptor(
                key, key, key, List.of(), false, HitlPolicy.NONE,
                EvaluationMode.HYBRID, 3);
    }

    private static TaskNode node(String nodeId, String capabilityKey) {
        return new TaskNode(
                nodeId, capabilityKey, nodeId, Map.of(), List.of(),
                com.moon.moonagent.orchestration.TaskNodeState.PENDING, 0,
                HitlPolicy.NONE, EvaluationMode.HYBRID, "", "", "", "");
    }
}
