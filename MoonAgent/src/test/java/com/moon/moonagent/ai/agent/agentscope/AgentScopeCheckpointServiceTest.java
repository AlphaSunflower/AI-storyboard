package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentApprovalMode;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
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
        when(support.runHITLStage(any(), any(), any(), any(Consumer.class)))
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
        verify(support).runHITLStage(any(), any(), stage.capture(), customizer.capture());
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
        when(support.runHITLStage(any(), any(), any(), any(Consumer.class)))
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
        verify(support).runHITLStage(any(), any(), stage.capture(), any(Consumer.class));
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("title"))
                .containsExactly("追加到末尾", "覆盖全部分镜");
    }

    @Test
    void storyboardCommitCardUsesFixedAppendOverwriteDeleteChoices() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any(), any(Consumer.class)))
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
        verify(support).runHITLStage(any(), any(), stage.capture(), customizer.capture());
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
        when(support.runHITLStage(any(), any(), any(), any(Consumer.class)))
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
        verify(support).runHITLStage(any(), any(), stage.capture(), any(Consumer.class));
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
        when(support.runHITLStage(any(), any(), any(), any(Consumer.class)))
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
        verify(support).runHITLStage(any(), any(), stage.capture(), customizer.capture());
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
        when(support.runHITLStage(any(), any(), any(), any(Consumer.class)))
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
        verify(support).runHITLStage(any(), any(), stage.capture(), any(Consumer.class));
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
        when(support.runHITLStage(any(), any(), any(), any(Consumer.class)))
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
        verify(support).runHITLStage(any(), any(), stage.capture(), any(Consumer.class));
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

}
