package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.SceneRequest;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.common.BusinessException;
import com.storyboard.mapper.SceneMapper;
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
    private final FileStorageService fileStorageService;

    /** 参考素材上限兜底（对齐 MiniMax 输入约束；网关 params 精确值由前端展示，后端硬上限防滥用） */
    private static final Map<String, int[]> REF_LIMITS = Map.of(
        "image", new int[] { 10, 30 * 1024 * 1024 },   // 数量, 单文件字节
        "video", new int[] { 3, 50 * 1024 * 1024 },
        "audio", new int[] { 3, 15 * 1024 * 1024 });

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
        // 分镜生成参数覆盖（null = 不修改）
        if (request.imageModel() != null) scene.setImageModel(request.imageModel());
        if (request.imageSize() != null) scene.setImageSize(request.imageSize());
        if (request.imageQuality() != null) scene.setImageQuality(request.imageQuality());
        if (request.imageN() != null) scene.setImageN(request.imageN());
        if (request.videoModel() != null) scene.setVideoModel(request.videoModel());
        if (request.videoAspectRatio() != null) scene.setVideoAspectRatio(request.videoAspectRatio());

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

    @Override
    @Transactional
    public void reorderScenes(String projectId, List<String> sceneIds) {
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
    public List<SceneReferenceResponse> listReferences(String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        return refImageMapper.findBySceneId(sceneId).stream()
            .map(r -> new SceneReferenceResponse(r.getId(), r.getType(), r.getPurpose(), r.getImageUrl(),
                    r.getFileName(), r.getFileSize()))
            .toList();
    }

    @Override
    @Transactional
    public SceneReferenceResponse uploadReference(String sceneId, String type, String purpose, MultipartFile file) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
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
        ref.setImageUrl(url);
        ref.setFileName(file.getOriginalFilename());
        ref.setFileSize(file.getSize());
        ref.setSortOrder((int) existing);
        refImageMapper.insert(ref);
        return new SceneReferenceResponse(ref.getId(), t, p, url, ref.getFileName(), ref.getFileSize());
    }

    @Override
    public void deleteReference(String referenceId) {
        SceneReferenceImage ref = refImageMapper.selectById(referenceId);
        if (ref == null) throw new BusinessException(40401, "素材不存在");
        // 删除本地文件（失败仅警告，不阻断——素材表已删，残留文件由磁盘清理兜底）
        try {
            String url = ref.getImageUrl();
            if (url != null && url.contains("/")) {
                String filename = url.substring(url.lastIndexOf('/') + 1);
                Path p = switch (ref.getType() == null ? "image" : ref.getType()) {
                    case "video" -> fileStorageService.resolveVideo(filename);
                    case "audio" -> fileStorageService.resolveAudio(filename);
                    default -> fileStorageService.resolveImage(filename);
                };
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("删除参考素材本地文件失败: {}", e.getMessage());
        }
        refImageMapper.deleteById(referenceId);
    }

    private SceneResponse toResponse(Scene s) {
        return new SceneResponse(
            s.getId(), s.getProjectId(), s.getSceneNumber(),
            s.getScriptContent(), s.getImagePrompt(), s.getVideoPrompt(),
            s.getNegativePrompt(), s.getCameraMovement(), s.getShotType(),
            s.getSoundDesign(), s.getAiModel(), s.getVideoResolution(),
            s.getDuration(), s.getImageUrl(), s.getVideoUrl(),
            s.getImageStatus(), s.getVideoStatus(), s.getVideoTaskId(),
            s.getImageUrls(), s.getImageModel(), s.getImageSize(),
            s.getImageQuality(), s.getImageN(), s.getVideoModel(),
            s.getVideoAspectRatio(), s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
