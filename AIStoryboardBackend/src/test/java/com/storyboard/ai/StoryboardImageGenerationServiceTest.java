package com.storyboard.ai;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.ImageGenerationService;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordTypes;
import com.storyboard.common.generation.GenerationRecordUpdate;
import com.storyboard.common.generation.GenerationRecordWriter;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardImageGenerationServiceTest {

    @Test
    void recordsCompletedStoryboardImage() {
        Fixture fixture = fixture();
        when(fixture.delegate.generateImages(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("/api/files/images/a.png", "/api/files/images/b.png"));
        when(fixture.writer.create(any())).thenReturn("record-1");

        String result = fixture.service.generateImage(
                "scene-1", "prompt", null, "1024x1024", "high", "16:9",
                List.of("/api/files/images/ref.png"), null, null, 2);

        assertEquals("/api/files/images/a.png", result);
        ArgumentCaptor<GenerationRecord> record = ArgumentCaptor.forClass(GenerationRecord.class);
        verify(fixture.writer).create(record.capture());
        assertEquals(GenerationRecordTypes.STORYBOARD_IMAGE, record.getValue().genType());
        assertEquals("scene-model", record.getValue().model());
        verify(fixture.writer).update(
                org.mockito.ArgumentMatchers.eq("record-1"),
                org.mockito.ArgumentMatchers.argThat(update ->
                        GenerationRecordTypes.COMPLETED.equals(update.status())
                                && update.resultUrls().size() == 2));
    }

    @Test
    void marksRecordFailedWhenDelegateThrows() {
        Fixture fixture = fixture();
        when(fixture.delegate.generateImages(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("upstream failed"));
        when(fixture.writer.create(any())).thenReturn("record-1");

        assertThrows(IllegalStateException.class, () -> fixture.service.generateImage(
                "scene-1", "prompt", "model-x", null, null, null,
                List.of(), null, null, null));

        verify(fixture.writer).update(
                org.mockito.ArgumentMatchers.eq("record-1"),
                org.mockito.ArgumentMatchers.argThat(update ->
                        GenerationRecordTypes.FAILED.equals(update.status())
                                && "upstream failed".equals(update.error())));
    }

    private static Fixture fixture() {
        ImageGenerationService delegate = mock(ImageGenerationService.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper scenePromptMapper = mock(ScenePromptMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        GenerationRecordWriter writer = mock(GenerationRecordWriter.class);

        Project project = new Project();
        project.setId("project-1");
        project.setUserId("user-1");
        Scene scene = new Scene();
        scene.setId("scene-1");
        scene.setProjectId("project-1");
        ScenePrompt prompt = new ScenePrompt();
        prompt.setSceneId("scene-1");
        prompt.setImageModel("scene-model");

        when(sceneMapper.selectById("scene-1")).thenReturn(scene);
        when(scenePromptMapper.selectById("scene-1")).thenReturn(prompt);
        when(projectMapper.selectById("project-1")).thenReturn(project);
        when(gateway.getDefaultImageModel()).thenReturn("gpt-image-2");

        return new Fixture(
                new StoryboardImageGenerationService(
                        delegate, gateway, sceneMapper, scenePromptMapper, projectMapper, writer),
                delegate, writer);
    }

    private record Fixture(StoryboardImageGenerationService service,
                           ImageGenerationService delegate,
                           GenerationRecordWriter writer) {
    }
}
