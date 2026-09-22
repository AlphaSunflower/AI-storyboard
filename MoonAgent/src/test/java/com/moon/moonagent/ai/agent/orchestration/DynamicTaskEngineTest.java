package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.capability.AgentCapability;
import com.moon.moonagent.ai.agent.capability.AgentCapabilityRegistry;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentLoopRun;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.orchestration.CapabilityDescriptor;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskEdge;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanScheduler;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicTaskEngineTest {

    @Test
    void executesReadyNodesInDependencyOrderAndReturnsLastMessage() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability first = capability("intent-aisplit", "first");
        AgentCapability second = capability("intent-pic", "second");
        TaskPlan plan = plan();

        when(planner.plan(any(), any())).thenReturn(plan);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, CapabilityDescriptor> descriptors = Map.of(
                "intent-aisplit", new CapabilityDescriptor(
                        "intent-aisplit", "first", "first", List.of(), false,
                        HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3),
                "intent-pic", new CapabilityDescriptor(
                        "intent-pic", "second", "second", List.of(), false,
                        HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3));
        when(registry.descriptors()).thenReturn(descriptors);
        when(registry.find("intent-aisplit")).thenReturn(Optional.of(first));
        when(registry.find("intent-pic")).thenReturn(Optional.of(second));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);

        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        OrchestrationRequest request = new OrchestrationRequest(conversation, "goal", List.of(), null);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        AgentLoopRun run = new AgentLoopRun();
        run.setId("run-1");

        AgentTurnResult result = engine.runTurn(conversation, "goal", "intent-aisplit",
                request, trace, run);

        InOrder order = inOrder(first, second);
        order.verify(first).execute(any());
        order.verify(second).execute(any());
        assertThat(result.content()).isEqualTo("second");
    }

    @Test
    void continuesDependentNodeAfterAsyncCompletion() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability next = capability("intent-pic", "continued");
        TaskPlan waiting = new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.WAITING_ASYNC, 1,
                20, 3, 2, 0,
                List.of(
                        new TaskNode("one", "intent-video", "one", Map.of(), List.of(),
                                TaskNodeState.WAITING_ASYNC, 1, HitlPolicy.NONE,
                                EvaluationMode.DETERMINISTIC, "", "queued video task", "task-1", ""),
                        new TaskNode("two", "intent-pic", "two", Map.of(), List.of("one"),
                                TaskNodeState.PENDING, 0, HitlPolicy.NONE,
                                EvaluationMode.DETERMINISTIC, "", "", "", "")),
                List.of(new TaskEdge("one", "two")), "", "");

        java.util.concurrent.atomic.AtomicReference<TaskPlan> current =
                new java.util.concurrent.atomic.AtomicReference<>(waiting);
        when(store.find("plan-1")).thenAnswer(invocation -> Optional.of(current.get()));
        when(store.update(any())).thenAnswer(invocation -> {
            TaskPlan updated = invocation.getArgument(0);
            current.set(updated);
            return updated;
        });
        when(registry.find("intent-pic")).thenReturn(Optional.of(next));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);

        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");

        engine.continueAfterAsync(conversation, "plan-1", "one", true, "video-url");

        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> contextCaptor =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        verify(next).execute(contextCaptor.capture());
        assertThat(contextCaptor.getValue().previousResult()).isNotNull();
        assertThat(contextCaptor.getValue().previousResult().message()).isEqualTo("video-url");
    }

    @Test
    void usesNodeParameterOverridesInsteadOfRootRequestContent() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability capability = capability("intent-pic", "done");
        TaskNode node = new TaskNode(
                "one", "intent-pic", "one", Map.of("prompt", "node prompt"), List.of(),
                TaskNodeState.PENDING, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
        TaskPlan plan = new TaskPlan(
                "plan-1", "conversation-1", "root goal", TaskPlanState.READY, 1,
                20, 3, 2, 0, List.of(node), List.of(), "", "");
        when(planner.plan(any(), any())).thenReturn(plan);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        java.util.concurrent.atomic.AtomicReference<TaskPlan> current =
                new java.util.concurrent.atomic.AtomicReference<>(plan);
        when(store.find("plan-1")).thenAnswer(invocation -> Optional.of(current.get()));
        when(store.update(any())).thenAnswer(invocation -> {
            TaskPlan updated = invocation.getArgument(0);
            current.set(updated);
            return updated;
        });
        CapabilityDescriptor descriptor = capability.descriptor();
        when(registry.descriptors()).thenReturn(Map.of("intent-pic", descriptor));
        when(registry.find("intent-pic")).thenReturn(Optional.of(capability));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);
        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        OrchestrationRequest request = new OrchestrationRequest(conversation, "root request", List.of(), null);

        engine.runTurn(conversation, "root request", "intent-pic",
                request, new AgentTraceAccumulator(request), new AgentLoopRun());

        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> contextCaptor =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        verify(capability).execute(contextCaptor.capture());
        assertThat(contextCaptor.getValue().userMessage()).isEqualTo("node prompt");
    }

    @Test
    void continueAfterAsyncSkipsMutationWhenConversationLockIsHeld() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        ConversationLock lock = mock(ConversationLock.class);
        when(lock.tryAcquire("conversation-1")).thenReturn(false);
        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        ReflectionTestUtils.setField(engine, "conversationLock", lock);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");

        engine.continueAfterAsync(conversation, "plan-1", "one", true, "url");

        verify(store, never()).find("plan-1");
        verify(store, never()).update(any());
    }

    @Test
    void reloadsPlanAndStopsBeforeStepWhenCancelled() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        TaskPlan cancelled = new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.CANCELLED, 1,
                20, 3, 2, 0, plan().nodes(), plan().edges(), "", "");
        when(store.find("plan-1")).thenReturn(Optional.of(cancelled));
        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");

        engine.resumePlan(conversation, plan());

        verify(registry, never()).find(any());
        verify(store, times(1)).find("plan-1");
    }

    private static AgentCapability capability(String key, String message) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                key, key, key, List.of(), false, HitlPolicy.NONE,
                EvaluationMode.DETERMINISTIC, 3);
        AgentCapability capability = mock(AgentCapability.class);
        when(capability.key()).thenReturn(key);
        when(capability.descriptor()).thenReturn(descriptor);
        when(capability.execute(any())).thenReturn(SpecialistResult.done(message));
        return capability;
    }

    private static TaskPlan plan() {
        TaskNode first = new TaskNode(
                "one", "intent-aisplit", "one", Map.of(), List.of(),
                TaskNodeState.PENDING, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
        TaskNode second = new TaskNode(
                "two", "intent-pic", "two", Map.of(), List.of("one"),
                TaskNodeState.PENDING, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.READY, 1,
                20, 3, 2, 0, List.of(first, second), List.of(new TaskEdge("one", "two")),
                "", "");
    }
}
