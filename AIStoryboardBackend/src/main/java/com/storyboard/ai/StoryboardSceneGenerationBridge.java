package com.storyboard.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.SceneView;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主后端分镜场景桥：把 agent-core 的轻量 SceneView 映射到 scenes + scene_prompts。
 */
@Component
@RequiredArgsConstructor
public class StoryboardSceneGenerationBridge implements SceneGenerationBridge {

    private final SceneMapper sceneMapper;
    private final ScenePromptMapper scenePromptMapper;
    private final AssetService assetService;

    @Override
    public SceneView getScene(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) return null;
        Scene scene = sceneMapper.selectById(sceneId);
        return scene == null ? null : toView(scene, scenePromptMapper.selectById(sceneId));
    }

    @Override
    public SceneView getSceneByVideoTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        Scene scene = sceneMapper.selectOne(new LambdaQueryWrapper<Scene>()
                .eq(Scene::getVideoTaskId, taskId)
                .last("LIMIT 1"));
        return scene == null ? null : toView(scene, scenePromptMapper.selectById(scene.getId()));
    }

    @Override
    public void updateScene(SceneView view) {
        if (view == null || view.id == null || view.id.isBlank()) return;
        Scene scene = sceneMapper.selectById(view.id);
        if (scene == null) return;

        scene.setImageUrl(view.imageUrl);
        scene.setImageUrls(view.imageUrls);
        scene.setImageStatus(view.imageStatus);
        scene.setVideoTaskId(view.videoTaskId);
        scene.setVideoStatus(view.videoStatus);
        scene.setVideoUrl(view.videoUrl);
        sceneMapper.updateById(scene);

        ScenePrompt prompt = scenePromptMapper.selectById(view.id);
        if (prompt == null) return;
        prompt.setImageModel(view.imageModel);
        prompt.setImageSize(view.imageSize);
        prompt.setImageQuality(view.imageQuality);
        prompt.setImageN(view.imageN);
        prompt.setVideoModel(view.videoModel);
        prompt.setVideoResolution(view.videoResolution);
        prompt.setVideoAspectRatio(view.videoAspectRatio);
        prompt.setDuration(view.duration);
        scenePromptMapper.updateById(prompt);
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

    private static SceneView toView(Scene scene, ScenePrompt prompt) {
        SceneView view = new SceneView();
        view.id = scene.getId();
        view.imageUrl = scene.getImageUrl();
        view.imageUrls = scene.getImageUrls();
        view.imageStatus = scene.getImageStatus();
        view.videoTaskId = scene.getVideoTaskId();
        view.videoStatus = scene.getVideoStatus();
        view.videoUrl = scene.getVideoUrl();
        if (prompt != null) {
            view.imageModel = prompt.getImageModel();
            view.imageSize = prompt.getImageSize();
            view.imageQuality = prompt.getImageQuality();
            view.imageN = prompt.getImageN();
            view.videoModel = prompt.getVideoModel();
            view.videoResolution = prompt.getVideoResolution();
            view.videoAspectRatio = prompt.getVideoAspectRatio();
            view.duration = prompt.getDuration();
        }
        return view;
    }
}
