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
import com.storyboard.asset.client.AssetContracts.ProjectOwnership;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    public ProjectOwnership getProjectOwnership(String id) {
        Project project = projectMapper.selectById(id);
        if (project == null) return new ProjectOwnership(id, null, false);
        return new ProjectOwnership(project.getId(), project.getUserId(), true);
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
            scene.setConversationId(s.conversationId());
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
    @Transactional
    public List<String> replaceProjectScenes(String projectId, List<SceneFull> scenes) {
        deleteProjectScenes(projectId);
        if (scenes == null || scenes.isEmpty()) return List.of();
        List<SceneFull> normalized = normalizeScenes(projectId, scenes, 1);
        List<String> sceneIds = normalized.stream().map(SceneFull::id).toList();
        batchInsertScenes(normalized);
        return sceneIds;
    }

    @Override
    @Transactional
    public List<String> appendProjectScenes(String projectId, List<SceneFull> scenes) {
        if (projectMapper.lockById(projectId) == null) {
            throw new BusinessException(40401, "项目不存在");
        }
        if (scenes == null || scenes.isEmpty()) return List.of();
        int maxSceneNumber = sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                        .eq(Scene::getProjectId, projectId)
                        .orderByDesc(Scene::getSceneNumber)
                        .last("LIMIT 1"))
                .stream()
                .map(Scene::getSceneNumber)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(0);
        List<SceneFull> normalized = normalizeScenes(projectId, scenes, maxSceneNumber + 1);
        List<String> sceneIds = normalized.stream().map(SceneFull::id).toList();
        batchInsertScenes(normalized);
        return sceneIds;
    }

    @Override
    public void deleteProjectScenes(String projectId) {
        // scene_prompts.scene_id FK ON DELETE CASCADE，删 scenes 即级联删明细
        sceneMapper.delete(new LambdaQueryWrapper<Scene>().eq(Scene::getProjectId, projectId));
    }

    @Override
    @Transactional
    public void deleteScene(String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "场景不存在");
        sceneMapper.deleteById(sceneId);
        List<Scene> remaining = sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                .eq(Scene::getProjectId, scene.getProjectId())
                .orderByAsc(Scene::getSceneNumber));
        for (int index = 0; index < remaining.size(); index++) {
            Scene item = remaining.get(index);
            if (item.getSceneNumber() != null && item.getSceneNumber() == index + 1) continue;
            item.setSceneNumber(index + 1);
            sceneMapper.updateById(item);
        }
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
            ref.setSourceType(link.sourceType());
            ref.setSourceId(link.sourceId());
            ref.setSourceConversationId(link.sourceConversationId());
            ref.setFileName(link.fileName());
            ref.setFileSize(link.fileSize());
            ref.setSortOrder(sort++);
            sceneReferenceImageMapper.insert(ref);
        }
    }

    @Override
    @Transactional
    public void replaceManagedVideoFrames(String sceneId, List<SceneReferenceLinkRequest> links) {
        sceneReferenceImageMapper.delete(new LambdaQueryWrapper<SceneReferenceImage>()
                .eq(SceneReferenceImage::getSceneId, sceneId)
                .eq(SceneReferenceImage::getPurpose, "video")
                .eq(SceneReferenceImage::getSourceType, "scene_image")
                .in(SceneReferenceImage::getRole, List.of("FIRST_FRAME", "LAST_FRAME")));
        linkSceneReferences(sceneId, links);
    }

    @Override
    public List<SceneReferenceResponse> getSceneReferences(String sceneId) {
        return sceneReferenceImageMapper.findBySceneId(sceneId).stream()
                .map(r -> new SceneReferenceResponse(r.getId(), r.getType(), r.getPurpose(), r.getImageUrl(),
                        r.getRole(), r.getSourceType(), r.getSourceId(), r.getMediaId(), r.getSourceConversationId(),
                        r.getFileName(), r.getFileSize()))
                .toList();
    }

    @Override
    public Map<String, Map<String, Boolean>> sceneMaterialPresence(String projectId,
                                                                   List<String> sceneIds) {
        if (sceneIds == null || sceneIds.isEmpty()) return Map.of();
        Set<String> requested = sceneIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (requested.isEmpty()) return Map.of();
        Set<String> owned = sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                        .eq(Scene::getProjectId, projectId)
                        .in(Scene::getId, requested))
                .stream().map(Scene::getId).collect(java.util.stream.Collectors.toSet());
        if (owned.isEmpty()) return Map.of();

        Set<String> referenced = sceneReferenceImageMapper.selectList(
                        new LambdaQueryWrapper<SceneReferenceImage>()
                                .in(SceneReferenceImage::getSceneId, owned))
                .stream().map(SceneReferenceImage::getSceneId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> linked = sceneAssetMapper.selectList(
                        new LambdaQueryWrapper<SceneAsset>()
                                .in(SceneAsset::getSceneId, owned))
                .stream().map(SceneAsset::getSceneId)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        for (String sceneId : requested) {
            if (!owned.contains(sceneId)) continue;
            result.put(sceneId, Map.of(
                    "hasReferences", referenced.contains(sceneId),
                    "hasAssets", linked.contains(sceneId)));
        }
        return result;
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
        if (s.conversationId() != null) scene.setConversationId(s.conversationId());
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

    private static List<SceneFull> normalizeScenes(
            String projectId, List<SceneFull> scenes, int startSceneNumber) {
        List<SceneFull> normalized = new ArrayList<>(scenes.size());
        for (int index = 0; index < scenes.size(); index++) {
            SceneFull source = scenes.get(index);
            String id = source.id() == null || source.id().isBlank()
                    ? UUID.randomUUID().toString() : source.id();
            normalized.add(new SceneFull(
                    id, projectId, source.conversationId(), startSceneNumber + index,
                    source.scriptContent(), source.imagePrompt(), source.videoPrompt(),
                    source.negativePrompt(), source.cameraMovement(), source.shotType(),
                    source.soundDesign(), source.aiModel(), source.videoResolution(),
                    source.duration(), source.imageUrl(), source.videoUrl(),
                    source.imageStatus(), source.videoStatus(), source.imageTaskId(),
                    source.videoTaskId(), source.imageUrls(), source.imageModel(),
                    source.imageSize(), source.imageQuality(), source.imageN(),
                    source.videoModel(), source.videoAspectRatio(),
                    source.createdAt(), source.updatedAt()));
        }
        return List.copyOf(normalized);
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
