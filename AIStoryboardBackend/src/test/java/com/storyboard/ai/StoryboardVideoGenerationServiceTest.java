package com.storyboard.ai;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.storyboard.common.generation.GenerationRecordWriter;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardVideoGenerationServiceTest {

    @Test
    void createsTaskRecordAndDelegatesPolling() {
        VideoGenerationService delegate = mock(VideoGenerationService.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper scenePromptMapper = mock(ScenePromptMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        GenerationRecordWriter writer = mock(GenerationRecordWriter.class);
        AiConfigProperties config = new AiConfigProperties(new AiConfigProperties.Gateway());

        Project project = new Project();
        project.setId("project-1");
        project.setUserId("user-1");
        Scene scene = new Scene();
        scene.setId("scene-1");
        scene.setProjectId("project-1");
        ScenePrompt prompt = new ScenePrompt();
        prompt.setSceneId("scene-1");
        prompt.setVideoModel("scene-video");
        when(sceneMapper.selectById("scene-1")).thenReturn(scene);
        when(scenePromptMapper.selectById("scene-1")).thenReturn(prompt);
        when(projectMapper.selectById("project-1")).thenReturn(project);
        when(writer.create(any())).thenReturn("record-1");
        when(delegate.createVideoTask(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn("task-1");
        when(delegate.pollVideoTask("task-1")).thenReturn(Map.of("status", "processing"));

        StoryboardVideoGenerationService service = new StoryboardVideoGenerationService(
                delegate, config, gateway, sceneMapper, scenePromptMapper, projectMapper, writer);

        service.createVideoTask("scene-1", "prompt", null, null, null, null,
                null, null, null, List.of(), null, List.of(), List.of());
        service.pollVideoTask("task-1");

        verify(writer).attachTaskId("record-1", "task-1");
        verify(delegate).pollVideoTask("task-1");
    }
}
