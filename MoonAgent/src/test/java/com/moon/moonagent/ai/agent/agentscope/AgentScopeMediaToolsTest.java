package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.CreativeCritiqueService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeMediaToolsTest {

    @Test
    void imageToolCallsGenerationImmediatelyWithoutPlanningState() {
        AgentGenerationService generation = mock(AgentGenerationService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        AgentScopeAssetGroupService assetGroups = mock(AgentScopeAssetGroupService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setCritiqueEnabled(false);
        when(assets.urlsByIds("c1", List.of("a1"))).thenReturn(List.of("/a1.png"));
        when(generation.generateImage(any(), isNull(), eq("海边日落"), isNull(),
                eq("1K"), eq("high"), eq(1), eq("edit"), eq(List.of("/a1.png")), isNull()))
                .thenReturn(Map.of("imageUrls", List.of("/result.png"),
                        "assetIds", List.of("result-asset")));
        AgentScopeImageTools tools = new AgentScopeImageTools(
                generation, assets, assetGroups, asyncTasks, properties,
                mock(CreativeCritiqueService.class), mock(ConstraintExtractionService.class));
        AgentScopeTurnState turnState = new AgentScopeTurnState();

        Map<String, Object> result = tools.generateImage(
                "海边日落", List.of("a1"), List.of(), "1K", "high", "1",
                context(turnState));

        assertThat(result).containsEntry("ok", true);
        assertThat(result.get("assetIds")).isEqualTo(List.of("result-asset"));
        assertThat(turnState.compose("")).contains("![生成图片](/result.png)");
    }

    @Test
    void multiImageRequestCreatesBatchTaskWithoutSynchronousGeneration() {
        AgentGenerationService generation = mock(AgentGenerationService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        AgentScopeAssetGroupService assetGroups = mock(AgentScopeAssetGroupService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setCritiqueEnabled(false);
        when(assets.urlsByIds("c1", List.of())).thenReturn(List.of());
        when(asyncTasks.startBatchImageTask(any(), eq("海边日落"),
                eq(List.of("运球场景", "扣篮场景")), eq(List.of("/uploaded.png")),
                eq("1K"), eq("high"), eq(2), eq(false), eq(""), eq(""), eq(""), eq(""),
                any())).thenReturn("batch-1");
        AgentScopeImageTools tools = new AgentScopeImageTools(
                generation, assets, assetGroups, asyncTasks, properties,
                mock(CreativeCritiqueService.class), mock(ConstraintExtractionService.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "两张图", List.of("/uploaded.png"), null);

        Map<String, Object> result = tools.generateImageBatch(
                "海边日落", List.of("运球场景", "扣篮场景"), List.of(), List.of(),
                "1K", "high", "2", false, "", "", "", "",
                new AgentScopeRunContext(conversation, request, null, new AgentScopeTurnState()));

        assertThat(result).containsEntry("taskId", "batch-1")
                .containsEntry("taskKind", "image-batch");
        verify(generation, org.mockito.Mockito.never())
                .generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvedPlanBatchIgnoresTheInternalConfirmationThreshold() {
        AgentGenerationService generation = mock(AgentGenerationService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        AgentScopeAssetGroupService assetGroups = mock(AgentScopeAssetGroupService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setCritiqueEnabled(false);
        properties.setBatchConfirmThreshold(2);
        when(assets.urlsByIds("c1", List.of())).thenReturn(List.of());
        when(asyncTasks.startBatchImageTask(any(), any(), any(), any(), any(), any(), eq(6),
                eq(false), any(), any(), any(), any(), any())).thenReturn("batch-approved");
        AgentScopeImageTools tools = new AgentScopeImageTools(
                generation, assets, assetGroups, asyncTasks, properties,
                mock(CreativeCritiqueService.class), mock(ConstraintExtractionService.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "生成六张篮球图", List.of(), null);
        request.setAction("approve");
        request.setCheckpointAction("agentscope-confirm");

        Map<String, Object> result = tools.generateImageBatch(
                "篮球海报",
                List.of("1", "2", "3", "4", "5", "6"),
                List.of(), List.of(), "1K", "high", "6", false,
                "", "", "", "",
                new AgentScopeRunContext(conversation, request, null, new AgentScopeTurnState()));

        assertThat(result).containsEntry("taskId", "batch-approved")
                .containsEntry("count", 6);
    }

    @Test
    void videoToolCreatesAsyncTaskAndReturnsTaskIdWithoutPolling() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        when(assets.urlsByIds("c1", List.of("a1"))).thenReturn(List.of("/a1.png"));
        when(support.startVideoGenerationAsyncWithRole(any(), eq("镜头推进"), eq("8"),
                eq("16:9"), any(), eq(List.of()), any())).thenReturn("task-1");
        AgentScopeVideoTools tools = new AgentScopeVideoTools(support, assets);

        Map<String, Object> result = tools.generateVideo(
                "镜头推进", List.of("a1"), List.of(), "1080p", "16:9", "8",
                context(new AgentScopeTurnState()));

        assertThat(result).containsEntry("taskId", "task-1").containsEntry("status", "queued");
        verify(support).startVideoGenerationAsyncWithRole(any(), eq("镜头推进"), eq("8"),
                eq("16:9"), any(), eq(List.of()), any());
    }

    @Test
    void videoToolRejectsRequestsLongerThanFifteenSeconds() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        AgentScopeVideoTools tools = new AgentScopeVideoTools(support, assets);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "生成一个两分钟的视频", List.of(), null);
        AgentScopeRunContext context = new AgentScopeRunContext(
                conversation, request, null, new AgentScopeTurnState());

        assertThatThrownBy(() -> tools.generateVideo(
                "别墅漫游", List.of(), List.of(), "1080p", "16:9", "8", context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("15 秒")
                .hasMessageContaining("分镜");
        verify(support, never()).startVideoGenerationAsyncWithRole(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void storyboardShotKeepsItsOwnFifteenSecondLimitUnderLongOriginalRequest() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        when(support.startStoryboardShotVideoGenerationAsyncWithRole(
                any(), eq("scene-3"), eq("首个镜头"), eq("15"), eq("16:9"),
                any(), eq(List.of()), any()))
                .thenReturn("shot-task-1");
        AgentScopeVideoTools tools = new AgentScopeVideoTools(support, assets);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "生成一个两分钟的视频", List.of(), null);
        AgentScopeRunContext context = new AgentScopeRunContext(
                conversation, request, null, new AgentScopeTurnState());

        Map<String, Object> result = tools.generateVideoForStoryboard(
                "首个镜头", List.of(), "1080p", "16:9", "15", "scene-3", context);

        assertThat(result).containsEntry("taskId", "shot-task-1");
        verify(support).startStoryboardShotVideoGenerationAsyncWithRole(
                any(), eq("scene-3"), eq("首个镜头"), eq("15"), eq("16:9"),
                any(), eq(List.of()), any());
        verify(support, never()).startVideoGenerationAsyncWithRole(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void genericVideoToolCannotBypassAnActiveStoryboardWorkflow() {
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        AgentScopeVideoTools tools = new AgentScopeVideoTools(support, assets);
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        turnState.markStoryboardRequested();

        assertThatThrownBy(() -> tools.generateVideo(
                "镜头推进", List.of(), List.of(), "1080p", "16:9", "8",
                context(turnState)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("分镜");
        verify(support, never()).startVideoGenerationAsyncWithRole(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void genericImageToolCannotBypassAnActiveStoryboardWorkflow() {
        AgentGenerationService generation = mock(AgentGenerationService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        AgentScopeAssetGroupService assetGroups = mock(AgentScopeAssetGroupService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        AgentScopeImageTools tools = new AgentScopeImageTools(
                generation, assets, assetGroups, asyncTasks, new AgentScopeProperties(),
                mock(CreativeCritiqueService.class), mock(ConstraintExtractionService.class));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        turnState.markStoryboardRequested();

        assertThatThrownBy(() -> tools.generateImage(
                "建立镜头", List.of(), List.of(), "1K", "high", "1",
                context(turnState)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("分镜");
        verify(generation, never()).generateImage(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedImageAttemptDoesNotBlockRetryAndFailureNeverMarksSuccess() {
        AgentGenerationService generation = mock(AgentGenerationService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        AgentScopeAssetGroupService assetGroups = mock(AgentScopeAssetGroupService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setCritiqueEnabled(false);
        when(assets.urlsByIds("c1", List.of())).thenReturn(List.of());
        when(generation.generateImage(any(), isNull(), eq("四宫格"), isNull(), isNull(), isNull(),
                eq(1), eq("edit"), eq(List.of("/uploaded.png")), isNull()))
                .thenThrow(new RuntimeException("参考图不存在: content"))
                .thenReturn(Map.of("imageUrls", List.of("/result.png")));
        AgentScopeImageTools tools = new AgentScopeImageTools(
                generation, assets, assetGroups, asyncTasks, properties,
                mock(CreativeCritiqueService.class), mock(ConstraintExtractionService.class));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "四宫格", List.of("/uploaded.png"), null);
        AgentScopeRunContext context = new AgentScopeRunContext(
                conversation, request, null, turnState);

        assertThatThrownBy(() -> tools.generateImage(
                "四宫格", List.of(), List.of(), null, null, "1", context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("参考图不存在");
        assertThat(turnState.hasStartedMedia("image")).isFalse();

        Map<String, Object> retry = tools.generateImage(
                "四宫格", List.of(), List.of(), null, null, "1", context);
        assertThat(retry).containsEntry("ok", true);
        assertThat(turnState.hasStartedMedia("image")).isTrue();

        assertThatThrownBy(() -> tools.generateImage(
                "四宫格", List.of(), List.of(), null, null, "1", context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已经成功生成");
    }

    private static AgentScopeRunContext context(AgentScopeTurnState turnState) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        return new AgentScopeRunContext(conversation, null, null, turnState);
    }
}
