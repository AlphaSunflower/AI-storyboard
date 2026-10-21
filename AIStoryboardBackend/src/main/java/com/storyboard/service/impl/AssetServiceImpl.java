package com.storyboard.service.impl;

import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AiAssetKind;
import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;
import com.storyboard.common.BusinessException;
import com.storyboard.dto.request.AssetCreateRequest;
import com.storyboard.dto.request.AssetUpdateRequest;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.dto.response.SceneAssetsResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetLinkMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 旧资产 API 的兼容适配器。
 * <p>数据源已切换为独立 asset-service；本类不再读写 assets/asset_images。
 */
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private static final int MAX_REFERENCE_IMAGES = 9;
    private static final Set<String> ASSET_TYPES = Set.of("character", "prop", "scene");
    private static final Map<String, Integer> TYPE_PRIORITY = Map.of("character", 0, "prop", 1, "scene", 2);
    private static final Map<String, String> TYPE_LABEL = Map.of("character", "人物", "prop", "道具", "scene", "场景");

    private final AssetClient assetClient;
    private final ProjectMapper projectMapper;
    private final SceneMapper sceneMapper;
    private final SceneAssetLinkMapper sceneAssetLinkMapper;

    @Override
    public AssetVO create(String userId, AssetCreateRequest request) {
        validateType(request.type());
        if (request.name() == null || request.name().isBlank()) {
            throw new BusinessException(40001, "资产名称不能为空");
        }
        String projectId = request.projectId() == null || request.projectId().isBlank() ? null : request.projectId();
        if (projectId != null) requireOwnedProject(userId, projectId);
        AssetSummary created = assetClient.createAsset(new CreateAssetRequest(
                userId,
                projectId,
                AssetType.AI_ASSET.name(),
                aiKind(request.type()).name(),
                request.name().trim(),
                request.description(),
                "ai_asset"
        ), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        return toVO(created, List.of());
    }

    @Override
    public List<AssetVO> list(String userId, String projectId, String type) {
        List<AssetSummary> merged = new ArrayList<>();
        if (projectId != null && !projectId.isBlank()) {
            merged.addAll(assetClient.listAssetsForUser(userId,
                    AssetScope.PROJECT, projectId, null, null,
                    AssetType.AI_ASSET.name(), 1, 100, UUID.randomUUID().toString()).records());
        }
        merged.addAll(assetClient.listAssetsForUser(userId,
                AssetScope.GLOBAL, null, null, null,
                AssetType.AI_ASSET.name(), 1, 100, UUID.randomUUID().toString()).records());
        return merged.stream()
                .filter(asset -> type == null || type.isBlank()
                        || aiKindName(asset.aiKind()).equalsIgnoreCase(type))
                .map(asset -> toVO(asset, mediaToImages(assetClient.getAssetInternal(asset.id(), userId).media())))
                .toList();
    }

    @Override
    public List<AssetVO> listAgentAssets(String userId, String projectId) {
        // 不按单一 asset_type 过滤：旧资产迁移后可能落在 GROUP（ai_kind 仍保留）。
        List<AssetSummary> merged = new ArrayList<>();
        for (String type : List.of(AssetType.AI_ASSET.name(), AssetType.GROUP.name())) {
            if (projectId != null && !projectId.isBlank()) {
                merged.addAll(assetClient.listAssetsForUser(userId,
                        AssetScope.PROJECT, projectId, null, null,
                        type, 1, 100, UUID.randomUUID().toString()).records());
            }
            merged.addAll(assetClient.listAssetsForUser(userId,
                    AssetScope.GLOBAL, null, null, null,
                    type, 1, 100, UUID.randomUUID().toString()).records());
        }
        return merged.stream()
                .filter(asset -> asset.aiKind() != null)
                .map(asset -> toVO(asset, mediaToImages(assetClient.getAssetInternal(asset.id(), userId).media())))
                .toList();
    }

    @Override
    public AssetVO update(String userId, String assetId, AssetUpdateRequest request) {
        AssetSummary current = assetClient.getAssetInternal(assetId, userId).asset();
        AssetSummary updated = assetClient.updateAsset(assetId, new UpdateAssetRequest(
                userId,
                blankToNull(request.name()),
                request.description(),
                null,
                current.version(),
                true
        ), current.version(), UUID.randomUUID().toString(), userId);
        return toVO(updated, mediaToImages(assetClient.getAssetInternal(assetId, userId).media()));
    }

    @Override
    @Transactional
    public void delete(String userId, String assetId) {
        AssetSummary current = assetClient.getAssetInternal(assetId, userId).asset();
        assetClient.trash(assetId, userId, current.version(), UUID.randomUUID().toString());
    }

    @Override
    public AssetImageVO uploadImage(String userId, String assetId, MultipartFile file, String text) {
        if (file == null || file.isEmpty()) throw new BusinessException(40001, "上传文件为空");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(40001, "仅支持上传图片文件");
        }
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };
            MediaRef media = assetClient.addAssetMedia(assetId, resource, UUID.randomUUID().toString(), userId);
            return new AssetImageVO(
                    media.id(), preferredMediaUrl(media), media.sortOrder(), file.getOriginalFilename(), text);
        } catch (Exception e) {
            throw new BusinessException(50201, "上传资产图片失败", e);
        }
    }

    @Override
    public void deleteImage(String userId, String assetId, String imageId) {
        assetClient.deleteAssetMedia(assetId, imageId, UUID.randomUUID().toString(), userId);
    }

    @Override
    @Transactional
    public void setSceneAssets(String userId, String sceneId, List<String> imageAssetIds, List<String> videoAssetIds) {
        getOwnedScene(userId, sceneId);
        validateAssetIds(userId, imageAssetIds);
        validateAssetIds(userId, videoAssetIds);
        sceneAssetLinkMapper.deleteBySceneId(sceneId);
        insertLinks(sceneId, imageAssetIds, "image");
        insertLinks(sceneId, videoAssetIds, "video");
    }

    @Override
    public SceneAssetsResponse listSceneAssets(String userId, String sceneId) {
        Scene scene = getOwnedScene(userId, sceneId);
        String ownerUserId = ownerUserId(scene.getProjectId());
        return new SceneAssetsResponse(
                doListSceneAssets(ownerUserId, sceneId, "image"),
                doListSceneAssets(ownerUserId, sceneId, "video"));
    }

    @Override
    public void linkSceneAssets(String sceneId, List<String> assetIds) {
        if (sceneId == null || sceneId.isBlank() || assetIds == null || assetIds.isEmpty()) return;
        for (String id : assetIds.stream().distinct().toList()) {
            insertLinks(sceneId, List.of(id), "image");
            insertLinks(sceneId, List.of(id), "video");
        }
    }

    @Override
    public List<AssetVO> projectAssets(String projectId) {
        if (projectId == null || projectId.isBlank()) return List.of();
        Project project = projectMapper.selectById(projectId);
        if (project == null) return List.of();
        return list(project.getUserId(), projectId, null);
    }

    @Override
    public List<AssetVO> sceneAssets(String sceneId, String purpose) {
        if (sceneId == null || sceneId.isBlank()) return List.of();
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) return List.of();
        return doListSceneAssets(ownerUserId(scene.getProjectId()), sceneId, purpose);
    }

    @Override
    public String buildSheetText(List<AssetVO> assets) {
        if (assets == null || assets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n本片固定设定（所有分镜必须严格遵循，不得改变外貌/服装/道具外观/场景构成）：\n");
        for (AssetVO asset : assets) {
            sb.append("- ").append(TYPE_LABEL.getOrDefault(asset.type(), asset.type()))
                    .append("【").append(asset.name()).append("】");
            if (asset.description() != null && !asset.description().isBlank()) {
                sb.append("：").append(asset.description().trim());
            }
            sb.append("\n");
            int index = 0;
            for (AssetImageVO image : sortedImages(asset.images())) {
                index++;
                if (image.text() != null && !image.text().isBlank()) {
                    sb.append("  · ").append(asset.name()).append(" 图").append(index)
                            .append("约束：").append(image.text().trim()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public List<String> buildReferenceImages(List<AssetVO> assets) {
        if (assets == null || assets.isEmpty()) return List.of();
        List<AssetVO> sorted = new ArrayList<>(assets);
        sorted.sort(Comparator.comparingInt(asset -> TYPE_PRIORITY.getOrDefault(asset.type(), 3)));
        List<String> result = new ArrayList<>();
        for (AssetVO asset : sorted) {
            for (AssetImageVO image : sortedImages(asset.images())) {
                if (result.size() >= MAX_REFERENCE_IMAGES) return List.copyOf(result);
                result.add(image.url());
            }
        }
        return List.copyOf(result);
    }

    private List<AssetVO> doListSceneAssets(String ownerUserId, String sceneId, String purpose) {
        return sceneAssetLinkMapper.findAssetIds(sceneId, purpose).stream()
                .map(assetId -> {
                    AssetDetail detail = assetClient.getAssetInternal(assetId, ownerUserId);
                    return toVO(detail.asset(), mediaToImages(detail.media()));
                })
                .toList();
    }

    private void validateAssetIds(String userId, List<String> assetIds) {
        if (assetIds == null) return;
        for (String id : assetIds) {
            if (id != null && !id.isBlank()) assetClient.getAssetInternal(id, userId);
        }
    }

    private void insertLinks(String sceneId, List<String> assetIds, String purpose) {
        if (assetIds == null) return;
        for (String id : assetIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList()) {
            sceneAssetLinkMapper.insert(sceneId, id, purpose);
        }
    }

    private Scene getOwnedScene(String userId, String sceneId) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new BusinessException(40401, "分镜不存在");
        if (!userId.equals(ownerUserId(scene.getProjectId()))) {
            throw new BusinessException(40401, "分镜不存在或无权访问");
        }
        return scene;
    }

    private void requireOwnedProject(String userId, String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) throw new BusinessException(40401, "项目不存在");
        if (!userId.equals(project.getUserId())) throw new BusinessException(40301, "无权为该项目创建资产");
    }

    private String ownerUserId(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getUserId() == null) throw new BusinessException(40401, "项目不存在");
        return project.getUserId();
    }

    private static List<AssetImageVO> mediaToImages(List<MediaRef> media) {
        if (media == null) return List.of();
        return media.stream().map(item -> new AssetImageVO(
                item.id(), preferredMediaUrl(item), item.sortOrder(), item.fileName(), item.constraintText())).toList();
    }

    private static String preferredMediaUrl(MediaRef media) {
        return media.contentUrl() == null || media.contentUrl().isBlank() ? media.url() : media.contentUrl();
    }

    private static AssetVO toVO(AssetSummary asset, List<AssetImageVO> images) {
        return new AssetVO(
                asset.id(), aiKindName(asset.aiKind()), asset.name(), asset.description(),
                asset.ownerProjectId(), images, asset.createdAt(), asset.updatedAt());
    }

    private static List<AssetImageVO> sortedImages(List<AssetImageVO> images) {
        if (images == null) return List.of();
        return images.stream().sorted(Comparator.comparingInt(
                image -> image.sortOrder() == null ? Integer.MAX_VALUE : image.sortOrder())).toList();
    }

    private static void validateType(String type) {
        if (type == null || !ASSET_TYPES.contains(type)) {
            throw new BusinessException(40001, "资产类型必须是 character/prop/scene");
        }
    }

    private static AiAssetKind aiKind(String type) {
        return switch (type) {
            case "character" -> AiAssetKind.CHARACTER;
            case "prop" -> AiAssetKind.PROP;
            case "scene" -> AiAssetKind.SCENE;
            default -> throw new BusinessException(40001, "资产类型不合法");
        };
    }

    private static String aiKindName(AiAssetKind kind) {
        return kind == null ? "" : kind.name().toLowerCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
