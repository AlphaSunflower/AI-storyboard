package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskPlanScheduler;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskPlanRecoveryWorkerTest {

    @Test
    void resumesRecoverablePlanForActiveConversation() {
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        TaskPlanScheduler scheduler = mock(TaskPlanScheduler.class);
        DynamicTaskEngine engine = mock(DynamicTaskEngine.class);
        TaskPlan plan = plan();
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        when(store.findRecoverable(20)).thenReturn(List.of(plan));
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(scheduler.recoverInterrupted(plan)).thenReturn(plan);

        new TaskPlanRecoveryWorker(store, conversationMapper, assetMapper, scheduler, engine).recover();

        verify(engine).resumePlan(conversation, plan);
    }

    @Test
    void continuesWaitingAsyncPlanWhenGatewayAssetIsTerminal() {
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        TaskPlanScheduler scheduler = mock(TaskPlanScheduler.class);
        DynamicTaskEngine engine = mock(DynamicTaskEngine.class);
        TaskPlan plan = asyncPlan();
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        AgentAsset asset = new AgentAsset();
        asset.setStatus("completed");
        asset.setUrl("/api/files/videos/video.mp4");
        when(store.findRecoverable(20)).thenReturn(List.of(plan));
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(assetMapper.selectOne(any())).thenReturn(asset);

        new TaskPlanRecoveryWorker(store, conversationMapper, assetMapper, scheduler, engine).recover();

        verify(engine).continueAfterAsync(conversation, "plan-1", "one", true,
                "/api/files/videos/video.mp4");
        verify(engine, never()).resumePlan(any(), any());
    }

    @Test
    void skipsWaitingAsyncPlanWhileGatewayAssetIsStillRunning() {
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        TaskPlanScheduler scheduler = mock(TaskPlanScheduler.class);
        DynamicTaskEngine engine = mock(DynamicTaskEngine.class);
        TaskPlan plan = asyncPlan();
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        AgentAsset asset = new AgentAsset();
        asset.setStatus("running");
        when(store.findRecoverable(20)).thenReturn(List.of(plan));
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(assetMapper.selectOne(any())).thenReturn(asset);

        new TaskPlanRecoveryWorker(store, conversationMapper, assetMapper, scheduler, engine).recover();

        verify(engine, never()).continueAfterAsync(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(engine, never()).resumePlan(any(), any());
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

    private static TaskPlan asyncPlan() {
        TaskNode node = new TaskNode(
                "one", "intent-video", "one", Map.of(), List.of(),
                TaskNodeState.WAITING_ASYNC, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "task-1", "");
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.WAITING_ASYNC, 1,
                20, 3, 2, 0, List.of(node), List.of(), "", "");
    }
}
