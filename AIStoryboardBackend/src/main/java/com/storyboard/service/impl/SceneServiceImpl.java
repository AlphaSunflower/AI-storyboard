package com.storyboard.service.impl;

import com.storyboard.dto.request.SceneRequest;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.service.SceneService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分镜服务实现。
 */
@Service
@RequiredArgsConstructor
public class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final SceneReferenceImageMapper refImageMapper;

    @Override
    public List<SceneResponse> listByProject(String projectId) {
        return sceneMapper.findByProjectIdOrdered(projectId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public SceneResponse addScene(String projectId, SceneRequest request) {
        int nextNum = sceneMapper.maxSceneNumber(projectId) + 1;
        Scene scene = new Scene();
        scene.setProjectId(projectId);
        scene.setSceneNumber(nextNum);
        scene.setScriptContent(request.scriptContent() == null ? "" : request.scriptContent());
        scene.setImagePrompt(request.imagePrompt() == null ? "" : request.imagePrompt());
        scene.setVideoPrompt(request.videoPrompt() == null ? "" : request.videoPrompt());
        scene.setCameraMovement(request.cameraMovement() == null ? "" : request.cameraMovement());
        scene.setShotType(request.shotType() == null ? "" : request.shotType());
        sceneMapper.insert(scene);
        return toResponse(scene);
    }

    @Override
    @Transactional
    public SceneResponse updateScene(String sceneId, SceneRequest request) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");

        // 仅更新非 null 字段（null = 不修改）
        if (request.scriptContent() != null) scene.setScriptContent(request.scriptContent());
        if (request.imagePrompt() != null) scene.setImagePrompt(request.imagePrompt());
        if (request.videoPrompt() != null) scene.setVideoPrompt(request.videoPrompt());
        if (request.negativePrompt() != null) scene.setNegativePrompt(request.negativePrompt());
        if (request.cameraMovement() != null) scene.setCameraMovement(request.cameraMovement());
        if (request.shotType() != null) scene.setShotType(request.shotType());
        if (request.soundDesign() != null) scene.setSoundDesign(request.soundDesign());
        if (request.aiModel() != null) scene.setAiModel(request.aiModel());
        if (request.videoResolution() != null) scene.setVideoResolution(request.videoResolution());
        if (request.duration() != null) scene.setDuration(request.duration());

        sceneMapper.updateById(scene);
        return toResponse(scene);
    }

    @Override
    @Transactional
    public void deleteScene(String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        sceneMapper.deleteById(sceneId);
    }

    private SceneResponse toResponse(Scene s) {
        return new SceneResponse(
            s.getId(), s.getProjectId(), s.getSceneNumber(),
            s.getScriptContent(), s.getImagePrompt(), s.getVideoPrompt(),
            s.getNegativePrompt(), s.getCameraMovement(), s.getShotType(),
            s.getSoundDesign(), s.getAiModel(), s.getVideoResolution(),
            s.getDuration(), s.getImageUrl(), s.getVideoUrl(),
            s.getImageStatus(), s.getVideoStatus(), s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
