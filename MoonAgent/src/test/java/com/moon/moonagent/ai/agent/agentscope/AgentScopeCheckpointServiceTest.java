package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentApprovalMode;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.human.ApprovalOutcome;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeException;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import io.agentscope.core.message.ToolUseBlock;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeCheckpointServiceTest {

    @Test
    void externalInputPauseCreatesOwnedCheckpointAndResolvesCustomText() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class)))
                .thenReturn("token-1");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setId("cp-1");
        persisted.setFormToken("token-1");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-1")
                .name(AgentScopeInteractionTools.REQUEST_HUMAN_INPUT)
                .input(Map.of(
                        "prompt", "想要哪种风格？",
                        "allow_custom", true,
                        "options", List.of(Map.of("title", "写实", "value", "realistic"))))
                .build();

        String token = service.pauseForExternalInput(request, "reply-1", call);

        assertThat(token).isEqualTo("token-1");
        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<AgentCheckpoint>> customizer =
                ArgumentCaptor.forClass(Consumer.class);
        verify(support).runHITLStage(any(), stage.capture(), customizer.capture());
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("id"))
                .containsExactly("option-0", "custom");
        AgentCheckpoint created = new AgentCheckpoint();
        customizer.getValue().accept(created);
        assertThat(created.getOwnerEngine()).isEqualTo("AGENTSCOPE");
        assertThat(created.getStateSchemaVersion()).isEqualTo(3);
        assertThat(created.getRuntimeReplyId()).isEqualTo("reply-1");
        assertThat(created.getRuntimeToolCallId()).isEqualTo("call-1");

        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setRuntimeToolCallId("call-1");
        checkpoint.setRuntimeToolName(AgentScopeInteractionTools.REQUEST_HUMAN_INPUT);
        checkpoint.setPlan("""
                {"items":[{"toolCallId":"call-1","toolName":"request_human_input",
                "options":[{"title":"写实","value":"realistic"}]}]}
                """);
        AgentScopeCheckpointService.ResumeValue value =
                service.resolve(checkpoint, "custom", "电影感写实");
        assertThat(value.toolCallId()).isEqualTo("call-1");
        assertThat(value.text()).isEqualTo("电影感写实");
    }

    @Test
    void storyboardCommitOptionsUseReadableFallbackTitles() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class)))
                .thenReturn("token-append-overwrite");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setFormToken("token-append-overwrite");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-storyboard")
                .name(AgentScopeInteractionTools.REQUEST_HUMAN_INPUT)
                .input(Map.of(
                        "prompt", "新的分镜要怎么写入？",
                        "options", List.of(
                                Map.of("label", "追加到末尾"),
                                Map.of("text", "覆盖全部分镜"))))
                .build();

        service.pauseForExternalInput(request, "reply-storyboard", call);

        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        verify(support).runHITLStage(any(), stage.capture(), any(Consumer.class));
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("title"))
                .containsExactly("追加到末尾", "覆盖全部分镜");
    }

    @Test
    void storyboardCommitCardUsesFixedAppendOverwriteDeleteChoices() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class)))
                .thenReturn("token-storyboard-commit");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setFormToken("token-storyboard-commit");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-storyboard-commit")
                .name(AgentScopeInteractionTools.CHOOSE_STORYBOARD_COMMIT)
                .input(Map.of("existing_scene_count", 16))
                .build();

        service.pauseForStoryboardCommit(request, "reply-storyboard", call, 16);

        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<AgentCheckpoint>> customizer =
                ArgumentCaptor.forClass(Consumer.class);
        verify(support).runHITLStage(any(), stage.capture(), customizer.capture());
        assertThat(stage.getValue().planText()).contains("已有 16 个分镜");
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("id"))
                .containsExactly("append", "overwrite", "delete");
        AgentCheckpoint created = new AgentCheckpoint();
        customizer.getValue().accept(created);
        assertThat(created.getRuntimeToolName())
                .isEqualTo(AgentScopeInteractionTools.CHOOSE_STORYBOARD_COMMIT);

        created.setRuntimeToolCallId("call-storyboard-commit");
        created.setRuntimeToolName(AgentScopeInteractionTools.CHOOSE_STORYBOARD_COMMIT);
        created.setPlan("""
                {"items":[{"kind":"storyboard-commit"}]}
                """);
        AgentScopeCheckpointService.ResumeValue value =
                service.resolve(created, "delete", "");
        assertThat(value.text()).isEqualTo("delete");
    }

    @Test
    void genericHumanInputForStoryboardCommitIsUpgradedToFixedChoices() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class)))
                .thenReturn("token-storyboard-upgrade");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setFormToken("token-storyboard-upgrade");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-generic-storyboard")
                .name(AgentScopeInteractionTools.REQUEST_HUMAN_INPUT)
                .input(Map.of(
                        "prompt", "确认清理方案：项目中有 31 条分镜（含两轮生成叠加），您希望如何处理？",
                        "options", List.of(Map.of(), Map.of(), Map.of())))
                .build();

        service.pauseForExternalInput(request, "reply-generic-storyboard", call);

        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        verify(support).runHITLStage(any(), stage.capture(), any(Consumer.class));
        assertThat(stage.getValue().planText()).contains("已有 31 个分镜");
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("title"))
                .containsExactly("追加到末尾", "覆盖全部分镜", "删除全部分镜");
    }

    @Test
    void externalInputDoesNotGuessRuntimeReferenceFromPlanJson() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setPlan("""
                {"items":[{"toolCallId":"call-from-plan","toolName":"request_human_input"}]}
                """);

        assertThatThrownBy(() -> service.resolve(checkpoint, "custom", "继续"))
                .isInstanceOf(CheckpointResumeException.class)
                .hasMessageContaining("运行态引用缺失");
    }

    @Test
    void confirmationUsesChineseToolLabelsAndMarksAgentScopeOwner() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class)))
                .thenReturn("card text");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setId("cp-2");
        persisted.setFormToken("token-2");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-2")
                .name(AgentScopeImageTools.GENERATE_IMAGE_BATCH)
                .input(Map.of("prompt", "两张篮球图"))
                .build();

        String token = service.pauseForConfirmation(request, "reply-2", List.of(call));

        assertThat(token).isEqualTo("token-2");
        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<AgentCheckpoint>> customizer =
                ArgumentCaptor.forClass(Consumer.class);
        verify(support).runHITLStage(any(), stage.capture(), customizer.capture());
        assertThat(stage.getValue().planText())
                .contains("生成方案待确认")
                .contains("批量生成图片")
                .doesNotContain(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("title"))
                .containsExactly("按方案生成", "取消");
        AgentCheckpoint created = new AgentCheckpoint();
        customizer.getValue().accept(created);
        assertThat(created.getOwnerEngine()).isEqualTo("AGENTSCOPE");
        assertThat(created.getStateSchemaVersion()).isEqualTo(3);
        assertThat(created.getRuntimeToolName()).isEqualTo(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        assertThat(created.getRuntimeToolCallsJson())
                .contains("\"id\":\"call-2\"")
                .contains("\"name\":\"generate_image_batch\"");
        assertThat(service.permissionToolCalls(created))
                .extracting(ToolUseBlock::getId)
                .containsExactly("call-2");
    }

    @Test
    void planApprovalShowsGenerationDetailsAndTruncatesLongPrompts() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class)))
                .thenReturn("plan-token");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setFormToken("plan-token");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setApprovalMode(AgentApprovalMode.PLAN_APPROVAL.name());
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        String longPrompt = "雨夜街头".repeat(120);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-plan")
                .name(AgentScopeImageTools.GENERATE_IMAGE_BATCH)
                .input(Map.of(
                        "prompt", "篮球海报",
                        "prompts", List.of("运球特写", longPrompt, "终场庆祝"),
                        "source_asset_ids", List.of("a1", "a2"),
                        "size", "2K",
                        "quality", "high"))
                .build();

        service.pauseForConfirmation(request, "reply-plan", List.of(call));

        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        verify(support).runHITLStage(any(), stage.capture(), any(Consumer.class));
        assertThat(stage.getValue().planText())
                .contains("生成方案待确认")
                .contains("数量：3 张")
                .contains("尺寸：2K")
                .contains("质量：high")
                .contains("参考素材：2 项")
                .contains("1. 运球特写")
                .doesNotContain(longPrompt);
    }

    @Test
    void requestApprovalUsesPerActionWording() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class)))
                .thenReturn("request-token");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setFormToken("request-token");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setApprovalMode(AgentApprovalMode.REQUEST_APPROVAL.name());
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-request")
                .name(AgentScopeVideoTools.GENERATE_VIDEO)
                .input(Map.of(
                        "prompt", "镜头缓慢推进",
                        "duration", "8",
                        "aspect_ratio", "16:9",
                        "resolution", "1080p"))
                .build();

        service.pauseForConfirmation(request, "reply-request", List.of(call));

        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        verify(support).runHITLStage(any(), stage.capture(), any(Consumer.class));
        assertThat(stage.getValue().planText())
                .contains("请求批准")
                .contains("生成视频")
                .contains("时长：8 秒")
                .contains("画幅：16:9")
                .contains("分辨率：1080p")
                .contains("提示词：镜头缓慢推进");
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("title"))
                .containsExactly("允许执行", "拒绝");
    }

    // ------------------------------------------------------------------
    // 复合旅程（produce_video_journey）的逐条审批方案
    // ------------------------------------------------------------------

    @Test
    void journeyPlanItemizesShortBranchStepsInApprovalCard() {
        CapturedConfirmation captured = confirmJourney(Map.of(
                "prompt", "雨夜街头短片",
                "target_duration_seconds", "12",
                "aspect_ratio", "16:9",
                "resolution", "1080p",
                "model", "MiniMax-H3"), new AgentScopeProperties());

        assertThat(captured.planText())
                .contains("一键成片")
                .contains("分支：生图 → 视频")
                .contains("阈值：15 秒")
                .contains("将执行以下 2 个步骤")
                .contains("① 生成 1 张图片")
                .contains("② 生成视频")
                .contains("画幅：16:9")
                .contains("分辨率：1080p")
                .contains("模型：MiniMax-H3")
                .contains("批准后仅执行以上列出的步骤");
        // 一次批准 = 批准的正是列出的这些操作。
        assertThat(captured.actions())
                .extracting(action -> action.get("title"))
                .containsExactly("按方案生成", "取消");
    }

    @Test
    void journeyPlanItemizesLongBranchStepsForDurationOverThreshold() {
        CapturedConfirmation captured = confirmJourney(Map.of(
                "prompt", "40 秒完整宣传片",
                "target_duration_seconds", "40",
                "shot_prompts", List.of("开场", "中段", "收尾")), new AgentScopeProperties());

        assertThat(captured.planText())
                .contains("分支：分镜 → 逐镜图片 → 逐镜视频 → 合成")
                .contains("阈值：15 秒")
                .contains("将执行以下 4 个步骤")
                .contains("① 生成分镜 3 个镜头")
                .contains("② 逐镜配图 3 个镜头")
                .contains("③ 逐镜视频 3 个镜头")
                .contains("④ 合成成片 3 个片段");
    }

    @Test
    void journeyPlanItemizesLongBranchWhenUserExplicitlyAsksForStoryboard() {
        CapturedConfirmation captured = confirmJourney(Map.of(
                "prompt", "走分镜流程",
                "target_duration_seconds", "8",
                "want_storyboard", true), new AgentScopeProperties());

        assertThat(captured.planText())
                .contains("分支：分镜 → 逐镜图片 → 逐镜视频 → 合成")
                .contains("① 生成分镜")
                .contains("④ 合成成片");
    }

    @Test
    void journeyPlanFollowsConfiguredThresholdInsteadOfHardCodedFifteen() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setJourneyDirectVideoMaxSeconds(60);

        CapturedConfirmation captured = confirmJourney(Map.of(
                "prompt", "20 秒但阈值 60",
                "target_duration_seconds", "20"), properties);

        assertThat(captured.planText())
                .contains("阈值：60 秒")
                .contains("分支：生图 → 视频")
                .contains("① 生成 1 张图片")
                .doesNotContain("合成成片");
    }

    @Test
    void journeyApprovalKeepsRejectTerminalAndPassesToolCallsThrough() {
        CapturedConfirmation captured = confirmJourney(Map.of(
                "prompt", "需要确认的旅程",
                "target_duration_seconds", "8"), new AgentScopeProperties());

        assertThat(captured.actions())
                .extracting(action -> action.get("id"))
                .containsExactly("approve", "reject");
        // 权限恢复仍按 checkpoint 里记录的原始 toolCalls 执行，没有新增 HITL 协议。
        assertThat(captured.service().permissionToolCalls(captured.created()))
                .extracting(ToolUseBlock::getName)
                .containsExactly(AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY);
    }

    private record CapturedConfirmation(String planText,
                                        List<Map<String, Object>> actions,
                                        AgentCheckpoint created,
                                        AgentScopeCheckpointService service) {
    }

    @Test
    void permissionResumeUsesClosedApprovalVocabularyAndFailsClosed() {
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(
                mock(AgentRuntimeSupport.class), mock(AgentCheckpointMapper.class));
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setPlan("{\"items\":[{\"kind\":\"permission\"}]}");

        assertThat(service.resolve(checkpoint, "approve", "").outcome())
                .isEqualTo(ApprovalOutcome.ALLOWED);
        assertThat(service.resolve(checkpoint, "reject", "").outcome())
                .isEqualTo(ApprovalOutcome.REJECTED);
        assertThat(service.resolve(checkpoint, "cancel", "").outcome())
                .isEqualTo(ApprovalOutcome.CANCELLED);
        assertThat(service.resolve(checkpoint, "not-a-real-action", "").outcome())
                .isEqualTo(ApprovalOutcome.UNAVAILABLE);
        assertThat(service.resolve(checkpoint, null, "").approved()).isFalse();
        assertThat(service.resolve(checkpoint, "not-a-real-action", "").text())
                .isEqualTo(ApprovalOutcome.UNAVAILABLE.wireValue());
    }

    /** 用指定的属性（阈值）与工具输入触发一次确认卡片，捕获其中的方案与动作。 */
    @Test
    void writeGraphConfirmationListsWriteNodesAndTools() {
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(
                mock(AgentRuntimeSupport.class),
                mock(com.moon.moonagent.mapper.AgentCheckpointMapper.class));
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-write")
                .name(AgentScopeOrchestrationTools.RUN_WRITE_TASK_GRAPH)
                .input(Map.of("task", "写文件", "nodes", List.of(Map.of(
                        "id", "writer",
                        "role", "writer",
                        "allowWrite", true,
                        "tools", List.of("write_file", "run_python_script")))))
                .build();

        String prompt = service.confirmationPrompt(
                AgentApprovalMode.PLAN_APPROVAL, List.of(call));

        assertThat(prompt)
                .contains("writer")
                .contains("write_file")
                .contains("run_python_script");
    }

    @Test
    void writeGraphPayloadExposesApprovalOptions() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(
                support, mock(com.moon.moonagent.mapper.AgentCheckpointMapper.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setApprovalMode(AgentApprovalMode.PLAN_APPROVAL.name());
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-write")
                .name(AgentScopeOrchestrationTools.RUN_WRITE_TASK_GRAPH)
                .input(Map.of("task", "写文件", "nodes", List.of(Map.of(
                        "id", "writer",
                        "role", "writer",
                        "allowWrite", true,
                        "tools", List.of("write_file")))))
                .build();

        service.pauseForConfirmation(request, "reply-write", List.of(call));

        org.mockito.ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                org.mockito.ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        verify(support).runHITLStage(any(), stage.capture(), any(Consumer.class));
        assertThat(stage.getValue().planPayload().getFirst())
                .extractingByKey("writeToolOptions")
                .asList()
                .isNotEmpty();
    }

    private static CapturedConfirmation confirmJourney(Map<String, Object> input,
                                                       AgentScopeProperties properties) {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(Consumer.class))).thenReturn("journey-token");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setFormToken("journey-token");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service =
                new AgentScopeCheckpointService(support, mapper, properties);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setApprovalMode(AgentApprovalMode.PLAN_APPROVAL.name());
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "帮我做一条完整短片", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-journey")
                .name(AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY)
                .input(input)
                .build();

        service.pauseForConfirmation(request, "reply-journey", List.of(call));

        ArgumentCaptor<AgentRuntimeSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentRuntimeSupport.StagePlan.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<AgentCheckpoint>> customizer =
                ArgumentCaptor.forClass(Consumer.class);
        verify(support).runHITLStage(any(), stage.capture(), customizer.capture());
        AgentCheckpoint created = new AgentCheckpoint();
        customizer.getValue().accept(created);
        return new CapturedConfirmation(
                stage.getValue().planText(), stage.getValue().actions(), created, service);
    }

}
