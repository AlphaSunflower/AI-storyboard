package com.storyboard.service.impl;

import com.storyboard.common.BusinessException;
import com.storyboard.dto.request.SceneMaterialReferenceRequest;
import com.storyboard.dto.request.SceneAssetGroupBindingRequest;
import com.storyboard.dto.request.SceneMaterialsUpdateRequest;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.mapper.SceneMaterialGroupSnapshotMapper;
import com.storyboard.service.AssetService;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.SnapshotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.io.Serializable;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SceneMaterialServiceImplTest {

    private SceneMapper sceneMapper;
    private ProjectMapper projectMapper;
    private AssetService assetService;
    private SceneReferenceImageMapper referenceMapper;
    private AgentAssetMapper agentAssetMapper;
    private SceneMaterialGroupSnapshotMapper groupSnapshotMapper;
    private AssetClient assetClient;
    private SceneMaterialServiceImpl service;

    @BeforeEach
    void setUp() {
        sceneMapper = mock(SceneMapper.class);
        projectMapper = mock(ProjectMapper.class);
        assetService = mock(AssetService.class);
        referenceMapper = mock(SceneReferenceImageMapper.class);
        agentAssetMapper = mock(AgentAssetMapper.class);
        groupSnapshotMapper = mock(SceneMaterialGroupSnapshotMapper.class);
        assetClient = mock(AssetClient.class);
        service = new SceneMaterialServiceImpl(
                sceneMapper, projectMapper, assetService, referenceMapper, agentAssetMapper,
                groupSnapshotMapper, assetClient);

        Scene scene = new Scene();
        scene.setId("s1");
        scene.setProjectId("p1");
        scene.setImageUrl("/api/files/images/scene-a.png");
        scene.setImageUrls("/api/files/images/scene-a.png,/api/files/images/scene-b.png");
        when(sceneMapper.selectById("s1")).thenReturn(scene);

        Project project = new Project();
        project.setId("p1");
        project.setUserId("u1");
        when(projectMapper.selectById("p1")).thenReturn(project);
    }

    @Test
    void replaceMaterials_replacesAssetsAndLinkedReferences_butKeepsUploads() {
        SceneReferenceImage upload = ref("r-upload", "upload", "upload-1", null, "/api/files/images/user.png");
        SceneReferenceImage oldOutput = ref("r-output", "output", "out-old", "c1", "/api/files/images/old.png");
        SceneReferenceImage oldScene = ref("r-scene", "scene_image", "s1", "c1", "/api/files/images/old-scene.png");
        when(referenceMapper.findBySceneId("s1")).thenReturn(List.of(upload, oldOutput, oldScene));

        AgentAsset outputRecord = new AgentAsset();
        outputRecord.setId("out-1");
        outputRecord.setConversationId("c1");
        outputRecord.setType("image");
        outputRecord.setStatus("completed");
        outputRecord.setUrl("/api/files/images/output.png");
        outputRecord.setResultUrls("[\"/api/files/images/output.png\"]");
        when(agentAssetMapper.findOwnedOutputRecord("u1", "p1", "out-1")).thenReturn(outputRecord);

        SceneMaterialsUpdateRequest request = new SceneMaterialsUpdateRequest(
                List.of("asset-1", "asset-2"),
                List.of(
                        new SceneMaterialReferenceRequest(
                                "image", "image", "/api/files/images/output.png", "REFERENCE",
                                "output", "out-1", "c1"),
                        new SceneMaterialReferenceRequest(
                                "image", "image", "/api/files/images/scene-b.png", "REFERENCE",
                        "scene_image", "s1", "c1")),
                List.of());

        service.replaceMaterials("u1", "s1", request);

        verify(assetService).setSceneAssets("u1", "s1", List.of("asset-1", "asset-2"), List.of("asset-1", "asset-2"));
        verify(referenceMapper, never()).deleteById((Serializable) "r-upload");
        verify(referenceMapper).deleteById((Serializable) "r-output");
        verify(referenceMapper).deleteById((Serializable) "r-scene");

        ArgumentCaptor<SceneReferenceImage> inserted = ArgumentCaptor.forClass(SceneReferenceImage.class);
        verify(referenceMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        assertEquals(List.of("output", "scene_image"),
                inserted.getAllValues().stream().map(SceneReferenceImage::getSourceType).toList());
        assertEquals(List.of("out-1", "s1"),
                inserted.getAllValues().stream().map(SceneReferenceImage::getSourceId).toList());
    }

    @Test
    void replaceMaterials_rejectsOutputFromAnotherProject_beforeDeletingExistingSelection() {
        AgentAsset foreignOutput = new AgentAsset();
        foreignOutput.setId("out-foreign");
        foreignOutput.setConversationId("c-foreign");
        foreignOutput.setType("image");
        foreignOutput.setStatus("completed");
        foreignOutput.setUrl("/api/files/images/foreign.png");
        foreignOutput.setResultUrls("[\"/api/files/images/foreign.png\"]");
        when(agentAssetMapper.findOwnedOutputRecord("u1", "p1", "out-foreign")).thenReturn(null);

        SceneMaterialsUpdateRequest request = new SceneMaterialsUpdateRequest(
                List.of(),
                List.of(new SceneMaterialReferenceRequest(
                        "image", "image", "/api/files/images/foreign.png", "REFERENCE",
                        "output", "out-foreign", "c-foreign")),
                List.of());

        assertThrows(BusinessException.class, () -> service.replaceMaterials("u1", "s1", request));
        verify(referenceMapper, never()).deleteById(org.mockito.ArgumentMatchers.<Serializable>any());
        verify(assetService, never()).setSceneAssets(any(), any(), any(), any());
    }

    @Test
    void replaceMaterials_rejectsMoreThanOneVideoReference_beforeDeletingExistingSelection() {
        SceneMaterialsUpdateRequest request = new SceneMaterialsUpdateRequest(
                List.of(),
                List.of(
                        new SceneMaterialReferenceRequest(
                                "video", "video", "/api/files/videos/a.mp4", "REFERENCE",
                                "output", "out-a", "c1"),
                        new SceneMaterialReferenceRequest(
                                "video", "video", "/api/files/videos/b.mp4", "REFERENCE",
                                "output", "out-b", "c1")),
                List.of());

        BusinessException error = assertThrows(
                BusinessException.class, () -> service.replaceMaterials("u1", "s1", request));

        assertEquals("每个分镜最多选择 1 个视频参考", error.getMessage());
        verify(referenceMapper, never()).deleteById(org.mockito.ArgumentMatchers.<Serializable>any());
        verify(assetService, never()).setSceneAssets(any(), any(), any(), any());
    }

    @Test
    void linkReferences_appendsOutputImage_withoutReplacingUploadsOrAssets() {
        SceneReferenceImage upload = ref("r-upload", "upload", "upload-1", null, "/api/files/images/user.png");
        when(referenceMapper.findBySceneId("s1")).thenReturn(List.of(upload));

        AgentAsset outputRecord = new AgentAsset();
        outputRecord.setId("out-1");
        outputRecord.setConversationId("c1");
        outputRecord.setType("image");
        outputRecord.setStatus("completed");
        outputRecord.setUrl("/api/files/images/output.png");
        outputRecord.setResultUrls("[\"/api/files/images/output.png\"]");
        when(agentAssetMapper.findOwnedOutputRecord("u1", "p1", "out-1")).thenReturn(outputRecord);

        List<com.storyboard.dto.response.SceneReferenceResponse> result = service.linkReferences(
                "u1", "s1",
                List.of(new SceneMaterialReferenceRequest(
                        "image", "image", "/api/files/images/output.png", "REFERENCE",
                        "output", "out-1", "c1")));

        verify(assetService, never()).setSceneAssets(any(), any(), any(), any());
        verify(referenceMapper, never()).deleteById(any(Serializable.class));
        ArgumentCaptor<SceneReferenceImage> inserted = ArgumentCaptor.forClass(SceneReferenceImage.class);
        verify(referenceMapper).insert(inserted.capture());
        assertEquals("output", inserted.getValue().getSourceType());
        assertEquals("out-1", inserted.getValue().getSourceId());
        assertEquals("c1", inserted.getValue().getSourceConversationId());
        assertEquals("/api/files/images/output.png", result.get(0).url());
    }

    @Test
    void linkReferences_skipsDuplicateExistingUrl() {
        SceneReferenceImage existing = ref(
                "r-output", "output", "out-1", "c1", "/api/files/images/output.png");
        when(referenceMapper.findBySceneId("s1")).thenReturn(List.of(existing));

        AgentAsset outputRecord = new AgentAsset();
        outputRecord.setId("out-1");
        outputRecord.setConversationId("c1");
        outputRecord.setType("image");
        outputRecord.setStatus("completed");
        outputRecord.setUrl("/api/files/images/output.png");
        outputRecord.setResultUrls("[\"/api/files/images/output.png\"]");
        when(agentAssetMapper.findOwnedOutputRecord("u1", "p1", "out-1")).thenReturn(outputRecord);

        List<com.storyboard.dto.response.SceneReferenceResponse> result = service.linkReferences(
                "u1", "s1",
                List.of(new SceneMaterialReferenceRequest(
                        "image", "image", "/api/files/images/output.png", "REFERENCE",
                        "output", "out-1", "c1")));

        verify(referenceMapper, never()).insert(any(SceneReferenceImage.class));
        assertEquals("r-output", result.get(0).id());
    }

    @Test
    void linkReferences_allowsSameOutputUrlForDifferentPurpose() {
        SceneReferenceImage videoReference = ref(
                "r-video", "output", "out-1", "c1", "/api/files/images/output.png");
        videoReference.setPurpose("video");
        when(referenceMapper.findBySceneId("s1")).thenReturn(List.of(videoReference));

        AgentAsset outputRecord = new AgentAsset();
        outputRecord.setId("out-1");
        outputRecord.setConversationId("c1");
        outputRecord.setType("image");
        outputRecord.setStatus("completed");
        outputRecord.setUrl("/api/files/images/output.png");
        outputRecord.setResultUrls("[\"/api/files/images/output.png\"]");
        when(agentAssetMapper.findOwnedOutputRecord("u1", "p1", "out-1")).thenReturn(outputRecord);

        List<com.storyboard.dto.response.SceneReferenceResponse> result = service.linkReferences(
                "u1", "s1",
                List.of(new SceneMaterialReferenceRequest(
                        "image", "image", "/api/files/images/output.png", "REFERENCE",
                        "output", "out-1", "c1")));

        ArgumentCaptor<SceneReferenceImage> inserted = ArgumentCaptor.forClass(SceneReferenceImage.class);
        verify(referenceMapper).insert(inserted.capture());
        assertEquals("image", inserted.getValue().getPurpose());
        assertEquals("image", result.get(0).purpose());
    }

    @Test
    void bindAssetGroup_freezesImageSnapshotAndReturnsBinding() {
        when(assetClient.createSnapshot(eq("group-1"), any(), any())).thenReturn(
                new AssetGroupSnapshot(
                        "snapshot-1", "group-1", 2L, "hash", "IMAGE",
                        List.of(new SnapshotItem(
                                "asset-1", "media-1", "blob-1", "images/a.png",
                                "/api/library/media/media-1/content", MediaType.IMAGE,
                                MediaRole.REFERENCE, 0)),
                        OffsetDateTime.now()));

        var result = service.bindAssetGroup(
                "u1", "s1", new SceneAssetGroupBindingRequest("group-1", "image"));

        assertEquals("snapshot-1", result.snapshotId());
        assertEquals("image", result.purpose());
        assertEquals(1, result.media().size());
        verify(groupSnapshotMapper).deleteBinding("s1", "group-1", "image");
        verify(groupSnapshotMapper).insert(
                eq("s1"), eq("snapshot-1"), eq("group-1"), eq(2L), eq("hash"),
                any(String.class), eq("image"));
    }

    private static SceneReferenceImage ref(String id, String sourceType, String sourceId,
                                           String sourceConversationId, String url) {
        SceneReferenceImage ref = new SceneReferenceImage();
        ref.setId(id);
        ref.setSceneId("s1");
        ref.setImageUrl(url);
        ref.setType("image");
        ref.setPurpose("image");
        ref.setSourceType(sourceType);
        ref.setSourceId(sourceId);
        ref.setSourceConversationId(sourceConversationId);
        return ref;
    }
}
