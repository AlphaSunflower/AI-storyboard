package com.storyboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.AssetCreateRequest;
import com.storyboard.dto.request.AssetUpdateRequest;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.Asset;
import com.storyboard.entity.AssetImage;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneAsset;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.AssetImageMapper;
import com.storyboard.mapper.AssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.AssetService;
import com.storyboard.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 资产库服务实现。
 */
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetServiceImpl.class);

    /** H3 参考图硬上限（业务侧截断，与网关/上游一致）。 */
    private static final int MAX_REFERENCE_IMAGES = 9;
    /** 单张资产图大小上限（与 H3 参考图一致）。 */
    private static final long MAX_IMAGE_SIZE = 30L * 1024 * 1024;
    /** 合法资产类型。 */
    private static final Set<String> ASSET_TYPES = Set.of("character", "prop", "scene");
    /** 参考图注入优先级：人物 > 道具 > 场景（数值越小越优先）。 */
    private static final Map<String, Integer> TYPE_PRIORITY = Map.of("character", 0, "prop", 1, "scene", 2);
    /** type → 设定集文字块里的中文标签。 */
    private static final Map<String, String> TYPE_LABEL = Map.of("character", "人物", "prop", "道具", "scene", "场景");

    private final AssetMapper assetMapper;
    private final AssetImageMapper assetImageMapper;
    private final SceneAssetMapper sceneAssetMapper;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final FileStorageService fileStorageService;

    @Override
    public AssetVO create(String userId, AssetCreateRequest request) {
        String type = request.type();
        if (type == null || !ASSET_TYPES.contains(type)) {
            throw new BusinessException(40001, "资产类型必须是 character/prop/scene");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BusinessException(40001, "资产名称不能为空");
        }
        // 项目资产需校验项目归属
        String projectId = (request.projectId() == null || request.projectId().isBlank()) ? null : request.projectId();
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            if (project == null) throw new BusinessException(40401, "项目不存在");
            if (!userId.equals(project.getUserId())) throw new BusinessException(40301, "无权为该项目创建资产");
        }
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setProjectId(projectId);
        asset.setType(type);
        asset.setName(request.name());
        asset.setDescription(request.description());
        assetMapper.insert(asset);
        return toVO(asset, List.of());
    }

    @Override
    public List<AssetVO> list(String userId, String projectId, String type) {
        List<Asset> assets = assetMapper.findByProjectOrGlobal(userId, projectId);
        if (type != null && !type.isBlank()) {
            assets = assets.stream().filter(a -> type.equals(a.getType())).toList();
        }
        return assets.stream().map(a -> toVO(a, loadImages(a.getId()))).toList();
    }

    @Override
    public AssetVO update(String userId, String assetId, AssetUpdateRequest request) {
        Asset asset = getOwnedAsset(userId, assetId);
        if (request.name() != null) {
            if (request.name().isBlank()) throw new BusinessException(40001, "资产名称不能为空");
            asset.setName(request.name());
        }
        if (request.description() != null) {
            asset.setDescription(request.description());
        }
        assetMapper.updateById(asset);
        return toVO(asset, loadImages(assetId));
    }

    @Override
    @Transactional
    public void delete(String userId, String assetId) {
        getOwnedAsset(userId, assetId);
        // 先清磁盘图片文件（asset_images / scene_assets 行由 DB 级联删）
        for (AssetImage img : assetImageMapper.findByAssetId(assetId)) {
            deleteLocalFile(img.getUrl());
        }
        assetMapper.deleteById(assetId);
    }

    @Override
    public AssetImageVO uploadImage(String userId, String assetId, MultipartFile file) {
        getOwnedAsset(userId, assetId);
        if (file == null || file.isEmpty()) throw new BusinessException(40001, "上传文件为空");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(40001, "仅支持上传图片文件");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) throw new BusinessException(40001, "图片超过大小限制 30MB");
        String url = fileStorageService.saveUploadedImage(file);
        long count = assetImageMapper.selectCount(new LambdaQueryWrapper<AssetImage>().eq(AssetImage::getAssetId, assetId));
        AssetImage img = new AssetImage();
        img.setAssetId(assetId);
        img.setUrl(url);
        img.setFileName(file.getOriginalFilename());
        img.setSortOrder((int) count);
        assetImageMapper.insert(img);
        return new AssetImageVO(img.getId(), url, img.getSortOrder(), img.getFileName());
    }

    @Override
    public void deleteImage(String userId, String assetId, String imageId) {
        getOwnedAsset(userId, assetId);
        AssetImage img = assetImageMapper.selectById(imageId);
        if (img == null || !assetId.equals(img.getAssetId())) throw new BusinessException(40401, "图片不存在");
        deleteLocalFile(img.getUrl());
        assetImageMapper.deleteById(imageId);
    }

    @Override
    @Transactional
    public void setSceneAssets(String userId, String sceneId, List<String> assetIds) {
        getOwnedScene(userId, sceneId);
        // 校验所有资产归属（无权访问统一 40401）
        if (assetIds != null) {
            for (String id : assetIds) {
                getOwnedAsset(userId, id);
            }
        }
        // 覆盖式：清空旧关联再写入
        sceneAssetMapper.delete(new LambdaQueryWrapper<SceneAsset>().eq(SceneAsset::getSceneId, sceneId));
        if (assetIds != null) {
            for (String id : assetIds) {
                SceneAsset link = new SceneAsset();
                link.setSceneId(sceneId);
                link.setAssetId(id);
                sceneAssetMapper.insert(link);
            }
        }
    }

    @Override
    public List<AssetVO> listSceneAssets(String userId, String sceneId) {
        getOwnedScene(userId, sceneId);
        return doListSceneAssets(sceneId);
    }

    @Override
    public List<AssetVO> projectAssets(String projectId) {
        if (projectId == null || projectId.isBlank()) return List.of();
        Project project = projectMapper.selectById(projectId);
        if (project == null) return List.of();
        return list(project.getUserId(), projectId, null);
    }

    @Override
    public List<AssetVO> sceneAssets(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) return List.of();
        return doListSceneAssets(sceneId);
    }

    @Override
    public String buildSheetText(List<AssetVO> assets) {
        if (assets == null || assets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n本片固定设定（所有分镜必须严格遵循，不得改变外貌/服装/道具外观/场景构成）：\n");
        for (AssetVO a : assets) {
            sb.append("- ").append(TYPE_LABEL.getOrDefault(a.type(), a.type()))
              .append("【").append(a.name()).append("】");
            if (a.description() != null && !a.description().isBlank()) {
                sb.append("：").append(a.description().trim());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public List<String> buildReferenceImages(List<AssetVO> assets) {
        if (assets == null || assets.isEmpty()) return List.of();
        List<AssetVO> sorted = new ArrayList<>(assets);
        sorted.sort(Comparator.comparingInt(a -> TYPE_PRIORITY.getOrDefault(a.type(), 3)));
        List<String> refs = new ArrayList<>();
        for (AssetVO a : sorted) {
            if (refs.size() >= MAX_REFERENCE_IMAGES) break;
            if (a.images() != null && !a.images().isEmpty()) {
                refs.add(a.images().getFirst().url());
            }
        }
        return refs;
    }

    // ─────────── 私有辅助 ───────────

    /** 分镜关联资产（含图），无归属校验。 */
    private List<AssetVO> doListSceneAssets(String sceneId) {
        List<SceneAsset> links = sceneAssetMapper.findBySceneId(sceneId);
        if (links.isEmpty()) return List.of();
        List<String> ids = links.stream().map(SceneAsset::getAssetId).distinct().toList();
        return assetMapper.selectBatchIds(ids).stream()
                .map(a -> toVO(a, loadImages(a.getId())))
                .toList();
    }

    /** 资产归属校验：不存在或非本人一律 40401（防 IDOR 枚举）。 */
    private Asset getOwnedAsset(String userId, String assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || !userId.equals(asset.getUserId())) {
            throw new BusinessException(40401, "资产不存在或无权访问");
        }
        return asset;
    }

    /** 分镜归属校验：分镜 → 项目 → 用户，任一不匹配 40401。 */
    private Scene getOwnedScene(String userId, String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        Project project = projectMapper.selectById(scene.getProjectId());
        if (project == null || !userId.equals(project.getUserId())) {
            throw new BusinessException(40401, "分镜不存在或无权访问");
        }
        return scene;
    }

    private List<AssetImageVO> loadImages(String assetId) {
        return assetImageMapper.findByAssetId(assetId).stream()
                .map(i -> new AssetImageVO(i.getId(), i.getUrl(), i.getSortOrder(), i.getFileName()))
                .toList();
    }

    private AssetVO toVO(Asset a, List<AssetImageVO> images) {
        return new AssetVO(a.getId(), a.getType(), a.getName(), a.getDescription(),
                a.getProjectId(), images, a.getCreatedAt(), a.getUpdatedAt());
    }

    /** 删除本地图片文件（失败仅警告，不阻断——DB 行已删，残留文件由磁盘清理兜底）。 */
    private void deleteLocalFile(String url) {
        if (url == null || !url.contains("/")) return;
        try {
            String filename = url.substring(url.lastIndexOf('/') + 1);
            Files.deleteIfExists(fileStorageService.resolveImage(filename));
        } catch (IOException e) {
            log.warn("删除资产图片本地文件失败: {}", e.getMessage());
        }
    }
}
