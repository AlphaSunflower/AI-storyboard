package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.service.impl.CanvasAssetServiceImpl;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetServiceException;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MetadataState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasAssetServiceTest {

    @Test
    void uploadWritesMinioUrlIntoCanvasAsset() {
        CanvasAssetMapper mapper = mock(CanvasAssetMapper.class);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        AgentAssetMapper agentAssetMapper = mock(AgentAssetMapper.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        AssetClient assetClient = mock(AssetClient.class);
        when(assetClient.findAssetBySource(eq("user-1"), eq("canvas-upload"), eq("canvas-1"), any()))
                .thenThrow(new AssetServiceException(40401, 404, "来源媒体不存在", "corr-1"));
        when(assetClient.registerExternalMedia(any(), any())).thenReturn(new AssetSummary(
                "library-1", "user-1", AssetScope.PROJECT, "project-1", AssetType.IMAGE, null,
                "source.png", null, null, false, MetadataState.NONE, AssetStatus.ACTIVE,
                "/api/files/images/source.png", 1L, List.of(), List.of(), null, List.of(),
                "canvas-upload", "canvas-1", OffsetDateTime.now(), OffsetDateTime.now()));
        MultipartFile file = mock(MultipartFile.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas);
        when(fileStorageService.saveUploadedReference("image", file))
                .thenReturn("/api/files/images/source.png");

        CanvasAssetService service = new CanvasAssetServiceImpl(
                mapper, canvasService, agentAssetMapper, fileStorageService, assetClient);
        CanvasAsset asset = service.upload(
                "user-1", "canvas-1", "node-1", "image", file);

        assertEquals("canvas-1", asset.getCanvasId());
        assertEquals("node-1", asset.getProducerNodeId());
        assertEquals("/api/files/images/source.png", asset.getObjectUrl());
        assertEquals("images/source.png", asset.getObjectKey());
        assertEquals("image/png", asset.getMimeType());
        assertEquals("ready", asset.getStatus());
        assertEquals("library-1", asset.getLibraryAssetId());
        assertEquals("ready", asset.getLibrarySyncState());
        ArgumentCaptor<CanvasAsset> captor = ArgumentCaptor.forClass(CanvasAsset.class);
        verify(mapper).insert(captor.capture());
        assertEquals(asset.getId(), captor.getValue().getId());
    }

    @Test
    void linkingSameGenerationResultReturnsExistingMapping() {
        CanvasAssetMapper mapper = mock(CanvasAssetMapper.class);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        AgentAssetMapper agentAssetMapper = mock(AgentAssetMapper.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        AssetClient assetClient = mock(AssetClient.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas);
        AgentAsset generated = new AgentAsset();
        generated.setId("generation-1");
        generated.setUserId("user-1");
        generated.setProjectId("project-1");
        generated.setType("video");
        generated.setStatus("completed");
        generated.setResultUrls("[\"/api/files/videos/generated.mp4\"]");
        when(agentAssetMapper.selectById("generation-1")).thenReturn(generated);
        CanvasAsset existing = new CanvasAsset();
        existing.setId("asset-existing");
        when(mapper.selectOne(any())).thenReturn(existing);

        CanvasAssetService service = new CanvasAssetServiceImpl(
                mapper, canvasService, agentAssetMapper, fileStorageService, assetClient);
        List<CanvasAsset> linked = service.linkGeneration(
                "user-1", "canvas-1", "node-1", "generation-1");

        assertEquals(List.of(existing), linked);
        verify(mapper, never()).insert(any(CanvasAsset.class));
    }
}
