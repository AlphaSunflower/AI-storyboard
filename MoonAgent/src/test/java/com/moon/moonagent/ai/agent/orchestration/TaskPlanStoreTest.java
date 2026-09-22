package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void savesPlanJsonAndMirrorsNodesAndEdges() {
        AgentTaskPlanMapper planMapper = mock(AgentTaskPlanMapper.class);
        AgentTaskNodeMapper nodeMapper = mock(AgentTaskNodeMapper.class);
        AgentTaskEdgeMapper edgeMapper = mock(AgentTaskEdgeMapper.class);
        TaskPlanStore store = new TaskPlanStore(planMapper, nodeMapper, edgeMapper, new ObjectMapper());
        TaskPlan plan = plan();

        store.save(plan);

        verify(planMapper).insert(any(AgentTaskPlan.class));
        verify(nodeMapper, times(2)).insert(any(com.moon.moonagent.entity.AgentTaskNode.class));
        verify(edgeMapper).insert(any(com.moon.moonagent.entity.AgentTaskEdge.class));
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
        assertThat(found.orElseThrow().goal()).isEqualTo("goal");
        assertThat(found.orElseThrow().nodes()).hasSize(2);
    }

    private static TaskPlan plan() {
        TaskNode first = new TaskNode(
                "plan", "intent-aisplit", "plan", Map.of(), List.of(),
                TaskNodeState.READY, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
        TaskNode second = new TaskNode(
                "image", "intent-pic", "image", Map.of(), List.of("plan"),
                TaskNodeState.PENDING, 0, HitlPolicy.NONE, EvaluationMode.HYBRID,
                "quality", "", "", "");
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.READY, 1,
                20, 3, 2, 0, List.of(first, second), List.of(new TaskEdge("plan", "image")),
                "", "");
    }
}
