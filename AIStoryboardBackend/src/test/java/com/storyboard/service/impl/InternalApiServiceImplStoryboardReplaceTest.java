package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.response.SceneFull;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.mapper.AssetImageMapper;
import com.storyboard.mapper.AssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalApiServiceImplStoryboardReplaceTest {

    @Test
    void replaceProjectScenesDeletesOldRowsAndReturnsNewSceneIdsInOrder() {
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper promptMapper = mock(ScenePromptMapper.class);
        when(sceneMapper.insert(any(Scene.class))).thenReturn(1);
        when(promptMapper.insert(any(ScenePrompt.class))).thenReturn(1);
        InternalApiServiceImpl service = new InternalApiServiceImpl(
                mock(ProjectMapper.class),
                sceneMapper,
                promptMapper,
                mock(SceneReferenceImageMapper.class),
                mock(SceneAssetMapper.class),
                mock(AssetMapper.class),
                mock(AssetImageMapper.class));

        List<String> sceneIds = service.replaceProjectScenes("project-1", List.of(
                scene("第一镜"),
                scene("第二镜")));

        assertThat(sceneIds).hasSize(2).allMatch(id -> id != null && !id.isBlank());
        verify(sceneMapper).delete(any(LambdaQueryWrapper.class));
        ArgumentCaptor<Scene> scenes = ArgumentCaptor.forClass(Scene.class);
        verify(sceneMapper, org.mockito.Mockito.times(2)).insert(scenes.capture());
        assertThat(scenes.getAllValues()).extracting(Scene::getSceneNumber)
                .containsExactly(1, 2);
        assertThat(scenes.getAllValues()).extracting(Scene::getId)
                .containsExactlyElementsOf(sceneIds);
    }

    private static SceneFull scene(String script) {
        return new SceneFull(
                null, "project-1", "conversation-1", 1,
                script, "image prompt", "video prompt", "", "推近",
                "中景", "", null, null, 3,
                null, null, "pending", "pending", null, null, null,
                null, null, null, null, null, null, null, null);
    }
}
