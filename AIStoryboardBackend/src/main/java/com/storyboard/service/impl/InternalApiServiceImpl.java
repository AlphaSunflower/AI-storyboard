package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.storyboard.common.BusinessException;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.dto.response.SceneFull;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.request.SceneReferenceLinkRequest;
import com.storyboard.entity.Asset;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneAsset;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.entity.SceneReferenceImage;
import com.storyboard.mapper.AssetImageMapper;
import com.storyboard.mapper.AssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.service.InternalApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 内部 API 业务实现 — 承接 /api/internal/** 全部数据访问（原 InternalApiController 直操 Mapper 逻辑下沉）。
 * 分镜宽表拆分后，读返回全量 {@link SceneFull}（保持 MoonAgent 契约），写按 scenes + scene_prompts 落库。
 */
@Service
@RequiredArgsConstructor
public class InternalApiServiceImpl implements InternalApiService {

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final ScenePromptMapper scenePromptMapper;
    private final SceneReferenceImageMapper sceneReferenceImageMapper;
    private final SceneAssetMapper sceneAssetMapper;
    private final AssetMapper assetMapper;
    private final AssetImageMapper assetImageMapper;

    @Override
    public Project getProject(String id) {
        Project project = projectMapper.selectById(id);
        if (project == null) throw new BusinessException(40401, "项目不存在");
        return project;
    }

    @Override
    public List<SceneFull> getProjectScenes(String projectId) {
        List<Scene> scenes = sceneMapper.selectList(
                new LambdaQueryWrapper<Scene>().eq(Scene::getProjectId, projectId).orderByAsc(Scene::getSceneNumber));
        Map<String, ScenePrompt> prompts = loadsPrompts(scenes);
        return scenes.stream().map(s -> toFull(s, prompts.get(s.getId()))).toList();
    }

    @Override
    public void batchInsertScenes(List<SceneFull> scenes) {
        for (SceneFull s : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(s.projectId());
            scene.setSceneNumber(s.sceneNumber());
            scene.setImageUrl(s.imageUrl());
            scene.setVideoUrl(s.videoUrl());
            scene.setImageStatus(s.imageStatus());
            scene.setVideoStatus(s.videoStatus());
            scene.setImageTaskId(s.imageTaskId());
            scene.setVideoTaskId(s.videoTaskId());
            scene.setImageUrls(s.imageUrls());
            if (s.id() != null) scene.setId(s.id());
            sceneMapper.insert(scene);

            ScenePrompt prompt = new ScenePrompt();
            prompt.setSceneId(scene.getId());
            prompt.setScriptContent(s.scriptContent());
            prompt.setImagePrompt(s.imagePrompt());
            prompt.setVideoPrompt(s.videoPrompt());
            prompt.setNegativePrompt(s.negativePrompt());
            prompt.setCameraMovement(s.cameraMovement());
            prompt.setShotType(s.shotType());
            prompt.setSoundDesign(s.soundDesign());
            prompt.setAiModel(s.aiModel());
            prompt.setVideoResolution(s.videoResolution());
            prompt.setDuration(s.duration());
            prompt.setImageModel(s.imageModel());
            prompt.setImageSize(s.imageSize());
            prompt.setImageQuality(s.imageQuality());
            prompt.setImageN(s.imageN());
            prompt.setVideoModel(s.videoModel());
            prompt.setVideoAspectRatio(s.videoAspectRatio());
            scenePromptMapper.insert(prompt);
        }
    }

    @Override
    public void deleteProjectScenes(String projectId) {
        // scene_prompts.scene_id FK ON DELETE CASCADE，删 scenes 即级联删明细
        sceneMapper.delete(new LambdaQueryWrapper<Scene>().eq(Scene::getProjectId, projectId));
    }

    @Override
    public List<SceneAsset> getSceneAssets(String sceneId) {
        return sceneAssetMapper.selectList(
                new LambdaQueryWrapper<SceneAsset>().eq(SceneAsset::getSceneId, sceneId));
    }

