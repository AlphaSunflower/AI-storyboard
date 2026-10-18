package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentScopeStoryboardToolsTest {

    @Test
    void existingProjectAsksForAppendOverwriteOrDeleteBeforeStarting() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene existing = new Scene();
        existing.setId("scene-1");
        existing.setProjectId("project-1");
        existing.setSceneNumber(1);
        when(storyboardClient.getProjectScenesFresh("project-1"))
                .thenReturn(List.of(existing));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production,
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tools.tools().getFirst()
                .invoke(Map.of("prompt", "生成分镜"), param("project-1"));

        // guard 已摊平：内层失败直接成为顶层结果，ok:false 不再被 {ok:true,result:{...}} 伪装
        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("needsChoice", true)
                .containsEntry("existingSceneCount", 1);
        verifyNoInteractions(asyncTasks);
    }

    @Test
    void existingProjectMarksPendingCommitChoiceForRuntimeFallback() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene existing = new Scene();
        existing.setId("scene-1");
        existing.setProjectId("project-1");
        existing.setSceneNumber(1);
        when(storyboardClient.getProjectScenesFresh("project-1")).thenReturn(List.of(existing));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production,
                mock(AgentScopeAsyncTaskService.class),
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation,
                new OrchestrationRequest(conversation, "生成分镜", List.of(), null),
                null,
                turnState);
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, runContext)
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name(AgentScopeStoryboardTools.START_STORYBOARD)
                        .input(Map.of("prompt", "生成分镜"))
                        .build())
                .input(Map.of("prompt", "生成分镜"))
                .runtimeContext(runtime)
                .build();

        tools.tools().getFirst().invoke(Map.of("prompt", "生成分镜"), param);

        assertThat(turnState.takePendingStoryboardCommitCount()).isEqualTo(1);
    }

    @Test
    void deleteCommitModeClearsProjectWithoutStartingGeneration() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene existing = new Scene();
        existing.setId("scene-1");
        existing.setProjectId("project-1");
        existing.setSceneNumber(1);
        when(storyboardClient.getProjectScenesFresh("project-1"))
                .thenReturn(List.of(existing));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production,
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tools.tools().getFirst()
                .invoke(Map.of("prompt", "生成分镜", "commit_mode", "delete"),
                        param("project-1"));

        // guard 已摊平：删除成功的结果直接在顶层
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("deleted", true);
        verify(storyboardClient).deleteProjectScenes("project-1");
        verifyNoInteractions(asyncTasks);
    }

    @Test
    void exposesStagedStoryboardAndPerShotMediaTools() {
        AgentScopeStoryboardTools tools = tools();

        assertThat(tools.tools()).extracting(AgentScopeTool::getName)
                .containsExactly(
                        AgentScopeStoryboardTools.START_STORYBOARD,
                        AgentScopeStoryboardTools.REVISE_STORYBOARD,
                        AgentScopeStoryboardTools.APPROVE_STORYBOARD,
                        AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGE,
                        AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO,
                        AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS,
                        AgentScopeStoryboardTools.LIST_STORYBOARD_DRAFT,
                        AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD,
                        AgentScopeStoryboardTools.READ_PROJECT_SCENE,
                        AgentScopeStoryboardTools.UPDATE_PROJECT_STORYBOARD_SHOT,
                        AgentScopeStoryboardTools.UPSERT_STORYBOARD_SHOT,
                        AgentScopeStoryboardTools.DELETE_STORYBOARD_DRAFT_SHOT,
                        AgentScopeStoryboardTools.REORDER_STORYBOARD_DRAFT,
                        AgentScopeStoryboardTools.DELETE_PROJECT_STORYBOARD_SHOT,
                        AgentScopeStoryboardTools.CLEAR_PROJECT_STORYBOARD,
                        AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES);
    }

    @Test
    void updatesOnlySpecifiedParametersOnMatchingProjectScene() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene existing = scene("scene-3", 3, "旧脚本");
        existing.setImageModel("old-model");
        Scene updated = scene("scene-3", 3, "新脚本");
        updated.setImageModel("new-image-model");
        updated.setImageSize("1024x1024");
        updated.setDuration(8);
        updated.setImagePrompt("新图片提示词");
        when(storyboardClient.getProjectScenesFresh("project-1"))
                .thenReturn(List.of(existing), List.of(updated));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production,
                mock(AgentScopeAsyncTaskService.class),
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, "update_project_storyboard_shot")
                .invoke(Map.of(
                        "scene_number", 3,
                        "script_content", "新脚本",
                        "image_prompt", "新图片提示词",
                        "image_model", "new-image-model",
                        "image_size", "1024x1024",
                        "duration", 8), param("project-1"));

        // guard 已摊平：更新结果直接在顶层
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("sceneId", "scene-3")
                .containsEntry("sceneNumber", 3);
        ArgumentCaptor<Scene> updatedScene = ArgumentCaptor.forClass(Scene.class);
        verify(storyboardClient).updateScene(updatedScene.capture());
        assertThat(updatedScene.getValue())
                .extracting(Scene::getId, Scene::getProjectId, Scene::getScriptContent,
                        Scene::getImagePrompt, Scene::getImageModel, Scene::getImageSize,
                        Scene::getDuration)
                .containsExactly("scene-3", "project-1", "新脚本", "新图片提示词",
                        "new-image-model", "1024x1024", 8);
        assertThat(updatedScene.getValue().getVideoPrompt()).isNull();
        assertThat(updatedScene.getValue().getVideoModel()).isNull();
    }

    @Test
    void storyboardVideoAutomaticallyQueuesBackgroundTask() {
        AgentScopeVideoTools videoTools = mock(AgentScopeVideoTools.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene scene = scene("scene-3", 3, "第三镜");
        scene.setVideoPrompt("镜头运动");
        scene.setImageUrl("/api/files/images/current.png");
        when(storyboardClient.getProjectScenesFresh("project-1")).thenReturn(List.of(scene));
        when(storyboardClient.getSceneReferences("scene-3")).thenReturn(List.of());
        when(asyncTasks.startStoryboardVideoTask(any(), eq(List.of("scene-3")), eq(false), anyString()))
                .thenReturn("video-task-1");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                videoTools,
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO)
                .invoke(Map.of("scene_number", 3), param("project-1"));

        // guard 已摊平：排队结果直接在顶层
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("taskId", "video-task-1")
                .containsEntry("taskKind", "long-tool");
        verify(asyncTasks).startStoryboardVideoTask(
                any(), eq(List.of("scene-3")), eq(false), anyString());
        verifyNoInteractions(videoTools);
    }

    @Test
    void storyboardVideoUsesSelectedFirstAndLastFrameReferences() {
        AgentScopeVideoTools videoTools = mock(AgentScopeVideoTools.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene scene = scene("scene-3", 3, "第三镜");
        scene.setVideoPrompt("镜头运动");
        scene.setImageUrl("/api/files/images/current.png");
        when(storyboardClient.getProjectScenesFresh("project-1")).thenReturn(List.of(scene));
        when(storyboardClient.getSceneReferences("scene-3")).thenReturn(List.of(
                Map.of("type", "image", "url", "/api/files/images/ref.png")));
        when(videoTools.generateVideoForStoryboard(
                anyString(), anyList(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("ok", true, "taskId", "video-task-1"));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                mock(AgentScopeAsyncTaskService.class),
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                videoTools,
                storyboardClient);

        tool(tools, AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO)
                .invoke(Map.of(
                        "scene_number", 3,
                        "reference_mode", "first_last_frame",
                        "resolution", "720p",
                        "aspect_ratio", "16:9",
                        "duration", "6"), param("project-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReferenceImage>> references =
                ArgumentCaptor.forClass(List.class);
        verify(videoTools).generateVideoForStoryboard(
                eq("镜头运动"), references.capture(), eq(""), eq("720p"), eq("16:9"), eq("6"),
                eq("scene-3"), any());
        assertThat(references.getValue()).hasSize(2);
        assertThat(references.getValue())
                .extracting(ReferenceImage::role)
                .containsExactly(ReferenceImageRole.FIRST_FRAME, ReferenceImageRole.LAST_FRAME);
    }

    @Test
    void internalShotWriterSchemaCarriesExactlyOneShotAndNeverAnArrayPayload() {
        AgentScopeTool tool = tools().upsertShotTool();

        assertThat(tool.getName()).isEqualTo(AgentScopeStoryboardTools.UPSERT_STORYBOARD_SHOT);
        Map<String, Object> schema = tool.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("run_id", "shot_id", "shot_ordinal", "reference_asset_ids");
        assertThat(properties).doesNotContainKey("shots");
        List<String> required = ((List<?>) schema.get("required")).stream()
                .map(String::valueOf).toList();
        assertThat(required).contains("run_id", "shot_id", "shot_ordinal");
    }

    @Test
    void batchImageToolTargetsProjectScenesWithoutARunAndSkipsExistingImages() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene withImage = scene("scene-1", 1, "分镜一");
        withImage.setImageUrl("/api/files/images/existing.png");
        Scene missing = scene("scene-2", 2, "分镜二");
        missing.setImagePrompt("第二镜图片提示词");
        when(storyboardClient.getProjectScenes("project-1"))
                .thenReturn(List.of(missing, withImage));
        when(storyboardClient.getSceneReferences("scene-2"))
                .thenReturn(List.of(Map.of("type", "image", "url", "/api/files/images/ref.png")));
        when(asyncTasks.startStoryboardImageTask(any(), any(), eq(false), anyString()))
                .thenReturn("task-1");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production,
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(tools, AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES)
                .invoke(Map.of(), param("project-1"));

        // guard 已摊平：入队结果直接在顶层
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("queued", true)
                .containsEntry("count", 1)
                .containsEntry("taskId", "task-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentScopeAsyncTaskService.StoryboardImageTarget>> targets =
                ArgumentCaptor.forClass(List.class);
        verify(asyncTasks).startStoryboardImageTask(any(), targets.capture(), eq(false), anyString());
        assertThat(targets.getValue()).hasSize(1);
        assertThat(targets.getValue().getFirst())
                .extracting(AgentScopeAsyncTaskService.StoryboardImageTarget::sceneId,
                        AgentScopeAsyncTaskService.StoryboardImageTarget::prompt)
                .containsExactly("scene-2", "第二镜图片提示词");
        assertThat(targets.getValue().getFirst().sourceUrls())
                .containsExactly("/api/files/images/ref.png");
        verify(production, never()).getLatestOwned(anyString(), anyString(), anyString());
    }

    @Test
    void batchImageToolFiltersBySceneNumbers() {
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        when(storyboardClient.getProjectScenes("project-1"))
                .thenReturn(List.of(
                        scene("scene-1", 1, "第一镜"),
                        scene("scene-2", 2, "第二镜")));
        when(storyboardClient.getSceneReferences(anyString())).thenReturn(List.of());
        when(asyncTasks.startStoryboardImageTask(any(), any(), eq(false), anyString()))
                .thenReturn("task-1");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        tool(tools, AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES)
                .invoke(Map.of("scene_numbers", List.of(2)), param("project-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentScopeAsyncTaskService.StoryboardImageTarget>> targets =
                ArgumentCaptor.forClass(List.class);
        verify(asyncTasks).startStoryboardImageTask(any(), targets.capture(), eq(false), anyString());
        assertThat(targets.getValue()).extracting(AgentScopeAsyncTaskService.StoryboardImageTarget::sceneId)
                .containsExactly("scene-2");
    }

    @Test
    void approvedPlanCanOverwriteStoryboardImagesWithoutASecondConfirmation() {
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene existing = scene("scene-1", 1, "第一镜");
        existing.setImageUrl("/api/files/images/existing.png");
        when(storyboardClient.getProjectScenes("project-1")).thenReturn(List.of(existing));
        when(storyboardClient.getSceneReferences("scene-1")).thenReturn(List.of());
        when(asyncTasks.startStoryboardImageTask(any(), any(), eq(true), anyString()))
                .thenReturn("task-overwrite");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES)
                .invoke(Map.of("overwrite", true), param("project-1", true));

        // guard 已摊平：覆盖入队结果直接在顶层
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("queued", true)
                .containsEntry("taskId", "task-overwrite");
    }

    @Test
    void batchVideoToolQueuesOnlyMissingOrFailedScenesByDefault() {
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene completed = scene("scene-1", 1, "已完成");
        completed.setVideoUrl("/api/files/videos/done.mp4");
        completed.setVideoStatus("completed");
        Scene missing = scene("scene-2", 2, "缺视频");
        missing.setVideoStatus("failed");
        when(storyboardClient.getProjectScenesFresh("project-1"))
                .thenReturn(List.of(completed, missing));
        when(asyncTasks.startStoryboardVideoTask(any(), eq(List.of("scene-2")), eq(false), anyString()))
                .thenReturn("video-batch-1");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS)
                .invoke(Map.of(), param("project-1"));

        // guard 已摊平：批量入队结果直接在顶层
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("status", "queued")
                .containsEntry("taskId", "video-batch-1")
                .containsEntry("count", 1);
    }

    @Test
    void listProjectStoryboardReportsImageAndVideoCounts() {
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene scene = scene("scene-1", 1, "分镜一");
        scene.setImageUrls("/api/files/images/a.png,/api/files/images/b.png");
        scene.setImageUrl("/api/files/images/a.png");
        scene.setVideoUrl("/api/files/videos/a.mp4");
        when(storyboardClient.getProjectScenes("project-1")).thenReturn(List.of(scene));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                mock(AgentScopeAsyncTaskService.class),
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(tools, AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD)
                .invoke(Map.of(), param("project-1"));

        // guard 已摊平：列表载荷直接在顶层
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) result.get("scenes");
        assertThat(scenes.getFirst())
                .containsEntry("imageCount", 2)
                .containsEntry("videoCount", 1)
                .containsEntry("needsImage", false)
                .containsEntry("needsVideo", false);
    }

    @Test
    void failedStoryboardStartStillLocksTheTurnIntoStoryboardMode() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        when(asyncTasks.startStoryboardTask(any(), any(), any()))
                .thenThrow(new RuntimeException("database unavailable"));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production,
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                mock(StoryboardClient.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation,
                new OrchestrationRequest(conversation, "生成分镜", List.of(), null),
                null,
                turnState);
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId("c1")
                .put(AgentScopeRunContext.class, runContext)
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name(AgentScopeStoryboardTools.START_STORYBOARD)
                        .input(Map.of("prompt", "生成分镜"))
                        .build())
                .input(Map.of("prompt", "生成分镜"))
                .runtimeContext(runtime)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tools.tools().getFirst()
                .invoke(Map.of("prompt", "生成分镜"), param);

        assertThat(result).containsEntry("ok", false);
        assertThat(turnState.isStoryboardRequested()).isTrue();
    }

    @Test
    void reviseStoryboardCreatesAsyncTaskInsteadOfRunningInline() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        when(asyncTasks.startStoryboardRevisionTask(
                any(), eq("run-1"), eq("PLOT_REVIEW"), eq("不要出现人物"), eq(""), eq("call-1")))
                .thenReturn("task-revision");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production,
                asyncTasks,
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                mock(StoryboardClient.class));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "修改分镜", List.of(), null);
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation, request, support, turnState);
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, runContext)
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name(AgentScopeStoryboardTools.REVISE_STORYBOARD)
                        .input(Map.of(
                                "run_id", "run-1",
                                "stage", "PLOT_REVIEW",
                                "instruction", "不要出现人物"))
                        .build())
                .input(Map.of(
                        "run_id", "run-1",
                        "stage", "PLOT_REVIEW",
                        "instruction", "不要出现人物"))
                .runtimeContext(runtime)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.REVISE_STORYBOARD)
                .invoke(Map.of(
                        "run_id", "run-1",
                        "stage", "PLOT_REVIEW",
                        "instruction", "不要出现人物"), param);

        // guard 已摊平：修订入队结果直接在顶层
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("taskId", "task-revision")
                .containsEntry("taskKind", "long-tool");
        assertThat(turnState.taskCards())
                .extracting(card -> card.get("taskId"))
                .containsExactly("task-revision");
        verify(production, never()).revise(any(), any(), any(), any());
    }

    @Test
    void readProjectSceneReturnsEveryFieldLikeTheListTool() {
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene target = scene("scene-2", 2, "雨夜街头，霓虹倒影");
        target.setImageUrl("/api/files/images/a.png");
        target.setImagePrompt("霓虹雨夜特写");
        target.setVideoPrompt("霓虹雨夜运镜");
        target.setNegativePrompt("不要白天");
        target.setCameraMovement("缓推");
        target.setShotType("特写");
        target.setSoundDesign("雨声");
        target.setDuration(5);
        when(storyboardClient.getProjectScenes("project-1")).thenReturn(List.of(target));
        AgentScopeStoryboardTools tools = tools(storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.READ_PROJECT_SCENE)
                .invoke(Map.of("scene_number", "2"), param("project-1"));

        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("projectId", "project-1")
                .containsKey("scene");
        @SuppressWarnings("unchecked")
        Map<String, Object> sceneFields = (Map<String, Object>) result.get("scene");
        assertThat(sceneFields)
                .containsAllEntriesOf(Map.ofEntries(
                        Map.entry("sceneId", "scene-2"),
                        Map.entry("sceneNumber", 2),
                        Map.entry("scriptContent", "雨夜街头，霓虹倒影"),
                        Map.entry("imagePrompt", "霓虹雨夜特写"),
                        Map.entry("videoPrompt", "霓虹雨夜运镜"),
                        Map.entry("negativePrompt", "不要白天"),
                        Map.entry("cameraMovement", "缓推"),
                        Map.entry("shotType", "特写"),
                        Map.entry("soundDesign", "雨声"),
                        Map.entry("hasImage", true),
                        Map.entry("hasVideo", false),
                        Map.entry("needsImage", false),
                        Map.entry("needsVideo", true),
                        Map.entry("imageCount", 1),
                        Map.entry("videoCount", 0)));
    }

    @Test
    void readProjectSceneRejectsSceneOutsideTheCurrentProject() {
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        // 目标分镜属于别的项目，当前会话的项目列表里根本没有它
        when(storyboardClient.getProjectScenes("project-1")).thenReturn(List.of());
        when(storyboardClient.getScene("scene-foreign")).thenReturn(null);
        AgentScopeStoryboardTools tools = tools(storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> forbidden = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.READ_PROJECT_SCENE)
                .invoke(Map.of("scene_id", "scene-foreign"), param("project-1"));

        assertThat(forbidden)
                .containsEntry("ok", false)
                .containsEntry("message", "未找到指定的项目分镜");
        assertThat(forbidden).doesNotContainKey("scene");
        // 越权场景不允许走单镜直读通道
        verify(storyboardClient, never()).getScene("scene-foreign");
    }

    @Test
    void readProjectSceneRequiresALocator() {
        AgentScopeStoryboardTools tools = tools();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.READ_PROJECT_SCENE)
                .invoke(Map.of(), param("project-1"));

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("message"))).contains("scene_id 或 scene_number");
    }

    /**
     * 模型漏传 asset_ids 时，服务端必须把会话素材带进分镜生产请求。
     *
     * <p>历史事实：47 次 start_storyboard_production 调用里 asset_ids 一次没传，
     * 于是伪分镜阶段的「可用资产」永远是空的，逐镜绑定与写库关联全是空转。
     */
    @Test
    void startStoryboardAutoInjectsSessionAssetsWhenAssetIdsOmitted() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        SessionAssetService sessionAssets = mock(SessionAssetService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        when(sessionAssets.list(eq("conversation-1"), eq("image"), anyInt())).thenReturn(List.of(
                new SessionAssetRef("asset-1", "image", "https://files/hero.png",
                        "uploaded", OffsetDateTime.now(), "角色｜林知夏：短发女性，黑色风衣"),
                new SessionAssetRef("asset-2", "image", "https://files/warehouse.png",
                        "uploaded", OffsetDateTime.now(), "场景｜旧仓库：钢架与昏黄吊灯")));
        when(storyboardClient.getProjectScenesFresh("project-1")).thenReturn(List.of());
        when(asyncTasks.startPlotReviewStoryboardTask(any(), any(), any(), any()))
                .thenReturn("task-1");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production, asyncTasks, sessionAssets,
                mock(AgentScopeImageTools.class), mock(AgentScopeVideoTools.class), storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.START_STORYBOARD)
                .invoke(Map.of("prompt", "生成分镜"), param("project-1"));

        ArgumentCaptor<StoryboardProductionRequest> request =
                ArgumentCaptor.forClass(StoryboardProductionRequest.class);
        verify(asyncTasks).startPlotReviewStoryboardTask(any(), request.capture(), any(), any());
        assertThat(request.getValue().assets())
                .extracting(AssetRef::assetId)
                .containsExactly("asset-1", "asset-2");
        assertThat(request.getValue().assets())
                .extracting(AssetRef::url)
                .containsExactly("https://files/hero.png", "https://files/warehouse.png");
        // 素材语义必须进 purpose：模型就是靠它判断「这镜该不该用这张图」
        assertThat(request.getValue().assets())
                .extracting(AssetRef::purpose)
                .containsExactly(
                        "角色｜林知夏：短发女性，黑色风衣",
                        "场景｜旧仓库：钢架与昏黄吊灯");
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("assetCount", 2)
                .containsEntry("assetsAutoInjected", true);
    }

    /** 显式指定 asset_ids 时不得被会话素材顶掉，也不许再查会话素材列表。 */
    @Test
    void explicitAssetIdsSkipSessionAssetFallback() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        AgentScopeAsyncTaskService asyncTasks = mock(AgentScopeAsyncTaskService.class);
        SessionAssetService sessionAssets = mock(SessionAssetService.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        when(sessionAssets.refsByIds("conversation-1", List.of("chosen"))).thenReturn(List.of(
                new SessionAssetRef("chosen", "image", "https://files/chosen.png",
                        "uploaded", OffsetDateTime.now(), "道具｜旧木椅：靠窗，木纹明显")));
        when(storyboardClient.getProjectScenesFresh("project-1")).thenReturn(List.of());
        when(asyncTasks.startPlotReviewStoryboardTask(any(), any(), any(), any()))
                .thenReturn("task-2");
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                production, asyncTasks, sessionAssets,
                mock(AgentScopeImageTools.class), mock(AgentScopeVideoTools.class), storyboardClient);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool(
                tools, AgentScopeStoryboardTools.START_STORYBOARD)
                .invoke(Map.of("prompt", "生成分镜", "asset_ids", List.of("chosen")),
                        param("project-1"));

        ArgumentCaptor<StoryboardProductionRequest> request =
                ArgumentCaptor.forClass(StoryboardProductionRequest.class);
        verify(asyncTasks).startPlotReviewStoryboardTask(any(), request.capture(), any(), any());
        assertThat(request.getValue().assets())
                .extracting(AssetRef::assetId)
                .containsExactly("chosen");
        assertThat(request.getValue().assets())
                .extracting(AssetRef::purpose)
                .containsExactly("道具｜旧木椅：靠窗，木纹明显");
        verify(sessionAssets, never()).list(anyString(), anyString(), anyInt());
        assertThat(result).containsEntry("assetsAutoInjected", false);
    }

    private static AgentScopeStoryboardTools tools() {
        return tools(mock(StoryboardClient.class));
    }

    private static AgentScopeStoryboardTools tools(StoryboardClient storyboardClient) {
        return new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                mock(AgentScopeAsyncTaskService.class),
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                mock(AgentScopeVideoTools.class),
                storyboardClient);
    }

    private static ToolCallParam param(String projectId) {
        return param(projectId, false);
    }

    private static ToolCallParam param(String projectId, boolean approvedPermissionResume) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId(projectId);
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "生成分镜", List.of(), null);
        if (approvedPermissionResume) {
            request.setAction("approve");
            request.setCheckpointAction("agentscope-confirm");
        }
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation,
                request,
                null,
                new AgentScopeTurnState());
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, runContext)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name(AgentScopeStoryboardTools.START_STORYBOARD)
                        .input(Map.of("prompt", "生成分镜"))
                        .build())
                .input(Map.of("prompt", "生成分镜"))
                .runtimeContext(runtime)
                .build();
    }

    private static Scene scene(String id, int number, String scriptContent) {
        Scene scene = new Scene();
        scene.setId(id);
        scene.setProjectId("project-1");
        scene.setSceneNumber(number);
        scene.setScriptContent(scriptContent);
        return scene;
    }

    private static AgentScopeTool tool(AgentScopeStoryboardTools tools, String name) {
        return tools.tools().stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
    }
}
