package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.response.SceneFull;
import com.storyboard.dto.request.SceneReferenceLinkRequest;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.service.AssetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class InternalApiServiceImplStoryboardAppendTest {

    @Test
    void appendProjectScenesPreservesExistingRowsAndNumbersNewScenesAfterTheMaximum() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper promptMapper = mock(ScenePromptMapper.class);
        when(projectMapper.lockById("project-1")).thenReturn(new Project());
        when(sceneMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(scene(16)));
        when(sceneMapper.insert(any(Scene.class))).thenReturn(1);
        when(promptMapper.insert(any(ScenePrompt.class))).thenReturn(1);
        InternalApiServiceImpl service = new InternalApiServiceImpl(
                projectMapper,
                sceneMapper,
                promptMapper,
                mock(SceneReferenceImageMapper.class),
                mock(SceneAssetMapper.class),
                mock(AssetService.class));

        List<String> sceneIds = service.appendProjectScenes(
                "project-1", List.of(fullScene("第三段"), fullScene("第四段")));

        assertThat(sceneIds).hasSize(2).allMatch(id -> id != null && !id.isBlank());
        ArgumentCaptor<Scene> captor = ArgumentCaptor.forClass(Scene.class);
        verify(sceneMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(Scene::getSceneNumber)
                .containsExactly(17, 18);
        assertThat(captor.getAllValues()).extracting(Scene::getId)
                .containsExactlyElementsOf(sceneIds);
        verify(projectMapper).lockById("project-1");
    }

    @Test
    void replaceManagedVideoFramesDeletesOldManagedFramesAndInsertsNewOnes() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper promptMapper = mock(ScenePromptMapper.class);
        SceneReferenceImageMapper referenceMapper = mock(SceneReferenceImageMapper.class);
        when(referenceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        InternalApiServiceImpl service = new InternalApiServiceImpl(
                projectMapper,
                sceneMapper,
                promptMapper,
                referenceMapper,
                mock(SceneAssetMapper.class),
                mock(AssetService.class));

        service.replaceManagedVideoFrames("scene-2", List.of(
                new SceneReferenceLinkRequest(
                        "/api/files/images/current.png", "image", "video", "FIRST_FRAME",
                        null, null, "scene_image", "scene-2", "conversation-1"),
                new SceneReferenceLinkRequest(
                        "/api/files/images/next.png", "image", "video", "LAST_FRAME",
                        null, null, "scene_image", "scene-3", "conversation-1")));

        verify(referenceMapper).delete(any(LambdaQueryWrapper.class));
        ArgumentCaptor<com.storyboard.entity.SceneReferenceImage> rows =
                ArgumentCaptor.forClass(com.storyboard.entity.SceneReferenceImage.class);
        verify(referenceMapper, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(
                        com.storyboard.entity.SceneReferenceImage::getRole,
                        com.storyboard.entity.SceneReferenceImage::getSourceType,
                        com.storyboard.entity.SceneReferenceImage::getSourceId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FIRST_FRAME", "scene_image", "scene-2"),
                        org.assertj.core.groups.Tuple.tuple("LAST_FRAME", "scene_image", "scene-3"));
    }

    private static Scene scene(int number) {
        Scene scene = new Scene();
        scene.setId("existing-" + number);
        scene.setProjectId("project-1");
        scene.setSceneNumber(number);
        return scene;
    }

    private static SceneFull fullScene(String script) {
        return new SceneFull(
                null, "project-1", "conversation-1", 1,
                script, "image", "video", "", "推近", "中景", "",
                null, null, 3, null, null, "pending", "pending",
                null, null, null, null, null, null, null, null, null,
                null, null);
    }
}
