package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.capability.AgentCapabilityRegistry;
import com.moon.moonagent.ai.agent.orchestration.DynamicTaskEngine;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanActionService;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanPlanner;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanStore;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.dto.request.AgentTaskPlanActionRequest;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanScheduler;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskPlanControllerTest {

    @Test
    void approveRoutesPendingCheckpointThroughFormResumePath() {
        AgentChatService chatService = mock(AgentChatService.class);
        TaskPlanStore planStore = mock(TaskPlanStore.class);
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        AgentCapabilityRegistry capabilities = mock(AgentCapabilityRegistry.class);
        DynamicTaskEngine engine = mock(DynamicTaskEngine.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        CheckpointResumeService checkpointResumeService = mock(CheckpointResumeService.class);
        AgentConversation conversation = conversation();
        TaskPlan waiting = plan(TaskNodeState.WAITING_HUMAN, TaskPlanState.WAITING_HUMAN, "old");
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setFormToken("token-1");
        checkpoint.setConversationId("conversation-1");
        checkpoint.setPlanId("plan-1");
        checkpoint.setNodeId("node-1");
        checkpoint.setAction("generate_image");
        checkpoint.setPlan("{\"_actions\":[{\"id\":\"cancel\"},{\"id\":\"generate_image:2\"}]}");
        when(chatService.getOwnedConversation("user-1", "conversation-1")).thenReturn(conversation);
        when(planStore.find("plan-1")).thenReturn(Optional.of(waiting));
        when(planStore.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(checkpointMapper.selectOne(any())).thenReturn(checkpoint);
        AgentTaskPlanController controller = controller(
                chatService, planStore, planner, capabilities, engine,
                checkpointMapper, checkpointResumeService);

        controller.action(auth(), "conversation-1", "plan-1",
                new AgentTaskPlanActionRequest("approve"));

        verify(chatService).submitFormAndResume(eq("user-1"), eq("conversation-1"),
                eq("token-1"), eq(""), eq("generate_image:2"), eq(""), anyMap(), anyList(),
                eq(""), any(SseEmitter.class));
        verify(engine, never()).resumePlan(any(), any());
    }

    @Test
    void retryPersistsReadyNodeAndResumesWithoutCheckpoint() {
        AgentChatService chatService = mock(AgentChatService.class);
        TaskPlanStore planStore = mock(TaskPlanStore.class);
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        AgentCapabilityRegistry capabilities = mock(AgentCapabilityRegistry.class);
        DynamicTaskEngine engine = mock(DynamicTaskEngine.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        CheckpointResumeService checkpointResumeService = mock(CheckpointResumeService.class);
        AgentConversation conversation = conversation();
        TaskPlan failed = plan(TaskNodeState.FAILED, TaskPlanState.FAILED, "old");
        when(chatService.getOwnedConversation("user-1", "conversation-1")).thenReturn(conversation);
        when(planStore.find("plan-1")).thenReturn(Optional.of(failed));
        when(planStore.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        AgentTaskPlanController controller = controller(
                chatService, planStore, planner, capabilities, engine,
                checkpointMapper, checkpointResumeService);

        controller.action(auth(), "conversation-1", "plan-1",
                new AgentTaskPlanActionRequest("retry"));

        ArgumentCaptor<TaskPlan> planCaptor = ArgumentCaptor.forClass(TaskPlan.class);
        verify(engine).resumePlan(eq(conversation), planCaptor.capture());
        assertThat(planCaptor.getValue().state()).isEqualTo(TaskPlanState.RUNNING);
        assertThat(planCaptor.getValue().nodes().get(0).state()).isEqualTo(TaskNodeState.READY);
    }

    @Test
    void replanUsesPlannerAndPersistsFreshNodes() {
        AgentChatService chatService = mock(AgentChatService.class);
        TaskPlanStore planStore = mock(TaskPlanStore.class);
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        AgentCapabilityRegistry capabilities = mock(AgentCapabilityRegistry.class);
        DynamicTaskEngine engine = mock(DynamicTaskEngine.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        CheckpointResumeService checkpointResumeService = mock(CheckpointResumeService.class);
        AgentConversation conversation = conversation();
        TaskPlan failed = plan(TaskNodeState.FAILED, TaskPlanState.FAILED, "old");
        TaskPlan fresh = new TaskPlan(
                "fresh-plan", "conversation-1", "replanned", TaskPlanState.READY, 1,
                20, 3, 2, 0,
                List.of(node("fresh", TaskNodeState.PENDING)),
                List.of(), "", "");
        when(chatService.getOwnedConversation("user-1", "conversation-1")).thenReturn(conversation);
        when(planStore.find("plan-1")).thenReturn(Optional.of(failed));
        when(planStore.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(planner.plan(any(), any())).thenReturn(fresh);
        when(capabilities.descriptors()).thenReturn(Map.of());
        AgentTaskPlanController controller = controller(
                chatService, planStore, planner, capabilities, engine,
                checkpointMapper, checkpointResumeService);

        controller.action(auth(), "conversation-1", "plan-1",
                new AgentTaskPlanActionRequest("replan"));

        verify(planner).plan(any(), eq(Map.of()));
        ArgumentCaptor<TaskPlan> planCaptor = ArgumentCaptor.forClass(TaskPlan.class);
        verify(planStore).update(planCaptor.capture());
        assertThat(planCaptor.getValue().planId()).isEqualTo("plan-1");
        assertThat(planCaptor.getValue().nodes()).extracting(TaskNode::nodeId).containsExactly("fresh");
        assertThat(planCaptor.getValue().replanCount()).isEqualTo(1);
        verify(engine).resumePlan(eq(conversation), any(TaskPlan.class));
    }

    private static AgentTaskPlanController controller(AgentChatService chatService,
                                                       TaskPlanStore planStore,
                                                       TaskPlanPlanner planner,
                                                       AgentCapabilityRegistry capabilities,
                                                       DynamicTaskEngine engine,
                                                       AgentCheckpointMapper checkpointMapper,
                                                       CheckpointResumeService checkpointResumeService) {
        return new AgentTaskPlanController(chatService, planStore, new TaskPlanActionService(),
                planner, capabilities, new TaskPlanScheduler(), engine, checkpointMapper,
                checkpointResumeService);
    }

    private static Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user-1");
        return auth;
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        return conversation;
    }

    private static TaskPlan plan(TaskNodeState nodeState, TaskPlanState planState, String id) {
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", planState, 1,
                20, 3, 2, 0, List.of(node(id, nodeState)), List.of(), "", "");
    }

    private static TaskNode node(String id, TaskNodeState state) {
        return new TaskNode(
                id, "intent-pic", id, Map.of(), List.of(),
                state, 0, HitlPolicy.BEFORE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
    }
}
