package com.storyboard.service.impl;

import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.common.BusinessException;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.security.FileUrlSigner;
import com.storyboard.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SceneServiceImplReferenceDeletionTest {

    private SceneMapper sceneMapper;
    private ProjectMapper projectMapper;
    private SceneReferenceImageMapper referenceMapper;
    private FileStorageService fileStorageService;
    private SceneServiceImpl service;

    @BeforeEach
    void setUp() {
        sceneMapper = mock(SceneMapper.class);
        projectMapper = mock(ProjectMapper.class);
        referenceMapper = mock(SceneReferenceImageMapper.class);
        fileStorageService = mock(FileStorageService.class);
        service = new SceneServiceImpl(
                sceneMapper, referenceMapper, mock(ScenePromptMapper.class), fileStorageService, projectMapper,
                mock(FileUrlSigner.class));

        Scene scene = new Scene();
        scene.setId("s1");
        scene.setProjectId("p1");
        when(sceneMapper.selectById("s1")).thenReturn(scene);
        Project project = new Project();
        project.setId("p1");
        project.setUserId("u1");
        when(projectMapper.selectById("p1")).thenReturn(project);
    }

    @Test
    void deleteLinkedOutputReference_unlinksWithoutDeletingOriginalFile() {
        SceneReferenceImage ref = reference("r1", "output");
        when(referenceMapper.selectById("r1")).thenReturn(ref);

        service.deleteReference("u1", "r1");

        verify(referenceMapper).deleteById((Serializable) "r1");
        verify(fileStorageService, never()).deleteImage(org.mockito.ArgumentMatchers.anyString());
        verify(fileStorageService, never()).deleteVideo(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deleteUploadedReference_keepsExistingFileCleanup() {
        SceneReferenceImage ref = reference("r2", "upload");
        ref.setImageUrl("/api/files/images/upload.png");
        when(referenceMapper.selectById("r2")).thenReturn(ref);

        service.deleteReference("u1", "r2");

        verify(referenceMapper).deleteById((Serializable) "r2");
        verify(fileStorageService).deleteImage("upload.png");
    }

    @Test
    void uploadSecondReferenceVideo_isRejected() {
        SceneReferenceImage existing = new SceneReferenceImage();
        existing.setSceneId("s1");
        existing.setType("video");
        when(referenceMapper.selectCount(any())).thenReturn(1L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "ref.mp4", "video/mp4", new byte[]{1, 2, 3});

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.uploadReference("u1", "s1", "video", "video", file));

        assertEquals("参考视频数量已达上限 1", error.getMessage());
        verify(fileStorageService, never()).saveUploadedReference(any(), any());
    }

    private static SceneReferenceImage reference(String id, String sourceType) {
        SceneReferenceImage ref = new SceneReferenceImage();
        ref.setId(id);
        ref.setSceneId("s1");
        ref.setType("image");
        ref.setImageUrl("/api/files/images/output.png");
        ref.setSourceType(sourceType);
        return ref;
    }
}
