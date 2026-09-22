package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskPlanRecoveryWorkerTest {

    @Test
    void resumesRecoverablePlanForActiveConversation() {
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        DynamicTaskEngine engine = mock(DynamicTaskEngine.class);
        TaskPlan plan = plan();
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        when(store.findRecoverable(20)).thenReturn(List.of(plan));
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);

        new TaskPlanRecoveryWorker(store, conversationMapper, engine).recover();

        verify(engine).resumePlan(conversation, plan);
    }

    private static TaskPlan plan() {
        TaskNode node = new TaskNode(
                "one", "intent-pic", "one", Map.of(), List.of(),
                TaskNodeState.READY, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.READY, 1,
                20, 3, 2, 0, List.of(node), List.of(), "", "");
    }
}
