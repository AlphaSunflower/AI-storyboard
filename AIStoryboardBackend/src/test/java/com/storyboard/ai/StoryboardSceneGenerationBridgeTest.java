package com.storyboard.ai;

import com.moon.moonagent.ai.SceneView;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.service.AssetService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardSceneGenerationBridgeTest {

    @Test
    void readsAndWritesSplitSceneModel() {
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper promptMapper = mock(ScenePromptMapper.class);
        AssetService assetService = mock(AssetService.class);
        StoryboardSceneGenerationBridge bridge =
                new StoryboardSceneGenerationBridge(sceneMapper, promptMapper, assetService);

        Scene scene = new Scene();
        scene.setId("scene-1");
        scene.setImageUrl("/api/files/images/a.png");
        scene.setImageStatus("generating");
        ScenePrompt prompt = new ScenePrompt();
        prompt.setSceneId("scene-1");
        prompt.setImageModel("gpt-image-2");
        prompt.setImageN(2);
        when(sceneMapper.selectById("scene-1")).thenReturn(scene);
        when(promptMapper.selectById("scene-1")).thenReturn(prompt);

        SceneView first = bridge.getScene("scene-1");
        assertEquals("generating", scene.getImageStatus());
        first.imageStatus = "completed";
        first.imageUrl = "/api/files/images/b.png";
        first.imageModel = "model-x";
        bridge.updateScene(first);

        verify(sceneMapper).updateById(scene);
        verify(promptMapper).updateById(prompt);
        assertEquals("completed", scene.getImageStatus());
        assertEquals("model-x", prompt.getImageModel());
    }

    @Test
    void delegatesAssetInjection() {
        SceneMapper sceneMapper = mock(SceneMapper.class);
        ScenePromptMapper promptMapper = mock(ScenePromptMapper.class);
        AssetService assetService = mock(AssetService.class);
        StoryboardSceneGenerationBridge bridge =
                new StoryboardSceneGenerationBridge(sceneMapper, promptMapper, assetService);
        AssetVO asset = new AssetVO("a1", "character", "角色", null, "p1", List.of(), null, null);
        when(assetService.sceneAssets("scene-1", "image")).thenReturn(List.of(asset));
        when(assetService.buildSheetText(List.of(asset))).thenReturn("sheet");
        when(assetService.buildReferenceImages(List.of(asset))).thenReturn(List.of("/api/files/images/a.png"));

        assertEquals("sheet", bridge.sceneAssetSheet("scene-1", "image"));
        assertEquals(List.of("/api/files/images/a.png"),
                bridge.sceneReferenceImages("scene-1", "image"));
    }
}
