package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) result.get("result");
        assertThat(choice)
                .containsEntry("ok", false)
                .containsEntry("needsChoice", true)
                .containsEntry("existingSceneCount", 1);
        verifyNoInteractions(asyncTasks);
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

        @SuppressWarnings("unchecked")
        Map<String, Object> deleted = (Map<String, Object>) result.get("result");
        assertThat(deleted)
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
                        AgentScopeStoryboardTools.LIST_STORYBOARD_DRAFT,
                        AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD,
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

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.get("result");
        assertThat(payload)
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
    void storyboardVideoAutomaticallyUsesSingleSceneImageAsFirstFrame() {
        AgentScopeVideoTools videoTools = mock(AgentScopeVideoTools.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        Scene scene = scene("scene-3", 3, "第三镜");
        scene.setVideoPrompt("镜头运动");
        scene.setImageUrl("/api/files/images/current.png");
        when(storyboardClient.getProjectScenesFresh("project-1")).thenReturn(List.of(scene));
        when(storyboardClient.getSceneReferences("scene-3")).thenReturn(List.of());
        when(videoTools.generateVideoForStoryboard(
                anyString(), anyList(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("ok", true, "taskId", "video-task-1"));
        AgentScopeStoryboardTools tools = new AgentScopeStoryboardTools(
                mock(StoryboardProductionService.class),
                mock(AgentScopeAsyncTaskService.class),
                mock(SessionAssetService.class),
                mock(AgentScopeImageTools.class),
                videoTools,
                storyboardClient);

        tool(tools, AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO)
                .invoke(Map.of("scene_number", 3), param("project-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReferenceImage>> references =
                ArgumentCaptor.forClass(List.class);
        verify(videoTools).generateVideoForStoryboard(
                eq("镜头运动"), references.capture(), anyString(), anyString(), anyString(), any());
        assertThat(references.getValue())
                .extracting(ReferenceImage::role)
                .containsExactly(ReferenceImageRole.FIRST_FRAME);
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
                anyString(), anyList(), anyString(), anyString(), anyString(), any()))
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
                eq("镜头运动"), references.capture(), eq("720p"), eq("16:9"), eq("6"), any());
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

        @SuppressWarnings("unchecked")
        Map<String, Object> queued = (Map<String, Object>) result.get("result");
        assertThat(queued)
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

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) payload.get("scenes");
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
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId(projectId);
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation,
                new OrchestrationRequest(conversation, "生成分镜", List.of(), null),
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
