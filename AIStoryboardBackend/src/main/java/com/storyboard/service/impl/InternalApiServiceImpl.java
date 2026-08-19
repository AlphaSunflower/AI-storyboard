package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.storyboard.common.BusinessException;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.Asset;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneAsset;
import com.storyboard.mapper.AssetImageMapper;
import com.storyboard.mapper.AssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.InternalApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 内部 API 业务实现 — 承接 /api/internal/** 全部数据访问（原 InternalApiController 直操 Mapper 逻辑下沉）。
 */
@Service
@RequiredArgsConstructor
public class InternalApiServiceImpl implements InternalApiService {

    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
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
    public List<Scene> getProjectScenes(String projectId) {
        return sceneMapper.selectList(
                new LambdaQueryWrapper<Scene>().eq(Scene::getProjectId, projectId).orderByAsc(Scene::getSceneNumber));
    }

    @Override
    public void batchInsertScenes(List<Scene> scenes) {
        for (Scene scene : scenes) {
            sceneMapper.insert(scene);
        }
    }

    @Override
    public void deleteProjectScenes(String projectId) {
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
            SceneAsset link = new SceneAsset();
            link.setSceneId(sceneId);
            link.setAssetId(assetId);
            sceneAssetMapper.insert(link);
        }
    }

    @Override
    public Scene getScene(String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "场景不存在");
        return scene;
    }

    @Override
    public void updateScene(String sceneId, Scene scene) {
        scene.setId(sceneId);
        sceneMapper.updateById(scene);
    }

    @Override
    public void updateProjectSceneParams(String projectId, Map<String, String> params) {
        if (params == null || params.isEmpty()) return;
        LambdaUpdateWrapper<Scene> uw = new LambdaUpdateWrapper<Scene>().eq(Scene::getProjectId, projectId);
        putParam(uw, Scene::getImageModel, params.get("imageModel"));
        putParam(uw, Scene::getImageSize, params.get("imageSize"));
        putParam(uw, Scene::getImageQuality, params.get("imageQuality"));
        putParam(uw, Scene::getVideoModel, params.get("videoModel"));
        putParam(uw, Scene::getVideoResolution, params.get("videoResolution"));
        putParam(uw, Scene::getVideoAspectRatio, params.get("videoAspectRatio"));
        String d = params.get("videoDuration");
        if (d != null && !d.isBlank()) {
            try {
                uw.set(Scene::getDuration, Integer.parseInt(d.trim()));
            } catch (NumberFormatException ignored) {
                // 非法时长忽略
            }
        }
        String setSql = uw.getSqlSet();
        if (setSql == null || setSql.isBlank()) return;
        sceneMapper.update(null, uw);
    }

    private void putParam(LambdaUpdateWrapper<Scene> uw, SFunction<Scene, ?> col, String v) {
        if (v != null && !v.isBlank()) uw.set(col, v.trim());
    }

    @Override
    public List<AssetVO> getProjectAssets(String projectId) {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .and(w -> w.eq(Asset::getProjectId, projectId).or().isNull(Asset::getProjectId))
                .orderByDesc(Asset::getCreatedAt));
        return assets.stream().map(a -> {
            List<AssetImageVO> images = assetImageMapper.findByAssetId(a.getId()).stream()
                    .map(i -> new AssetImageVO(i.getId(), i.getUrl(), i.getSortOrder(), i.getFileName()))
                    .toList();
            return new AssetVO(a.getId(), a.getType(), a.getName(), a.getDescription(),
                    a.getProjectId(), images, a.getCreatedAt(), a.getUpdatedAt());
        }).toList();
    }

    @Override
    public Scene getSceneByVideoTaskId(String videoTaskId) {
        Scene scene = sceneMapper.selectOne(new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, videoTaskId));
        if (scene == null) throw new BusinessException(40401, "场景不存在");
        return scene;
    }
}
