package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.SceneRequest;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.common.BusinessException;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.SceneService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 分镜服务实现。
 */
@Service
@RequiredArgsConstructor
public class SceneServiceImpl implements SceneService {

    private static final Logger log = LoggerFactory.getLogger(SceneServiceImpl.class);

    private final SceneMapper sceneMapper;
    private final SceneReferenceImageMapper refImageMapper;
    private final ScenePromptMapper scenePromptMapper;
    private final FileStorageService fileStorageService;
    private final ProjectMapper projectMapper;

    /** 参考素材上限兜底（对齐 MiniMax 输入约束；网关 params 精确值由前端展示，后端硬上限防滥用） */
    private static final Map<String, int[]> REF_LIMITS = Map.of(
        "image", new int[] { 10, 30 * 1024 * 1024 },   // 数量, 单文件字节
        "video", new int[] { 1, 50 * 1024 * 1024 },
        "audio", new int[] { 3, 15 * 1024 * 1024 });

    @Override
    public List<SceneResponse> listByProject(String projectId) {
        List<Scene> scenes = sceneMapper.findByProjectIdOrdered(projectId);
        Map<String, ScenePrompt> prompts = loadPrompts(scenes);
        return scenes.stream()
            .map(s -> toResponse(s, prompts.get(s.getId())))
            .toList();
    }

    @Override
    @Transactional
    public SceneResponse addScene(String userId, String projectId, SceneRequest request) {
        checkProjectOwnership(userId, projectId);
        int nextNum = sceneMapper.maxSceneNumber(projectId) + 1;
        Scene scene = new Scene();
        scene.setProjectId(projectId);
        scene.setSceneNumber(nextNum);
        sceneMapper.insert(scene);
        ScenePrompt prompt = new ScenePrompt();
        prompt.setSceneId(scene.getId());
        prompt.setScriptContent(request.scriptContent() == null ? "" : request.scriptContent());
        prompt.setImagePrompt(request.imagePrompt() == null ? "" : request.imagePrompt());
        prompt.setVideoPrompt(request.videoPrompt() == null ? "" : request.videoPrompt());
        prompt.setCameraMovement(request.cameraMovement() == null ? "" : request.cameraMovement());
        prompt.setShotType(request.shotType() == null ? "" : request.shotType());
        scenePromptMapper.insert(prompt);
        return toResponse(scene, prompt);
    }

    @Override
    @Transactional
    public SceneResponse updateScene(String userId, String sceneId, SceneRequest request) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        checkProjectOwnership(userId, scene.getProjectId());

