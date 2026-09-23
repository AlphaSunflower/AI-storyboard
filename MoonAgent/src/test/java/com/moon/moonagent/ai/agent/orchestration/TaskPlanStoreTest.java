package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentTaskEdge;
import com.moon.moonagent.entity.AgentTaskNode;
import com.moon.moonagent.entity.AgentTaskPlan;
import com.moon.moonagent.mapper.AgentTaskEdgeMapper;
import com.moon.moonagent.mapper.AgentTaskNodeMapper;
import com.moon.moonagent.mapper.AgentTaskPlanMapper;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskEdge;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskPlanStoreTest {

    @Test
    void savesFullPlanJsonAndMirrorsExactNodesAndEdges() throws Exception {
        AgentTaskPlanMapper planMapper = mock(AgentTaskPlanMapper.class);
        AgentTaskNodeMapper nodeMapper = mock(AgentTaskNodeMapper.class);
        AgentTaskEdgeMapper edgeMapper = mock(AgentTaskEdgeMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TaskPlanStore store = new TaskPlanStore(planMapper, nodeMapper, edgeMapper, objectMapper);
        TaskPlan plan = plan();
        ArgumentCaptor<AgentTaskPlan> planRowCaptor = ArgumentCaptor.forClass(AgentTaskPlan.class);
        ArgumentCaptor<AgentTaskNode> nodeRowCaptor = ArgumentCaptor.forClass(AgentTaskNode.class);
        ArgumentCaptor<AgentTaskEdge> edgeRowCaptor = ArgumentCaptor.forClass(AgentTaskEdge.class);

        store.save(plan);

        verify(planMapper).insert(planRowCaptor.capture());
        assertThat(objectMapper.readValue(planRowCaptor.getValue().getPlanJson(), TaskPlan.class))
                .isEqualTo(plan);

        verify(nodeMapper, times(2)).insert(nodeRowCaptor.capture());
        List<AgentTaskNode> nodeRows = nodeRowCaptor.getAllValues();
        assertNodeRow(nodeRows.get(0), plan.planId(), plan.nodes().get(0));
        assertNodeRow(nodeRows.get(1), plan.planId(), plan.nodes().get(1));

        verify(edgeMapper).insert(edgeRowCaptor.capture());
        AgentTaskEdge edgeRow = edgeRowCaptor.getValue();
        assertThat(edgeRow.getId()).isNull();
        assertThat(edgeRow.getPlanId()).isEqualTo(plan.planId());
        assertThat(edgeRow.getFromNodeId()).isEqualTo(plan.edges().get(0).fromNodeId());
        assertThat(edgeRow.getToNodeId()).isEqualTo(plan.edges().get(0).toNodeId());
        assertThat(edgeRow.getCreatedAt()).isNull();
    }

    @Test
    void readsPlanFromPersistedJson() throws Exception {
        AgentTaskPlanMapper planMapper = mock(AgentTaskPlanMapper.class);
        AgentTaskNodeMapper nodeMapper = mock(AgentTaskNodeMapper.class);
        AgentTaskEdgeMapper edgeMapper = mock(AgentTaskEdgeMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTaskPlan row = new AgentTaskPlan();
        row.setId("plan-1");
        row.setConversationId("conversation-1");
        row.setPlanJson(objectMapper.writeValueAsString(plan()));
        when(planMapper.selectById("plan-1")).thenReturn(row);
        TaskPlanStore store = new TaskPlanStore(planMapper, nodeMapper, edgeMapper, objectMapper);

        Optional<TaskPlan> found = store.find("plan-1");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow()).isEqualTo(plan());
    }

    @ParameterizedTest
    @EnumSource(value = TaskPlanState.class, names = {"RUNNING", "WAITING_HUMAN", "WAITING_ASYNC"})
    void findActiveDeserializesActivePlan(TaskPlanState state) throws Exception {
        AgentTaskPlanMapper planMapper = mock(AgentTaskPlanMapper.class);
        AgentTaskNodeMapper nodeMapper = mock(AgentTaskNodeMapper.class);
        AgentTaskEdgeMapper edgeMapper = mock(AgentTaskEdgeMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TaskPlan plan = plan(state);
        AgentTaskPlan row = new AgentTaskPlan();
        row.setId(plan.planId());
        row.setConversationId(plan.conversationId());
        row.setStatus(state.name());
        row.setPlanJson(objectMapper.writeValueAsString(plan));
        when(planMapper.selectOne(any())).thenReturn(row);
        TaskPlanStore store = new TaskPlanStore(planMapper, nodeMapper, edgeMapper, objectMapper);

        Optional<TaskPlan> found = store.findActive(plan.conversationId());

        assertThat(found).contains(plan);
    }

    private static TaskPlan plan() {
        return plan(TaskPlanState.RUNNING);
    }

    private static TaskPlan plan(TaskPlanState state) {
        TaskNode first = new TaskNode(
                "plan", "intent-aisplit", "Build plan",
                Map.of("style", "cinematic", "count", 2), List.of(),
                TaskNodeState.READY, 1, HitlPolicy.BEFORE, EvaluationMode.DETERMINISTIC,
                "must contain scenes", "plan ready", "async-plan", "previous transient failure");
        TaskNode second = new TaskNode(
                "image", "intent-pic", "Generate image",
                Map.of("prompt", "hero shot", "width", 1024), List.of("plan"),
                TaskNodeState.WAITING_ASYNC, 2, HitlPolicy.AFTER, EvaluationMode.HYBRID,
                "must match prompt", "image queued", "async-image", "retry later");
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", state, 3,
                50, 4, 5, 2, List.of(first, second), List.of(new TaskEdge("plan", "image")),
                "plan warning", "signature-1");
    }

    private static void assertNodeRow(AgentTaskNode row, String planId, TaskNode node) {
        assertThat(row.getId()).isNull();
        assertThat(row.getPlanId()).isEqualTo(planId);
        assertThat(row.getNodeId()).isEqualTo(node.nodeId());
        assertThat(row.getCapabilityKey()).isEqualTo(node.capabilityKey());
        assertThat(row.getTitle()).isEqualTo(node.title());
        assertThat(row.getState()).isEqualTo(node.state().name());
        assertThat(row.getAttemptCount()).isEqualTo(node.attemptCount());
        assertThat(row.getHitlPolicy()).isEqualTo(node.hitlPolicy().name());
        assertThat(row.getEvaluationMode()).isEqualTo(node.evaluationMode().name());
        assertThat(row.getQualityCriteria()).isEqualTo(node.qualityCriteria());
        assertThat(row.getOutputSummary()).isEqualTo(node.outputSummary());
        assertThat(row.getAsyncTaskId()).isEqualTo(node.asyncTaskId());
        assertThat(row.getError()).isEqualTo(node.error());
        assertThat(row.getCreatedAt()).isNull();
        assertThat(row.getUpdatedAt()).isNull();
    }
}