    @Override
    public void linkSceneAssets(String sceneId, List<String> assetIds) {
        for (String assetId : assetIds) {
            if (assetId == null || assetId.isBlank()) continue;
            for (String purpose : List.of("image", "video")) {
                long exists = sceneAssetMapper.selectCount(new LambdaQueryWrapper<SceneAsset>()
                        .eq(SceneAsset::getSceneId, sceneId)
                        .eq(SceneAsset::getAssetId, assetId)
                        .eq(SceneAsset::getPurpose, purpose));
                if (exists > 0) continue;
                SceneAsset link = new SceneAsset();
                link.setSceneId(sceneId);
                link.setAssetId(assetId);
                link.setPurpose(purpose);
                sceneAssetMapper.insert(link);
            }
        }
    }

    @Override
    public void linkSceneReferences(String sceneId, List<SceneReferenceLinkRequest> links) {
        if (links == null || links.isEmpty()) return;
        int sort = (int) (long) sceneReferenceImageMapper.selectCount(
                new LambdaQueryWrapper<SceneReferenceImage>().eq(SceneReferenceImage::getSceneId, sceneId));
        for (SceneReferenceLinkRequest link : links) {
            if (link == null || link.url() == null || link.url().isBlank()) continue;
            long exists = sceneReferenceImageMapper.selectCount(new LambdaQueryWrapper<SceneReferenceImage>()
                    .eq(SceneReferenceImage::getSceneId, sceneId)
                    .eq(SceneReferenceImage::getImageUrl, link.url()));
            if (exists > 0) continue;
            SceneReferenceImage ref = new SceneReferenceImage();
            ref.setSceneId(sceneId);
            ref.setImageUrl(link.url());
            ref.setType(link.type() == null || link.type().isBlank() ? "image" : link.type());
            ref.setPurpose(link.purpose() == null || link.purpose().isBlank() ? "video" : link.purpose());
            ref.setRole(link.role() == null || link.role().isBlank() ? "REFERENCE" : link.role());
            ref.setFileName(link.fileName());
            ref.setFileSize(link.fileSize());
            ref.setSortOrder(sort++);
            sceneReferenceImageMapper.insert(ref);
        }
    }

    @Override
    public List<SceneReferenceResponse> getSceneReferences(String sceneId) {
        return sceneReferenceImageMapper.findBySceneId(sceneId).stream()
                .map(r -> new SceneReferenceResponse(r.getId(), r.getType(), r.getPurpose(), r.getImageUrl(),
                        r.getRole(), r.getFileName(), r.getFileSize()))
                .toList();
    }

