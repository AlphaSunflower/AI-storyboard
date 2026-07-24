package com.storyboard.service;

import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class SceneService {

    private final SceneMapper sceneMapper;
    private final SceneReferenceImageMapper refImageMapper;

    public SceneService(SceneMapper sceneMapper, SceneReferenceImageMapper refImageMapper) {
        this.sceneMapper = sceneMapper;
        this.refImageMapper = refImageMapper;
    }

    public List<SceneResponse> listByProject(String projectId) {
        return sceneMapper.findByProjectIdOrdered(projectId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public SceneResponse addScene(String projectId, Map<String, Object> data) {
        int nextNum = sceneMapper.maxSceneNumber(projectId) + 1;
        Scene scene = new Scene();
        scene.setProjectId(projectId);
        scene.setSceneNumber(nextNum);
        scene.setScriptContent((String) data.getOrDefault("scriptContent", ""));
        scene.setImagePrompt((String) data.getOrDefault("imagePrompt", ""));
        scene.setVideoPrompt((String) data.getOrDefault("videoPrompt", ""));
        scene.setCameraMovement((String) data.getOrDefault("cameraMovement", ""));
        scene.setShotType((String) data.getOrDefault("shotType", ""));
        sceneMapper.insert(scene);
        return toResponse(scene);
    }

    @Transactional
    public SceneResponse updateScene(String sceneId, Map<String, Object> data) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");

        if (data.containsKey("scriptContent")) scene.setScriptContent((String) data.get("scriptContent"));
        if (data.containsKey("imagePrompt")) scene.setImagePrompt((String) data.get("imagePrompt"));
        if (data.containsKey("videoPrompt")) scene.setVideoPrompt((String) data.get("videoPrompt"));
        if (data.containsKey("negativePrompt")) scene.setNegativePrompt((String) data.get("negativePrompt"));
        if (data.containsKey("cameraMovement")) scene.setCameraMovement((String) data.get("cameraMovement"));
        if (data.containsKey("shotType")) scene.setShotType((String) data.get("shotType"));
        if (data.containsKey("soundDesign")) scene.setSoundDesign((String) data.get("soundDesign"));
        if (data.containsKey("aiModel")) scene.setAiModel((String) data.get("aiModel"));
        if (data.containsKey("videoResolution")) scene.setVideoResolution((String) data.get("videoResolution"));
        if (data.containsKey("duration") && data.get("duration") != null) scene.setDuration((Integer) data.get("duration"));

        sceneMapper.updateById(scene);
        return toResponse(scene);
    }

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
