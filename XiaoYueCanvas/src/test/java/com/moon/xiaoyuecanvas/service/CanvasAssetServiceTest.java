package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.service.impl.CanvasAssetServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
        MultipartFile file = mock(MultipartFile.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas);
        when(fileStorageService.saveUploadedReference("image", file))
                .thenReturn("/api/files/images/source.png");

        CanvasAssetService service = new CanvasAssetServiceImpl(
                mapper, canvasService, agentAssetMapper, fileStorageService);
        CanvasAsset asset = service.upload(
                "user-1", "canvas-1", "node-1", "image", file);

        assertEquals("canvas-1", asset.getCanvasId());
        assertEquals("node-1", asset.getProducerNodeId());
        assertEquals("/api/files/images/source.png", asset.getObjectUrl());
        assertEquals("images/source.png", asset.getObjectKey());
        assertEquals("image/png", asset.getMimeType());
        assertEquals("ready", asset.getStatus());
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
                mapper, canvasService, agentAssetMapper, fileStorageService);
        List<CanvasAsset> linked = service.linkGeneration(
                "user-1", "canvas-1", "node-1", "generation-1");

        assertEquals(List.of(existing), linked);
        verify(mapper, never()).insert(any(CanvasAsset.class));
    }
}
