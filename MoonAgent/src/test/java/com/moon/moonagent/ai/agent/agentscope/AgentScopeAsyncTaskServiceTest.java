package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.ai.agent.task.AgentTaskView;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleKind;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardCommitMode;
import com.moon.moonagent.ai.storyboard.production.StoryboardProgressListener;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentScopeAsyncTaskServiceTest {

    @Test
    void storyboardStartMarksPartialWithoutWritingWhenSomeShotsFail()
            throws Exception {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService,
                new AgentTaskEventHub(), mock(AgentScopeMessageBus.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "storyboardClient", storyboardClient);

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(taskMapper.selectOne(any())).thenReturn(null);

        AtomicReference<AgentAsyncTask> persisted = new AtomicReference<>();
        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            task.setId("task-1");
            persisted.set(task);
            return 1;
        });
        when(taskMapper.selectById("task-1")).thenAnswer(invocation -> persisted.get());
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = persisted.get();
            @SuppressWarnings("unchecked")
            UpdateWrapper<AgentAsyncTask> wrapper = invocation.getArgument(1);
            if (wrapper.getParamNameValuePairs().containsValue("running")) {
                task.setStatus("running");
            } else if (wrapper.getParamNameValuePairs().containsValue("partial")) {
                task.setStatus("partial");
            }
            wrapper.getParamNameValuePairs().values().forEach(value -> {
                String text = String.valueOf(value);
                if (text.contains("shot-2")) task.setResultSummary(text);
            });
            return 1;
        });
        when(messageMapper.insert(any(AgentMessage.class))).thenAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            message.setId("message-1");
            return 1;
        });

        StoryboardRunSnapshot partial = new StoryboardRunSnapshot(
                "run-1", "conversation-1", Stage.FINALIZING, 9,
                "原始剧情", "修正剧情", "",
                new PlotAssessment(false, List.of(), List.of(), "已修正", ""),
                StoryBible.empty(), List.of(), List.of(), List.of(), List.of(),
                "以下镜头生成失败: shot-2");
        when(productionService.startAutoStoryboard(
                any(StoryboardProductionRequest.class), any(StoryboardProgressListener.class)))
                .thenReturn(partial);

        service.startStoryboardTask(
                conversation,
                new StoryboardProductionRequest(
                        "user-1", "project-1", "conversation-1", "",
                        "原始剧情", "video", "2", List.of(), List.of()),
                "call-partial",
                StoryboardCommitMode.APPEND);

        awaitStatus(persisted, "partial");
        assertThat(persisted.get().getResultSummary()).contains("shot-2");
        verifyNoInteractions(storyboardClient);
    }

    @Test
    void storyboardStartRunsAutoPipelineAndPublishesWrittenStoryboard()
            throws Exception {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService,
                new AgentTaskEventHub(), mock(AgentScopeMessageBus.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "storyboardClient", storyboardClient);

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(taskMapper.selectOne(any())).thenReturn(null);

        AtomicReference<AgentAsyncTask> persisted = new AtomicReference<>();
        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            task.setId("task-1");
            persisted.set(task);
            return 1;
        });
        when(taskMapper.selectById("task-1")).thenAnswer(invocation -> persisted.get());
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = persisted.get();
            @SuppressWarnings("unchecked")
            UpdateWrapper<AgentAsyncTask> wrapper = invocation.getArgument(1);
            if (wrapper.getParamNameValuePairs().containsValue("running")) {
                task.setStatus("running");
            } else if (wrapper.getParamNameValuePairs().containsValue("completed")) {
                task.setStatus("completed");
            }
            return 1;
        });
        when(messageMapper.insert(any(AgentMessage.class))).thenAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            message.setId("message-1");
            return 1;
        });

        StoryboardShot shot = new StoryboardShot(
                "shot-1", "segment-1", 1, "画面", "中景", "推近", 3,
                "图片", "视频", "", List.of());
        StoryboardRunSnapshot finalReview = new StoryboardRunSnapshot(
                "run-1", "conversation-1", Stage.FINAL_REVIEW, 4,
                "原始剧情", "修正剧情", "",
                new PlotAssessment(false, List.of(), List.of(), "已修正", ""),
                StoryBible.empty(), List.of(), List.of(), List.of(), List.of(shot), "");
        StoryboardRunSnapshot completed = new StoryboardRunSnapshot(
                "run-1", "conversation-1", Stage.COMPLETED, 5,
                "原始剧情", "修正剧情", "",
                finalReview.assessment(), StoryBible.empty(), List.of(), List.of(),
                List.of(), List.of(shot), "");
        when(productionService.startAutoStoryboard(
                any(StoryboardProductionRequest.class), any(StoryboardProgressListener.class)))
                .thenReturn(finalReview);
        when(productionService.getOwned("run-1", "user-1", "project-1"))
                .thenReturn(finalReview);
        when(storyboardClient.appendProjectScenes(eq("project-1"), any())).thenReturn(List.of("scene-1"));
        when(productionService.markCompleted(eq("run-1"), eq("user-1"), eq("project-1"), any()))
                .thenReturn(completed);

        String taskId = service.startStoryboardTask(
                conversation,
                new StoryboardProductionRequest(
                        "user-1", "project-1", "conversation-1", "",
                        "原始剧情", "video", "1", List.of(), List.of()),
                "call-1",
                StoryboardCommitMode.APPEND);

        assertThat(taskId).isEqualTo("task-1");
        awaitStatus(persisted, "completed");
        assertThat(persisted.get().getRequestJson()).contains("\"commitMode\":\"APPEND\"");
        ArgumentCaptor<AgentMessage> terminalMessage = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).insert(terminalMessage.capture());
        assertThat(terminalMessage.getValue().getContent())
                .contains("已生成 1 个镜头")
                .contains("追加");
        assertThat(terminalMessage.getValue().getMetadata())
                .containsEntry("kind", "storyboard-review");
        assertThat(service.getTask("user-1", "task-1").canFollowUp()).isFalse();
        verify(productionService).startAutoStoryboard(any(), any());
        verify(storyboardClient).appendProjectScenes(eq("project-1"), any());
        verify(productionService).markCompleted(
                eq("run-1"), eq("user-1"), eq("project-1"), eq(Map.of("shot-1", "scene-1")));
    }

    @Test
    void plotReviewTaskStopsBeforeStoryboardDesign() throws Exception {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, mock(AgentGenerationService.class),
                mock(AgentOrchestratorSupport.class), new AgentScopeProperties(), productionService,
                new AgentTaskEventHub(), mock(AgentScopeMessageBus.class));

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(taskMapper.selectOne(any())).thenReturn(null);

        AtomicReference<AgentAsyncTask> persisted = new AtomicReference<>();
        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            task.setId("task-plot");
            persisted.set(task);
            return 1;
        });
        when(taskMapper.selectById("task-plot")).thenAnswer(invocation -> persisted.get());
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = persisted.get();
            @SuppressWarnings("unchecked")
            UpdateWrapper<AgentAsyncTask> wrapper = invocation.getArgument(1);
            if (wrapper.getParamNameValuePairs().containsValue("running")) task.setStatus("running");
            if (wrapper.getParamNameValuePairs().containsValue("completed")) {
                task.setStatus("completed");
                task.setStage("waiting_review");
            }
            return 1;
        });
        ArgumentCaptor<AgentMessage> message = ArgumentCaptor.forClass(AgentMessage.class);
        when(messageMapper.insert(message.capture())).thenReturn(1);

        StoryboardRunSnapshot plotReview = new StoryboardRunSnapshot(
                "run-plot", "conversation-1", Stage.PLOT_REVIEW, 1,
                "原始需求", "优化后的完整剧情", "",
                new PlotAssessment(false, List.of(), List.of(), "已优化", ""),
                StoryBible.empty(), List.of(), List.of(), List.of(), List.of(), "");
        when(productionService.start(any())).thenReturn(plotReview);

        service.startPlotReviewStoryboardTask(
                conversation,
                new StoryboardProductionRequest(
                        "user-1", "project-1", "conversation-1", "",
                        "原始需求", "design", "12", List.of(), List.of()),
                "call-plot",
                StoryboardCommitMode.APPEND);

        awaitStatus(persisted, "completed");
        verify(productionService).start(any());
        verify(productionService, never()).startAutoStoryboard(any(), any());
        assertThat(message.getValue().getContent()).contains("PLOT_REVIEW");
        assertThat(message.getValue().getMetadata())
                .containsEntry("kind", "storyboard-review");
        assertThat(((Map<?, ?>) message.getValue().getMetadata().get("storyboardReview"))
                .get("stage")).isEqualTo("PLOT_REVIEW");
    }

    @Test
    void storyboardStartReturnsImmediatelyAndReportsBackgroundProgress()
            throws Exception {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        AgentScopeMessageBus messageBus = mock(AgentScopeMessageBus.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, properties, productionService, new AgentTaskEventHub(), messageBus);

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(taskMapper.selectOne(any())).thenReturn(null);

        AtomicReference<AgentAsyncTask> persisted = new AtomicReference<>();
        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            task.setId("task-1");
            persisted.set(task);
            return 1;
        });
        when(taskMapper.selectById("task-1")).thenAnswer(invocation -> persisted.get());
        AtomicInteger updates = new AtomicInteger();
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = persisted.get();
            @SuppressWarnings("unchecked")
            UpdateWrapper<AgentAsyncTask> wrapper = invocation.getArgument(1);
            if (wrapper.getParamNameValuePairs().containsValue("running")) {
                task.setStatus("running");
            } else if (wrapper.getParamNameValuePairs().containsValue("completed")) {
                task.setStatus("completed");
            } else if (wrapper.getParamNameValuePairs().containsValue("failed")) {
                task.setStatus("failed");
            }
            updates.incrementAndGet();
            return 1;
        });
        when(messageMapper.insert(any(AgentMessage.class))).thenAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            message.setId("message-1");
            return 1;
        });

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(productionService.startAutoStoryboard(
                any(StoryboardProductionRequest.class), any(StoryboardProgressListener.class)))
                .thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            StoryboardProgressListener listener = invocation.getArgument(1);
            listener.update("plot_diagnosis", 10);
            return new StoryboardRunSnapshot(
                    "run-1", Stage.FINALIZING, 1,
                    "原始剧情", "修正剧情", "",
                    new PlotAssessment(false, List.of("缺少结局"), List.of("转折过快"),
                            "需要补足收束", "补写主角的选择与代价"),
                    StoryBible.empty(),
                    List.of(new RoleProfile("director", "导演", RoleKind.CREATIVE, "控制节奏")),
                    List.of(), List.of(), List.of(), "正式镜头尚未全部完成");
        });

        StoryboardProductionRequest request = new StoryboardProductionRequest(
                "user-1", "project-1", "conversation-1", "",
                "原始剧情", "video", "12", List.of(), List.of("导演"));

        long startedAt = System.nanoTime();
        String taskId = service.startStoryboardTask(conversation, request, "call-1");
        long startupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(taskId).isEqualTo("task-1");
        assertThat(startupMs).isLessThan(1000);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        awaitStatus(persisted, "completed");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AgentAsyncTask>> updateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper, org.mockito.Mockito.atLeast(2)).update(isNull(), updateCaptor.capture());
        String updatesSql = updateCaptor.getAllValues().stream()
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().values().stream())
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
        assertThat(updatesSql)
                .contains("run_id")
                .contains("plot_diagnosis")
                .contains("正式镜头尚未全部完成");
        ArgumentCaptor<AgentMessage> terminalMessage = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).insert(terminalMessage.capture());
        assertThat(terminalMessage.getValue().getContent())
                .contains("run_id");
        assertThat(terminalMessage.getValue().getMetadata())
                .containsEntry("kind", "storyboard-review");
        verify(productionService).startAutoStoryboard(
                any(StoryboardProductionRequest.class), any(StoryboardProgressListener.class));
    }

    @Test
    void completedTaskViewIsReturnedAndInjectedIntoTheNextAgentTurnOnce() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);

        AgentAsyncTask task = new AgentAsyncTask();
        task.setId("task-1");
        task.setConversationId("conversation-1");
        task.setUserId("user-1");
        task.setTitle("剧情与分镜诊断");
        task.setToolName(AgentScopeStoryboardTools.START_STORYBOARD);
        task.setKind("long-tool");
        task.setStatus("completed");
        task.setStage("waiting_review");
        task.setProgress(100);
        task.setResultSummary("剧情诊断完成，等待确认");
        task.setVersion(3L);
        when(taskMapper.selectById("task-1")).thenReturn(task);

        AgentTaskView view = service.getTask("user-1", "task-1");
        assertThat(view).isNotNull();
        assertThat(view.title()).isEqualTo("剧情与分镜诊断");
        assertThat(view.status()).isEqualTo("completed");
        assertThat(view.stage()).isEqualTo("waiting_review");
        assertThat(view.canFollowUp()).isTrue();

        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        assertThat(service.consumeUnreadContext("conversation-1"))
                .contains("剧情与分镜诊断")
                .contains("剧情诊断完成，等待确认");
        verify(taskMapper, org.mockito.Mockito.atLeastOnce())
                .update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void consumeUnreadContextWritesTimestampAsOffsetDateTime() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        AgentAsyncTask task = new AgentAsyncTask();
        task.setId("task-1");
        task.setConversationId("conversation-1");
        task.setTitle("剧情与分镜");
        task.setStatus("completed");
        task.setResultSummary("分镜已写入项目");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        service.consumeUnreadContext("conversation-1");

        ArgumentCaptor<UpdateWrapper<AgentAsyncTask>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(isNull(), captor.capture());
        long timestampValues = captor.getValue().getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof OffsetDateTime)
                .count();
        assertThat(timestampValues).isGreaterThanOrEqualTo(2);
    }

    @Test
    void cancelQueuedTaskWritesCompletedAtAsOffsetDateTime() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        AgentAsyncTask task = new AgentAsyncTask();
        task.setId("task-1");
        task.setConversationId("conversation-1");
        task.setUserId("user-1");
        task.setStatus("queued");
        when(taskMapper.selectById("task-1")).thenReturn(task);
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        service.cancelTask("user-1", "task-1");

        ArgumentCaptor<UpdateWrapper<AgentAsyncTask>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(isNull(), captor.capture());
        long timestampValues = captor.getValue().getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof OffsetDateTime)
                .count();
        assertThat(timestampValues).isGreaterThanOrEqualTo(2);
    }

    @Test
    void taskListCountQueryDoesNotCarryOrderByClause() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));
        java.util.concurrent.atomic.AtomicBoolean countQueryHasNoOrder =
                new java.util.concurrent.atomic.AtomicBoolean();
        when(taskMapper.selectCount(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaQueryWrapper<AgentAsyncTask> countQuery =
                    (LambdaQueryWrapper<AgentAsyncTask>) invocation.getArgument(0);
            countQueryHasNoOrder.set(countQuery.getExpression().getOrderBy().isEmpty());
            return 0L;
        });
        when(taskMapper.selectList(any())).thenReturn(List.of());

        service.listTasks("user-1", "conversation-1", null, null, 1, 20);

        assertThat(countQueryHasNoOrder).isTrue();
    }

    @Test
    void taskViewExposesBatchSlotsAndPartialRetry() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);

        AgentAsyncTask task = new AgentAsyncTask();
        task.setId("task-1");
        task.setConversationId("conversation-1");
        task.setUserId("user-1");
        task.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        task.setKind("image-batch");
        task.setStatus("partial");
        task.setTitle("批量生成 2 张图片");
        task.setResultJson("""
                {"slots":[
                  {"index":0,"prompt":"第一张","status":"completed","url":"/one.png","assetId":"asset-1","error":"","completedAt":"2026-09-24T10:00:00+08:00"},
                  {"index":1,"prompt":"第二张","status":"failed","url":"","assetId":"","error":"超时","completedAt":"2026-09-24T10:00:01+08:00"}
                ]}
                """);
        when(taskMapper.selectById("task-1")).thenReturn(task);

        AgentTaskView view = service.getTask("user-1", "task-1");

        assertThat(view.status()).isEqualTo("partial");
        assertThat(view.slots()).hasSize(2);
        assertThat(view.slots().get(0).url()).isEqualTo("/one.png");
        assertThat(view.slots().get(1).error()).isEqualTo("超时");
        assertThat(view.canRetry()).isTrue();
    }

    @Test
    void partialRetryCreatesChildForOnlyFailedOrMissingSlots() throws Exception {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);

        AgentAsyncTask parent = new AgentAsyncTask();
        parent.setId("parent-1");
        parent.setConversationId("conversation-1");
        parent.setUserId("user-1");
        parent.setSessionId("conversation-1");
        parent.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        parent.setKind("image-batch");
        parent.setStatus("partial");
        parent.setTitle("批量生成 3 张图片");
        parent.setRequestJson("""
                {"prompt":"父提示","prompts":["第一张","第二张","第三张"],
                 "sourceUrls":["/source.png"],"size":"1K","quality":"high","count":3}
                """);
        parent.setResultJson("""
                {"slots":[
                  {"index":0,"prompt":"第一张","status":"completed","url":"/one.png","assetId":"asset-1","error":""},
                  {"index":1,"prompt":"第二张","status":"failed","url":"","assetId":"","error":"超时"},
                  {"index":2,"prompt":"第三张","status":"pending","url":"","assetId":"","error":""}
                ]}
                """);
        when(taskMapper.selectById("parent-1")).thenReturn(parent);

        AtomicReference<AgentAsyncTask> inserted = new AtomicReference<>();
        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask child = invocation.getArgument(0);
            child.setId("child-1");
            inserted.set(child);
            return 1;
        });

        AgentTaskView child = service.retryTask("user-1", "parent-1");

        assertThat(child.taskId()).isEqualTo("child-1");
        assertThat(inserted.get().getParentTaskId()).isEqualTo("parent-1");
        Map<String, Object> request = new ObjectMapper().readValue(
                inserted.get().getRequestJson(), new TypeReference<>() {
                });
        assertThat(request.get("prompts")).isEqualTo(List.of("第二张", "第三张"));
        assertThat(request.get("slotIndices")).isEqualTo(List.of(1, 2));
        assertThat(request.get("count")).isEqualTo(2);
        assertThat(request.get("retryOfTaskId")).isEqualTo("parent-1");
    }

    @Test
    void successfulRetryMergesBackIntoParentAsCompleted() {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);

        AgentAsyncTask parent = new AgentAsyncTask();
        parent.setId("parent-1");
        parent.setConversationId("conversation-1");
        parent.setUserId("user-1");
        parent.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        parent.setKind("image-batch");
        parent.setStatus("partial");
        parent.setTitle("批量生成 2 张图片");
        parent.setRequestJson("""
                {"prompt":"父提示","prompts":["第一张","第二张"],"count":2}
                """);
        parent.setResultJson("""
                {"slots":[
                  {"index":0,"prompt":"第一张","status":"completed","url":"/one.png","assetId":"asset-1","error":""},
                  {"index":1,"prompt":"第二张","status":"failed","url":"","assetId":"","error":"超时"}
                ]}
                """);
        when(taskMapper.selectById("parent-1")).thenReturn(parent);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        AtomicReferenceArray<UpdateWrapper<AgentAsyncTask>> updates =
                new AtomicReferenceArray<>(4);
        AtomicInteger updateCount = new AtomicInteger();
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            UpdateWrapper<AgentAsyncTask> wrapper = invocation.getArgument(1);
            updates.set(updateCount.getAndIncrement() % updates.length(), wrapper);
            return 1;
        });

        service.mergeRetryIntoParent(
                "child-1",
                Map.of("retryOfTaskId", "parent-1"),
                List.of(AgentScopeBatchImageSlots.Slot.pending(1, "第二张")
                        .complete("/two-retry.png", "asset-2r", OffsetDateTime.now())));

        String values = java.util.stream.IntStream.range(0, updates.length())
                .mapToObj(index -> updates.get(index))
                .filter(java.util.Objects::nonNull)
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().values().stream())
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
        assertThat(values).contains("completed").contains("/two-retry.png").contains("/one.png");
    }

    @Test
    void batchImageExecutionPublishesPartialAfterOneSlotFails() throws Exception {
        AgentAsyncTaskMapper taskMapper = mock(AgentAsyncTaskMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentGenerationService generationService = mock(AgentGenerationService.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        StoryboardProductionService productionService = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService service = new AgentScopeAsyncTaskService(
                taskMapper, conversationMapper, messageMapper, generationService,
                support, new AgentScopeProperties(), productionService, new AgentTaskEventHub(),
                mock(AgentScopeMessageBus.class));

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        AtomicReference<AgentAsyncTask> stored = new AtomicReference<>();
        when(taskMapper.insert(any(AgentAsyncTask.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = invocation.getArgument(0);
            task.setId("batch-1");
            stored.set(task);
            return 1;
        });
        when(taskMapper.selectById("batch-1")).thenAnswer(invocation -> stored.get());

        CountDownLatch partialReached = new CountDownLatch(1);
        when(taskMapper.update(isNull(), any(UpdateWrapper.class))).thenAnswer(invocation -> {
            AgentAsyncTask task = stored.get();
            @SuppressWarnings("unchecked")
            UpdateWrapper<AgentAsyncTask> wrapper = invocation.getArgument(1);
            wrapper.getParamNameValuePairs().values().forEach(value -> {
                String text = String.valueOf(value);
                if ("running".equals(value)) task.setStatus("running");
                if ("partial".equals(value)) {
                    task.setStatus("partial");
                    partialReached.countDown();
                }
                if (text.contains("\"slots\"")) {
                    task.setResultJson(text);
                }
                if (text.contains("1/2") && text.contains("张")) {
                    task.setResultSummary(text);
                }
                if (text.startsWith("[") && text.contains(".png")) {
                    task.setResultUrlsJson(text);
                }
                if (value instanceof Integer progress) task.setProgress(progress);
            });
            return 1;
        });

        when(generationService.generateImage(
                any(), isNull(), eq("第一张"), isNull(), any(), any(), eq(1),
                any(), any(), isNull()))
                .thenReturn(Map.of(
                        "imageUrls", List.of("/one.png"),
                        "assetIds", List.of("asset-1")));
        when(generationService.generateImage(
                any(), isNull(), eq("第二张"), isNull(), any(), any(), eq(1),
                any(), any(), isNull()))
                .thenThrow(new RuntimeException("审核失败"));

        String taskId = service.startBatchImageTask(
                conversation, "父提示", List.of("第一张", "第二张"),
                List.of(), "1K", "high", 2,
                false, "", "", "", "", "call-1");

        assertThat(taskId).isEqualTo("batch-1");
        assertThat(partialReached.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(stored.get().getStatus()).isEqualTo("partial");
        assertThat(stored.get().getResultSummary()).contains("1/2");
        assertThat(stored.get().getResultUrlsJson()).contains("/one.png").doesNotContain("/two.png");
        assertThat(stored.get().getResultJson())
                .contains("\"status\":\"failed\"")
                .contains("审核失败");
    }

    private static void awaitStatus(AtomicReference<AgentAsyncTask> taskRef, String status)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            AgentAsyncTask task = taskRef.get();
            if (task != null && status.equals(task.getStatus())) return;
            Thread.sleep(20);
        }
    }
}
