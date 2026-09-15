package com.moon.moonagent.service.impl;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.StoryboardImageEnqueueRequest;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.service.AssetService;
import com.moon.moonagent.service.StoryboardImageQueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryboardAutoImageServiceImplTest {

    @Mock
    private StoryboardClient storyboardClient;
    @Mock
    private AssetService assetService;
    @Mock
    private StoryboardImageQueueService imageQueueService;

    @InjectMocks
    private StoryboardAutoImageServiceImpl service;

    @Test
    void enqueueMissingMaterialImages_onlyQueuesEligibleScenesAsGenerate() {
        Scene eligible = scene("scene-1", 1, null, "一只猫看向窗外");
        Scene existingImage = scene("scene-2", 2, "/api/files/generated.png", "已有图片");
        Scene withReference = scene("scene-3", 3, null, "带参考图");
        Scene withAsset = scene("scene-4", 4, null, "带资产");

        when(storyboardClient.getProjectScenes("project-1"))
                .thenReturn(List.of(eligible, existingImage, withReference, withAsset));
        when(storyboardClient.getSceneReferences("scene-1")).thenReturn(List.of());
        when(assetService.sceneAssets("scene-1", null)).thenReturn(List.of());
        when(storyboardClient.getSceneReferences("scene-3"))
                .thenReturn(List.of(Map.of("type", "image", "url", "/api/files/reference.png")));
        when(assetService.sceneAssets("scene-4", null))
                .thenReturn(List.of(new AssetVO(
                        "asset-1", "character", "主角", null, "project-1",
                        List.of(), null, null)));

        int queued = service.enqueueMissingMaterialImages(
                "conversation-1", "project-1", List.of(1, 2, 3, 4));

        assertEquals(1, queued);
        ArgumentCaptor<StoryboardImageEnqueueRequest> captor =
                ArgumentCaptor.forClass(StoryboardImageEnqueueRequest.class);
        verify(imageQueueService).enqueue(captor.capture());
        StoryboardImageEnqueueRequest request = captor.getValue();
        assertEquals("conversation-1", request.conversationId());
        assertEquals("project-1", request.projectId());
        assertEquals(1, request.items().size());
        assertEquals("scene-1", request.items().getFirst().sceneId());
        assertEquals("一只猫看向窗外", request.items().getFirst().prompt());
        assertEquals("generate", request.items().getFirst().mode());
        assertNull(request.items().getFirst().sourceImageUrl());
        assertEquals(List.of(), request.items().getFirst().refImageUrls());
    }

    @Test
    void enqueueMissingMaterialImages_noEligibleScenes_doesNotEnqueue() {
        when(storyboardClient.getProjectScenes("project-1"))
                .thenReturn(List.of(scene("scene-1", 1, "/api/files/generated.png", "已有图片")));

        int queued = service.enqueueMissingMaterialImages(
                "conversation-1", "project-1", List.of(1));

        assertEquals(0, queued);
        verify(imageQueueService, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enqueueMissingMaterialImages_materialLookupFails_skipsScene() {
        Scene scene = scene("scene-1", 1, null, "无法确认素材时保守跳过");
        when(storyboardClient.getProjectScenes("project-1")).thenReturn(List.of(scene));
        when(storyboardClient.getSceneReferences("scene-1"))
                .thenThrow(new RuntimeException("backend unavailable"));

        int queued = service.enqueueMissingMaterialImages(
                "conversation-1", "project-1", List.of(1));

        assertEquals(0, queued);
        verify(imageQueueService, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    private static Scene scene(String id, int number, String imageUrl, String imagePrompt) {
        Scene scene = new Scene();
        scene.setId(id);
        scene.setProjectId("project-1");
        scene.setSceneNumber(number);
        scene.setImageUrl(imageUrl);
        scene.setImagePrompt(imagePrompt);
        scene.setScriptContent(imagePrompt);
        return scene;
    }
}
