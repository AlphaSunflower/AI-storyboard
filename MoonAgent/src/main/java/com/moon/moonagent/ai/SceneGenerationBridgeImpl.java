package com.moon.moonagent.ai;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分镜场景回写桥实现（MoonAgent 侧）：{@link SceneView} ↔ 分镜 {@link Scene} 双向映射。
 * agent-core 生成服务只依赖 {@link SceneGenerationBridge} 接口；本实现在 MoonAgent 装配
 * 并提供 StoryboardClient/AssetService 的具体读写，使分镜路径（sceneId != null）保持原行为。
 * 画布（XiaoYueCanvas）不装配本实现（无 StoryboardClient/AssetService），生成服务走纯生成路径，零影响。
 */
@Service
@RequiredArgsConstructor
public class SceneGenerationBridgeImpl implements SceneGenerationBridge {

    private final StoryboardClient storyboardClient;
    private final AssetService assetService;

    @Override
    public SceneView getScene(String sceneId) {
        Scene scene = storyboardClient.getScene(sceneId);
        return scene == null ? null : toView(scene);
    }

    @Override
    public SceneView getSceneByVideoTaskId(String taskId) {
        Scene scene = storyboardClient.getSceneByVideoTaskId(taskId);
        return scene == null ? null : toView(scene);
    }

    @Override
    public void updateScene(SceneView view) {
        if (view == null || view.id == null) return;
        Scene scene = storyboardClient.getScene(view.id);
        if (scene == null) return;
        scene.setImageModel(view.imageModel);
        scene.setImageSize(view.imageSize);
        scene.setImageQuality(view.imageQuality);
        scene.setImageN(view.imageN);
        scene.setImageUrl(view.imageUrl);
        scene.setImageUrls(view.imageUrls);
        scene.setImageStatus(view.imageStatus);
        scene.setVideoModel(view.videoModel);
        scene.setVideoResolution(view.videoResolution);
        scene.setVideoAspectRatio(view.videoAspectRatio);
        scene.setDuration(view.duration);
        scene.setVideoTaskId(view.videoTaskId);
        scene.setVideoStatus(view.videoStatus);
        scene.setVideoUrl(view.videoUrl);
        storyboardClient.updateScene(scene);
    }

    @Override
    public String sceneAssetSheet(String sceneId, String type) {
        List<AssetVO> assets = assetService.sceneAssets(sceneId, type);
        if (assets == null || assets.isEmpty()) return "";
        String sheet = assetService.buildSheetText(assets);
        return sheet == null ? "" : sheet;
    }

    @Override
    public List<String> sceneReferenceImages(String sceneId, String type) {
        List<AssetVO> assets = assetService.sceneAssets(sceneId, type);
        if (assets == null || assets.isEmpty()) return List.of();
        return assetService.buildReferenceImages(assets);
    }

    private SceneView toView(Scene scene) {
        SceneView v = new SceneView();
        v.id = scene.getId();
        v.imageModel = scene.getImageModel();
        v.imageSize = scene.getImageSize();
        v.imageQuality = scene.getImageQuality();
        v.imageN = scene.getImageN();
        v.imageUrl = scene.getImageUrl();
        v.imageUrls = scene.getImageUrls();
        v.imageStatus = scene.getImageStatus();
        v.videoModel = scene.getVideoModel();
        v.videoResolution = scene.getVideoResolution();
        v.videoAspectRatio = scene.getVideoAspectRatio();
        v.duration = scene.getDuration();
        v.videoTaskId = scene.getVideoTaskId();
        v.videoStatus = scene.getVideoStatus();
        v.videoUrl = scene.getVideoUrl();
        return v;
    }
}
