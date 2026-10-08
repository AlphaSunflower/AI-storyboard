package com.storyboard.service.impl;

import com.storyboard.dto.response.ProjectResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.security.FileUrlSigner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectServiceImplMediaUrlTest {

    @Test
    void projectResponseReSignsLegacySceneMediaUrls() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper scenePromptMapper = mock(ScenePromptMapper.class);
        FileUrlSigner signer = new FileUrlSigner("test-file-signing-secret", 900);
        ProjectServiceImpl service = new ProjectServiceImpl(
                projectMapper, sceneMapper, scenePromptMapper, signer);

        Project project = new Project();
        project.setId("p1");
        project.setUserId("u1");
        project.setName("项目");
        project.setStatus("active");
        when(projectMapper.selectById("p1")).thenReturn(project);

        Scene scene = new Scene();
        scene.setId("s1");
        scene.setProjectId("p1");
        scene.setSceneNumber(1);
        scene.setImageUrl("/api/files/images/legacy.png");
        scene.setImageUrls("/api/files/images/legacy.png,/api/files/images/legacy-2.png");
        scene.setVideoUrl("/api/files/videos/legacy.mp4");
        when(sceneMapper.findByProjectIdOrdered("p1")).thenReturn(List.of(scene));
        when(scenePromptMapper.selectBatchIds(List.of("s1"))).thenReturn(List.of());

        ProjectResponse response = service.getById("u1", "p1");
        SceneResponse sceneResponse = response.scenes().getFirst();

        assertThat(sceneResponse.imageUrl()).startsWith("/api/files/images/legacy.png?token=");
        assertThat(sceneResponse.videoUrl()).startsWith("/api/files/videos/legacy.mp4?token=");
        assertThat(sceneResponse.imageUrls())
                .startsWith("/api/files/images/legacy.png?token=")
                .contains(",/api/files/images/legacy-2.png?token=");
    }
}