        // 仅更新非 null 字段（null = 不修改）
        sceneMapper.updateById(scene);
        ScenePrompt prompt = scenePromptMapper.selectById(sceneId);
        boolean hadPrompt = prompt != null;
        if (prompt == null) {
            prompt = new ScenePrompt();
            prompt.setSceneId(sceneId);
        }
        boolean touched = false;
        if (request.scriptContent() != null) { prompt.setScriptContent(request.scriptContent()); touched = true; }
        if (request.imagePrompt() != null) { prompt.setImagePrompt(request.imagePrompt()); touched = true; }
        if (request.videoPrompt() != null) { prompt.setVideoPrompt(request.videoPrompt()); touched = true; }
        if (request.negativePrompt() != null) { prompt.setNegativePrompt(request.negativePrompt()); touched = true; }
        if (request.cameraMovement() != null) { prompt.setCameraMovement(request.cameraMovement()); touched = true; }
        if (request.shotType() != null) { prompt.setShotType(request.shotType()); touched = true; }
        if (request.soundDesign() != null) { prompt.setSoundDesign(request.soundDesign()); touched = true; }
        if (request.aiModel() != null) { prompt.setAiModel(request.aiModel()); touched = true; }
        if (request.videoResolution() != null) { prompt.setVideoResolution(request.videoResolution()); touched = true; }
        if (request.duration() != null) { prompt.setDuration(request.duration()); touched = true; }
        if (request.imageModel() != null) { prompt.setImageModel(request.imageModel()); touched = true; }
        if (request.imageSize() != null) { prompt.setImageSize(request.imageSize()); touched = true; }
        if (request.imageQuality() != null) { prompt.setImageQuality(request.imageQuality()); touched = true; }
        if (request.imageN() != null) { prompt.setImageN(request.imageN()); touched = true; }
        if (request.videoModel() != null) { prompt.setVideoModel(request.videoModel()); touched = true; }
        if (request.videoAspectRatio() != null) { prompt.setVideoAspectRatio(request.videoAspectRatio()); touched = true; }
        if (touched) {
            if (hadPrompt) {
                scenePromptMapper.updateById(prompt);
            } else {
                scenePromptMapper.insert(prompt);
            }
        }
        return toResponse(scene, prompt);
    }

    @Override
    @Transactional
    public void deleteScene(String userId, String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        checkProjectOwnership(userId, scene.getProjectId());
        sceneMapper.deleteById(sceneId);
    }

    @Override
    @Transactional
    public void reorderScenes(String userId, String projectId, List<String> sceneIds) {
        checkProjectOwnership(userId, projectId);
        // 校验：sceneIds 不能为空
        if (sceneIds == null || sceneIds.isEmpty()) {
            throw new BusinessException(40001, "排序列表不能为空");
        }
        // 校验：sceneIds 必须属于该项目
        List<Scene> existing = sceneMapper.findByProjectIdOrdered(projectId);
        if (existing.size() != sceneIds.size()) {
            throw new BusinessException(40001, "排序列表数量与项目分镜数量不一致");
        }
        java.util.Set<String> existingIds = existing.stream().map(Scene::getId).collect(java.util.stream.Collectors.toSet());
        for (String id : sceneIds) {
            if (!existingIds.contains(id)) {
                throw new BusinessException(40001, "分镜 " + id + " 不属于该项目");
            }
        }
        // 批量更新 scene_number（1-based）
        for (int i = 0; i < sceneIds.size(); i++) {
            sceneMapper.updateSceneNumber(sceneIds.get(i), i + 1);
        }
    }

    @Override
    public List<SceneReferenceResponse> listReferences(String userId, String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        checkProjectOwnership(userId, scene.getProjectId());
        return refImageMapper.findBySceneId(sceneId).stream()
            .map(r -> new SceneReferenceResponse(r.getId(), r.getType(), r.getPurpose(), r.getImageUrl(),
                    r.getRole(), r.getSourceType(), r.getSourceId(), r.getSourceConversationId(),
                    r.getFileName(), r.getFileSize()))
            .toList();
    }

    @Override
    @Transactional
    public SceneReferenceResponse uploadReference(String userId, String sceneId, String type, String purpose, MultipartFile file) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        checkProjectOwnership(userId, scene.getProjectId());
        String t = type == null ? "image" : type;
        String p = purpose == null ? "image" : purpose;
        if (!REF_LIMITS.containsKey(t)) throw new BusinessException(40001, "不支持的素材类型: " + t);

        int[] limits = REF_LIMITS.get(t);
        long existing = refImageMapper.selectCount(new LambdaQueryWrapper<SceneReferenceImage>()
                .eq(SceneReferenceImage::getSceneId, sceneId)
                .eq(SceneReferenceImage::getType, t));
        String typeName = switch (t) {
            case "video" -> "视频";
            case "audio" -> "音频";
            default -> "图";
        };
        if (existing >= limits[0]) {
            throw new BusinessException(40001, "参考" + typeName + "数量已达上限 " + limits[0]);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "上传文件为空");
        }
        if (file.getSize() > limits[1]) {
            throw new BusinessException(40001, "文件超过大小限制 " + (limits[1] / 1024 / 1024) + "MB");
        }
        String url = fileStorageService.saveUploadedReference(t, file);
        SceneReferenceImage ref = new SceneReferenceImage();
        ref.setSceneId(sceneId);
        ref.setType(t);
        ref.setPurpose(p);
        ref.setRole("REFERENCE");
        ref.setSourceType("upload");
        ref.setImageUrl(url);
        ref.setFileName(file.getOriginalFilename());
        ref.setFileSize(file.getSize());
        ref.setSortOrder((int) existing);
        refImageMapper.insert(ref);
        return new SceneReferenceResponse(ref.getId(), t, p, url, "REFERENCE",
                "upload", null, null, ref.getFileName(), ref.getFileSize());
    }

    @Override
    public void deleteReference(String userId, String referenceId) {
        SceneReferenceImage ref = refImageMapper.selectById(referenceId);
        if (ref == null) throw new BusinessException(40401, "素材不存在");
        // 归属校验：通过 ref → scene → project → userId
        Scene scene = sceneMapper.selectById(ref.getSceneId());
        if (scene == null) throw new BusinessException(40401, "素材所属分镜不存在");
        checkProjectOwnership(userId, scene.getProjectId());
        // 仅用户直接上传的素材删除文件；产出素材/分镜图片只解除引用，不能删除原生成文件。
        if ("upload".equals(ref.getSourceType())) {
            try {
                String url = ref.getImageUrl();
                if (url != null && url.contains("/")) {
                    String filename = url.substring(url.lastIndexOf('/') + 1);
                    switch (ref.getType() == null ? "image" : ref.getType()) {
                        case "video" -> fileStorageService.deleteVideo(filename);
                        case "audio" -> fileStorageService.deleteAudio(filename);
                        default -> fileStorageService.deleteImage(filename);
                    }
                }
            } catch (Exception e) {
                log.warn("删除参考素材对象失败: {}", e.getMessage());
            }
        }
        refImageMapper.deleteById(referenceId);
    }

    /** 归属校验：校验 userId 是否拥有 projectId 对应的项目 */
    private void checkProjectOwnership(String userId, String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || !project.getUserId().equals(userId)) {
            throw new BusinessException(40401, "项目不存在");
        }
    }

    private SceneResponse toResponse(Scene s, ScenePrompt p) {
        return new SceneResponse(
            s.getId(), s.getProjectId(), s.getConversationId(), s.getSceneNumber(),
            p != null ? p.getScriptContent() : null,
            p != null ? p.getImagePrompt() : null,
            p != null ? p.getVideoPrompt() : null,
            p != null ? p.getNegativePrompt() : null,
            p != null ? p.getCameraMovement() : null,
            p != null ? p.getShotType() : null,
            p != null ? p.getSoundDesign() : null,
            p != null ? p.getAiModel() : null,
            p != null ? p.getVideoResolution() : null,
            p != null ? p.getDuration() : null,
            s.getImageUrl(), s.getVideoUrl(),
            s.getImageStatus(), s.getVideoStatus(), s.getVideoTaskId(),
            s.getImageUrls(),
            p != null ? p.getImageModel() : null,
            p != null ? p.getImageSize() : null,
            p != null ? p.getImageQuality() : null,
            p != null ? p.getImageN() : null,
            p != null ? p.getVideoModel() : null,
            p != null ? p.getVideoAspectRatio() : null,
            s.getCreatedAt(), s.getUpdatedAt()
        );
    }

    /** 批量取分镜 prompt 明细（sceneIds → ScenePrompt）。 */
    private Map<String, ScenePrompt> loadPrompts(List<Scene> scenes) {
        if (scenes.isEmpty()) return Map.of();
        List<String> ids = scenes.stream().map(Scene::getId).toList();
        return scenePromptMapper.selectBatchIds(ids).stream()
            .collect(java.util.stream.Collectors.toMap(ScenePrompt::getSceneId, p -> p));
    }
}
