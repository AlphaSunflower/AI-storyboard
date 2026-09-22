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
import static org.mockito.ArgumentMatchers.eq;
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
        AgentCapability first = capability("intent-aisplit", "![生成图片](/api/files/images/generated.png)");
        AgentCapability second = capability("intent-video", "second");
        TaskPlan plan = plan();

        when(planner.plan(any(), any())).thenReturn(plan);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, CapabilityDescriptor> descriptors = Map.of(
                "intent-aisplit", new CapabilityDescriptor(
                        "intent-aisplit", "first", "first", List.of(), false,
                        HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3),
                "intent-video", new CapabilityDescriptor(
                        "intent-video", "second", "second", List.of(), true,
                        HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3));
        when(registry.descriptors()).thenReturn(descriptors);
        when(registry.find("intent-aisplit")).thenReturn(Optional.of(first));
        when(registry.find("intent-video")).thenReturn(Optional.of(second));
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
        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> firstContext =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> secondContext =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        order.verify(first).execute(firstContext.capture());
        order.verify(second).execute(secondContext.capture());
        assertThat(result.content()).contains("接下来执行第 2/2 步").contains("second");
        assertThat(firstContext.getValue().directExecution()).isTrue();
        assertThat(secondContext.getValue().picUrls()).containsExactly("/api/files/images/generated.png");
        verify(recorder).step(eq(run), eq(0), eq("intent-aisplit"), eq("task_node"),
                eq("completed"), eq("CONTINUE"), eq("plan-1"), eq("one"), eq(1),
                eq(""), any(), eq(""), eq(""), eq(""), any(), any());
        verify(recorder).step(eq(run), eq(1), eq("intent-video"), eq("task_node"),
                eq("completed"), eq("CONTINUE"), eq("plan-1"), eq("two"), eq(1),
                eq(""), any(), eq(""), eq(""), eq(""), any(), any());
    }

    @Test
    void failedNodeStopsPlanAndDoesNotExecuteDependents() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability first = mock(AgentCapability.class);
        AgentCapability second = capability("intent-video", "should not run");
        TaskPlan plan = plan();
        CapabilityDescriptor firstDescriptor = new CapabilityDescriptor(
                "intent-aisplit", "first", "first", List.of(), false,
                HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3);
        CapabilityDescriptor secondDescriptor = new CapabilityDescriptor(
                "intent-video", "second", "second", List.of(), true,
                HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3);
        when(first.key()).thenReturn("intent-aisplit");
        when(first.descriptor()).thenReturn(firstDescriptor);
        when(first.execute(any())).thenReturn(SpecialistResult.failed("tool unavailable"));
        when(planner.plan(any(), any())).thenReturn(plan);
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(registry.descriptors()).thenReturn(Map.of(
                "intent-aisplit", firstDescriptor,
                "intent-video", secondDescriptor));
        when(registry.find("intent-aisplit")).thenReturn(Optional.of(first));
        when(registry.find("intent-video")).thenReturn(Optional.of(second));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);
        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        OrchestrationRequest request = new OrchestrationRequest(conversation, "goal", List.of(), null);

        AgentTurnResult result = engine.runTurn(conversation, "goal", "intent-aisplit",
                request, new AgentTraceAccumulator(request), new AgentLoopRun());

        assertThat(result.content()).contains("tool unavailable");
        verify(first).execute(any());
        verify(second, never()).execute(any());
    }

    @Test
    void resumedVideoNodeReceivesImageFromCompletedDependency() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability video = capability("intent-video", "video submitted");
        TaskPlan plan = new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.WAITING_HUMAN, 1,
                20, 3, 2, 0,
                List.of(
                        new TaskNode("media-image", "intent-pic", "生成图片", Map.of(), List.of(),
                                TaskNodeState.COMPLETED, 1, HitlPolicy.NONE,
                                EvaluationMode.DETERMINISTIC, "",
                                "![生成图片](/api/files/images/reference.png)", "", ""),
                        new TaskNode("media-video", "intent-video", "生成视频", Map.of(),
                                List.of("media-image"), TaskNodeState.WAITING_HUMAN, 0,
                                HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, "",
                                "{\"plansText\":\"视频方案\"}", "", "")),
                List.of(new TaskEdge("media-image", "media-video")), "", "");
        when(store.findActive("conversation-1")).thenReturn(Optional.of(plan));
        when(store.find("plan-1")).thenReturn(Optional.of(plan));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(registry.find("intent-video")).thenReturn(Optional.of(video));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);
        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        OrchestrationRequest request = new OrchestrationRequest(conversation, "1", List.of(), null);

        engine.runTurn(conversation, "1", "intent-video", request,
                new AgentTraceAccumulator(request), null);

        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> contextCaptor =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        verify(video).execute(contextCaptor.capture());
        assertThat(contextCaptor.getValue().picUrls())
                .containsExactly("/api/files/images/reference.png");
    }

    @Test
    void newImageIntentPreemptsWaitingVideoAndCarriesGeneratedImage() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability image = capability("intent-pic", "请确认完善方案");
        TaskPlan waiting = new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.WAITING_HUMAN, 1,
                20, 3, 2, 0,
                List.of(
                        new TaskNode("media-image", "intent-pic", "生成图片", Map.of(), List.of(),
                                TaskNodeState.COMPLETED, 1, HitlPolicy.NONE,
                                EvaluationMode.DETERMINISTIC, "",
                                "![生成图片](/api/files/images/generated.png)", "", ""),
                        new TaskNode("media-video", "intent-video", "生成视频", Map.of(),
                                List.of("media-image"), TaskNodeState.WAITING_HUMAN, 0,
                                HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, "",
                                "{\"plansText\":\"视频方案\"}", "", "")),
                List.of(new TaskEdge("media-image", "media-video")), "", "");
        TaskPlan replanned = new TaskPlan(
                "plan-2", "conversation-1", "refine image", TaskPlanState.READY, 1,
                20, 3, 2, 0,
                List.of(new TaskNode("refine-image", "intent-pic", "完善图片", Map.of(), List.of(),
                        TaskNodeState.PENDING, 0, HitlPolicy.NONE,
                        EvaluationMode.DETERMINISTIC, "", "", "", "")),
                List.of(), "", "");
        when(store.findActive("conversation-1")).thenReturn(Optional.of(waiting));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(planner.plan(any(), any())).thenReturn(replanned);
        CapabilityDescriptor imageDescriptor = new CapabilityDescriptor(
                "intent-pic", "picture", "picture", List.of(), false,
                HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3);
        when(registry.descriptors()).thenReturn(Map.of(
                "intent-pic", imageDescriptor));
        when(registry.find("intent-pic")).thenReturn(Optional.of(image));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);
        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "能把这图完善吗，文字有问题", List.of(), null);

        engine.runTurn(conversation, "能把这图完善吗，文字有问题", "intent-pic",
                request, new AgentTraceAccumulator(request), null);

        ArgumentCaptor<TaskPlanningRequest> planningRequest = ArgumentCaptor.forClass(TaskPlanningRequest.class);
        verify(planner).plan(planningRequest.capture(), any());
        assertThat(planningRequest.getValue().goal())
                .contains("不是用户上传")
                .contains("先完善这张图片");
        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> contextCaptor =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        verify(image).execute(contextCaptor.capture());
        assertThat(contextCaptor.getValue().picUrls())
                .containsExactly("/api/files/images/generated.png");
    }

    @Test
    void artifactFollowUpSearchesRecentPlansForCompletedImage() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability image = capability("intent-pic", "请确认");
        TaskPlan runningWithoutArtifact = new TaskPlan(
                "newer", "conversation-1", "goal", TaskPlanState.RUNNING, 1,
                20, 3, 2, 0,
                List.of(new TaskNode("pending", "intent-pic", "待执行", Map.of(), List.of(),
                        TaskNodeState.RUNNING, 0, HitlPolicy.NONE,
                        EvaluationMode.DETERMINISTIC, "", "", "", "")),
                List.of(), "", "");
        TaskPlan olderWithImage = new TaskPlan(
                "older", "conversation-1", "goal", TaskPlanState.COMPLETED, 1,
                20, 3, 2, 0,
                List.of(new TaskNode("old-image", "intent-pic", "旧图", Map.of(), List.of(),
                        TaskNodeState.COMPLETED, 1, HitlPolicy.NONE,
                        EvaluationMode.DETERMINISTIC, "",
                        "![生成图片](/api/files/images/old.png)", "", "")),
                List.of(), "", "");
        TaskPlan replanned = new TaskPlan(
                "replanned", "conversation-1", "edit", TaskPlanState.READY, 1,
                20, 3, 2, 0,
                List.of(new TaskNode("edit", "intent-pic", "完善图片", Map.of(), List.of(),
                        TaskNodeState.PENDING, 0, HitlPolicy.NONE,
                        EvaluationMode.DETERMINISTIC, "", "", "", "")),
                List.of(), "", "");
        when(store.findActive("conversation-1")).thenReturn(Optional.empty());
        when(store.findRecent("conversation-1", 6))
                .thenReturn(List.of(runningWithoutArtifact, olderWithImage));
        when(store.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(planner.plan(any(), any())).thenReturn(replanned);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                "intent-pic", "picture", "picture", List.of(), false,
                HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3);
        when(registry.descriptors()).thenReturn(Map.of("intent-pic", descriptor));
        when(registry.find("intent-pic")).thenReturn(Optional.of(image));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);
        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "图片还要调一下，年份是2025，我希望是2026", List.of(), null);

        engine.runTurn(conversation, request.getContent(), "intent-pic",
                request, new AgentTraceAccumulator(request), null);

        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> contextCaptor =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        verify(image).execute(contextCaptor.capture());
        assertThat(contextCaptor.getValue().picUrls())
                .containsExactly("/api/files/images/old.png");
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

    @Test
    void conversationalQuestionWaitsThenResumesFromNextUserMessage() {
        TaskPlanPlanner planner = mock(TaskPlanPlanner.class);
        TaskPlanStore store = mock(TaskPlanStore.class);
        AgentCapabilityRegistry registry = mock(AgentCapabilityRegistry.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist gateway = mock(AigcGatewaySpecialist.class);
        AgentCheckpointMapper checkpointMapper = mock(AgentCheckpointMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        OrchestrationRunRecorder recorder = mock(OrchestrationRunRecorder.class);
        AgentCapability capability = mock(AgentCapability.class);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                "intent-pic", "picture", "picture", List.of(), false,
                HitlPolicy.NONE, EvaluationMode.DETERMINISTIC, 3);
        when(capability.key()).thenReturn("intent-pic");
        when(capability.descriptor()).thenReturn(descriptor);
        when(capability.execute(any())).thenReturn(
                SpecialistResult.question("请选择方案", Map.of(
                        "toolName", "generate_image",
                        "plansText", "【方案1】方案一\n【方案2】方案二\n【方案3】方案三")),
                SpecialistResult.done("generated"));
        TaskPlan initial = new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.READY, 1,
                20, 3, 2, 0,
                List.of(new TaskNode("one", "intent-pic", "one", Map.of(), List.of(),
                        TaskNodeState.PENDING, 0, HitlPolicy.NONE,
                        EvaluationMode.DETERMINISTIC, "", "", "", "")),
                List.of(), "", "");
        java.util.concurrent.atomic.AtomicReference<TaskPlan> active =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(planner.plan(any(), any())).thenReturn(initial);
        when(store.findActive("conversation-1"))
                .thenAnswer(invocation -> Optional.ofNullable(active.get()));
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.find("plan-1"))
                .thenAnswer(invocation -> Optional.ofNullable(active.get()));
        when(store.update(any())).thenAnswer(invocation -> {
            TaskPlan plan = invocation.getArgument(0);
            active.set(plan);
            return plan;
        });
        when(registry.descriptors()).thenReturn(Map.of("intent-pic", descriptor));
        when(registry.find("intent-pic")).thenReturn(Optional.of(capability));
        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(assetMapper.selectOne(any())).thenReturn((AgentAsset) null);

        DynamicTaskEngine engine = new DynamicTaskEngine(
                planner, store, new TaskPlanScheduler(), registry, support, gateway,
                checkpointMapper, assetMapper, recorder);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        OrchestrationRequest firstRequest = new OrchestrationRequest(
                conversation, "生成图片并继续做视频", List.of(), null);

        AgentTurnResult waiting = engine.runTurn(
                conversation, "生成图片并继续做视频", "intent-pic",
                firstRequest, new AgentTraceAccumulator(firstRequest), null);

        assertThat(waiting.content()).contains("接下来执行第 1/1 步").contains("请选择方案");
        assertThat(active.get().state()).isEqualTo(TaskPlanState.WAITING_HUMAN);
        assertThat(active.get().nodes().get(0).outputSummary()).contains("\"plansText\"");

        OrchestrationRequest selection = new OrchestrationRequest(conversation, "1", List.of(), null);
        AgentTurnResult generated = engine.runTurn(
                conversation, "1", "intent-pic", selection,
                new AgentTraceAccumulator(selection), null);

        assertThat(generated.content()).contains("generated");
        ArgumentCaptor<com.moon.moonagent.ai.agent.specialist.SpecialistContext> contexts =
                ArgumentCaptor.forClass(com.moon.moonagent.ai.agent.specialist.SpecialistContext.class);
        verify(capability, times(2)).execute(contexts.capture());
        assertThat(contexts.getAllValues().get(1).action()).isEqualTo("select_image:1");
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
                "two", "intent-video", "two", Map.of(), List.of("one"),
                TaskNodeState.PENDING, 0, HitlPolicy.NONE, EvaluationMode.DETERMINISTIC,
                "", "", "", "");
        return new TaskPlan(
                "plan-1", "conversation-1", "goal", TaskPlanState.READY, 1,
                20, 3, 2, 0, List.of(first, second), List.of(new TaskEdge("one", "two")),
                "", "");
    }
}