    @Override
    public SceneFull getScene(String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "场景不存在");
        return toFull(scene, scenePromptMapper.selectById(sceneId));
    }

    @Override
    public void updateScene(String sceneId, SceneFull s) {
        Scene scene = new Scene();
        scene.setId(sceneId);
        if (s.projectId() != null) scene.setProjectId(s.projectId());
        if (s.imageUrl() != null) scene.setImageUrl(s.imageUrl());
        if (s.videoUrl() != null) scene.setVideoUrl(s.videoUrl());
        if (s.imageStatus() != null) scene.setImageStatus(s.imageStatus());
        if (s.videoStatus() != null) scene.setVideoStatus(s.videoStatus());
        if (s.imageTaskId() != null) scene.setImageTaskId(s.imageTaskId());
        if (s.videoTaskId() != null) scene.setVideoTaskId(s.videoTaskId());
        if (s.imageUrls() != null) scene.setImageUrls(s.imageUrls());
        sceneMapper.updateById(scene);

        ScenePrompt prompt = scenePromptMapper.selectById(sceneId);
        boolean had = prompt != null;
        if (prompt == null) {
            prompt = new ScenePrompt();
            prompt.setSceneId(sceneId);
        }
        if (s.scriptContent() != null) prompt.setScriptContent(s.scriptContent());
        if (s.imagePrompt() != null) prompt.setImagePrompt(s.imagePrompt());
        if (s.videoPrompt() != null) prompt.setVideoPrompt(s.videoPrompt());
        if (s.negativePrompt() != null) prompt.setNegativePrompt(s.negativePrompt());
        if (s.cameraMovement() != null) prompt.setCameraMovement(s.cameraMovement());
        if (s.shotType() != null) prompt.setShotType(s.shotType());
        if (s.soundDesign() != null) prompt.setSoundDesign(s.soundDesign());
        if (s.aiModel() != null) prompt.setAiModel(s.aiModel());
        if (s.videoResolution() != null) prompt.setVideoResolution(s.videoResolution());
        if (s.duration() != null) prompt.setDuration(s.duration());
        if (s.imageModel() != null) prompt.setImageModel(s.imageModel());
        if (s.imageSize() != null) prompt.setImageSize(s.imageSize());
        if (s.imageQuality() != null) prompt.setImageQuality(s.imageQuality());
        if (s.imageN() != null) prompt.setImageN(s.imageN());
        if (s.videoModel() != null) prompt.setVideoModel(s.videoModel());
        if (s.videoAspectRatio() != null) prompt.setVideoAspectRatio(s.videoAspectRatio());
        if (had) {
            scenePromptMapper.updateById(prompt);
        } else {
            scenePromptMapper.insert(prompt);
        }
    }

    @Override
    public void updateProjectSceneParams(String projectId, Map<String, String> params) {
        if (params == null || params.isEmpty()) return;
        LambdaUpdateWrapper<ScenePrompt> uw = new LambdaUpdateWrapper<ScenePrompt>()
                .inSql(ScenePrompt::getSceneId, "SELECT id FROM scenes WHERE project_id = '"
                        + projectId.replace("'", "''") + "'");
        putParam(uw, ScenePrompt::getImageModel, params.get("imageModel"));
        putParam(uw, ScenePrompt::getImageSize, params.get("imageSize"));
        putParam(uw, ScenePrompt::getImageQuality, params.get("imageQuality"));
        putParam(uw, ScenePrompt::getVideoModel, params.get("videoModel"));
        putParam(uw, ScenePrompt::getVideoResolution, params.get("videoResolution"));
        putParam(uw, ScenePrompt::getVideoAspectRatio, params.get("videoAspectRatio"));
        String d = params.get("videoDuration");
        if (d != null && !d.isBlank()) {
            try {
                uw.set(ScenePrompt::getDuration, Integer.parseInt(d.trim()));
            } catch (NumberFormatException ignored) {
                // 非法时长忽略
            }
        }
        String setSql = uw.getSqlSet();
        if (setSql == null || setSql.isBlank()) return;
        scenePromptMapper.update(null, uw);
    }

    private void putParam(LambdaUpdateWrapper<ScenePrompt> uw, SFunction<ScenePrompt, ?> col, String v) {
        if (v != null && !v.isBlank()) uw.set(col, v.trim());
    }

    @Override
    public List<AssetVO> getProjectAssets(String projectId) {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .and(w -> w.eq(Asset::getProjectId, projectId).or().isNull(Asset::getProjectId))
                .orderByDesc(Asset::getCreatedAt));
        return assets.stream().map(a -> {
            List<AssetImageVO> images = assetImageMapper.findByAssetId(a.getId()).stream()
                    .map(i -> new AssetImageVO(i.getId(), i.getUrl(), i.getSortOrder(), i.getFileName(), i.getText()))
                    .toList();
            return new AssetVO(a.getId(), a.getType(), a.getName(), a.getDescription(),
                    a.getProjectId(), images, a.getCreatedAt(), a.getUpdatedAt());
        }).toList();
    }

    @Override
    public SceneFull getSceneByVideoTaskId(String videoTaskId) {
        Scene scene = sceneMapper.selectOne(new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, videoTaskId));
        if (scene == null) throw new BusinessException(40401, "场景不存在");
        return toFull(scene, scenePromptMapper.selectById(scene.getId()));
    }

    private SceneFull toFull(Scene s, ScenePrompt p) {
        return new SceneFull(
            s.getId(), s.getProjectId(), s.getSceneNumber(),
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
            s.getImageStatus(), s.getVideoStatus(),
            s.getImageTaskId(), s.getVideoTaskId(),
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

    private Map<String, ScenePrompt> loadsPrompts(List<Scene> scenes) {
        if (scenes.isEmpty()) return Map.of();
        return scenePromptMapper.selectBatchIds(scenes.stream().map(Scene::getId).toList()).stream()
            .collect(java.util.stream.Collectors.toMap(ScenePrompt::getSceneId, p -> p));
    }
}
