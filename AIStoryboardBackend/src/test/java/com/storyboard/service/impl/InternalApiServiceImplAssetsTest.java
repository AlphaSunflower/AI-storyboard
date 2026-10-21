package com.storyboard.service.impl;

import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.Project;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.service.AssetService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** getProjectAssets 已从 public.assets 切到 asset-service，避免归档后旧读取链路复活。 */
class InternalApiServiceImplAssetsTest {

    @Test
    void getProjectAssetsReadsAssetServiceWithProjectOwner() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        AssetService assetService = mock(AssetService.class);
        Project project = new Project();
        project.setId("project-1");
        project.setUserId("user-1");
        when(projectMapper.selectById("project-1")).thenReturn(project);
        AssetVO asset = new AssetVO(
                "asset-1", "character", "连凯文", "主角", "project-1", List.of(), null, null);
        when(assetService.listAgentAssets("user-1", "project-1")).thenReturn(List.of(asset));

        InternalApiServiceImpl service = new InternalApiServiceImpl(
                projectMapper,
                mock(SceneMapper.class),
                mock(ScenePromptMapper.class),
                mock(SceneReferenceImageMapper.class),
                mock(SceneAssetMapper.class),
                assetService);

        assertThat(service.getProjectAssets("project-1")).containsExactly(asset);
    }

    @Test
    void getProjectAssetsReturnsEmptyForUnknownProject() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(projectMapper.selectById("missing")).thenReturn(null);

        InternalApiServiceImpl service = new InternalApiServiceImpl(
                projectMapper,
                mock(SceneMapper.class),
                mock(ScenePromptMapper.class),
                mock(SceneReferenceImageMapper.class),
                mock(SceneAssetMapper.class),
                mock(AssetService.class));

        assertThat(service.getProjectAssets("missing")).isEmpty();
    }
}
