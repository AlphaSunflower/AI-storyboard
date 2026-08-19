package com.moon.moonagent.service.impl;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.AssetCreateRequest;
import com.moon.moonagent.dto.request.AssetUpdateRequest;
import com.moon.moonagent.dto.response.AssetImageVO;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.dto.response.SceneAssetsResponse;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.entity.SceneAsset;
import com.moon.moonagent.mapper.SceneAssetMapper;
import com.moon.moonagent.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AssetService 实现：通过 StoryboardClient 调用主后端 + 本地 scene_assets 表。
 */
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final StoryboardClient storyboardClient;
    private final SceneAssetMapper sceneAssetMapper;

    @Override
    public List<AssetVO> projectAssets(String projectId) {
        return storyboardClient.getProjectAssets(projectId);
    }

    @Override
    public List<AssetVO> sceneAssets(String sceneId, String purpose) {
        // 1. 查本地 scene_assets 表拿关联的 assetId 列表
        List<SceneAsset> links = sceneAssetMapper.findBySceneId(sceneId);
        if (links.isEmpty()) return List.of();
        Set<String> assetIds = links.stream()
                .filter(sa -> purpose == null || purpose.equals(sa.getPurpose()))
                .map(SceneAsset::getAssetId)
                .collect(Collectors.toSet());
        if (assetIds.isEmpty()) return List.of();

        // 2. 拿场景的 projectId，再从主后端拉全部项目资产，按 assetIds 过滤
        Scene scene = storyboardClient.getScene(sceneId);
        if (scene == null) return List.of();
        List<AssetVO> all = storyboardClient.getProjectAssets(scene.getProjectId());
        return all.stream().filter(a -> assetIds.contains(a.id())).toList();
    }

    @Override
    public String buildSheetText(List<AssetVO> assets) {
        return StoryboardClient.buildSheetText(assets);
    }

    @Override
    public List<String> buildReferenceImages(List<AssetVO> assets) {
        if (assets == null || assets.isEmpty()) return List.of();
        // 优先级：character > prop > scene
        List<String> priorityOrder = List.of("character", "prop", "scene");
        List<AssetVO> sorted = new ArrayList<>(assets);
        sorted.sort(Comparator.comparingInt(a -> {
            int idx = priorityOrder.indexOf(a.type());
            return idx >= 0 ? idx : 99;
        }));
        List<String> result = new ArrayList<>();
        for (AssetVO a : sorted) {
            if (result.size() >= 9) break;
            if (a.images() != null && !a.images().isEmpty()) {
                // 主图 = sortOrder 最小的那张
                a.images().stream()
                        .min(Comparator.comparingInt(img -> img.sortOrder() != null ? img.sortOrder() : Integer.MAX_VALUE))
                        .ifPresent(img -> result.add(img.url()));
            }
        }
        return result;
    }

    // ── 以下方法 MoonAgent 不使用，抛 UnsupportedOperationException ──

    @Override
    public AssetVO create(String userId, AssetCreateRequest request) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public List<AssetVO> list(String userId, String projectId, String type) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public AssetVO update(String userId, String assetId, AssetUpdateRequest request) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public void delete(String userId, String assetId) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public AssetImageVO uploadImage(String userId, String assetId, MultipartFile file) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public void deleteImage(String userId, String assetId, String imageId) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public void setSceneAssets(String userId, String sceneId, List<String> imageAssetIds, List<String> videoAssetIds) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public SceneAssetsResponse listSceneAssets(String userId, String sceneId) {
        throw new UnsupportedOperationException("Use main backend for asset CRUD");
    }

    @Override
    public void linkSceneAssets(String sceneId, List<String> assetIds) {
        storyboardClient.linkSceneAssets(sceneId, assetIds);
    }
}
