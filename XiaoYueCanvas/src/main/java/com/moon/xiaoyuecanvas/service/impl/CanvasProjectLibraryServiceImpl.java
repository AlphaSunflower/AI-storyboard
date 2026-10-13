package com.moon.xiaoyuecanvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.response.CanvasProjectLibraryResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.service.CanvasProjectLibraryService;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetGroupDetail;
import com.storyboard.asset.client.AssetContracts.AssetGroupMediaItem;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.FolderTreeResponse;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.common.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CanvasProjectLibraryServiceImpl implements CanvasProjectLibraryService {

    /** 文件夹优先的浏览流：根目录返回项目/全局未归档素材和各项目文件夹。 */
    private static final String ROOT_FOLDER = "UNFILED";

    private final AssetClient assetClient;
    private final MorrowCanvasService canvasService;
    private final CanvasAssetMapper canvasAssetMapper;
    private final FileUrlSigner fileUrlSigner;

    public CanvasProjectLibraryServiceImpl(
            AssetClient assetClient,
            MorrowCanvasService canvasService,
            CanvasAssetMapper canvasAssetMapper,
            FileUrlSigner fileUrlSigner
    ) {
        this.assetClient = assetClient;
        this.canvasService = canvasService;
        this.canvasAssetMapper = canvasAssetMapper;
        this.fileUrlSigner = fileUrlSigner;
    }

    /** 读取边界重签：库内 URL 的签名会过期（导入的素材还没有签名），返回前刷新 token。 */
    private CanvasAsset signed(CanvasAsset asset) {
        if (asset != null) asset.setObjectUrl(fileUrlSigner.signIfLocal(asset.getObjectUrl()));
        return asset;
    }

    @Override
    public CanvasProjectLibraryResponse load(
            String userId,
            String projectId,
            AssetScope scope,
            String folderId,
            String type
    ) {
        String correlationId = UUID.randomUUID().toString();
        FolderTreeResponse projectTree = projectId == null || projectId.isBlank()
                ? null
                : assetClient.getFolderTreeForUser(userId, AssetScope.PROJECT, projectId, correlationId);
        FolderTreeResponse globalTree = assetClient.getFolderTreeForUser(
                userId, AssetScope.GLOBAL, null, correlationId);

        String effectiveFolder = folderId == null || folderId.isBlank() ? ROOT_FOLDER : folderId;
        List<AssetSummary> assets = new ArrayList<>();
        List<AssetSummary> groups = new ArrayList<>();
        if (scope == null || scope == AssetScope.PROJECT) {
            if (projectId != null && !projectId.isBlank()) {
                append(assets, groups, userId, AssetScope.PROJECT, projectId, effectiveFolder, type, correlationId);
            } else if (scope == AssetScope.PROJECT) {
                throw new BusinessException(40001, "项目库查询缺少 projectId");
            }
        }
        if (scope == null || scope == AssetScope.GLOBAL) {
            append(assets, groups, userId, AssetScope.GLOBAL, null, effectiveFolder, type, correlationId);
        }
        return new CanvasProjectLibraryResponse(projectTree, globalTree, assets, groups);
    }

    @Override
    public CanvasAsset importAsset(String userId, String canvasId, String libraryAssetId, String producerNodeId) {
        MorrowCanvas canvas = canvasService.get(userId, canvasId);
        CanvasAsset existing = canvasAssetMapper.selectOne(new LambdaQueryWrapper<CanvasAsset>()
                .eq(CanvasAsset::getCanvasId, canvasId)
                .eq(CanvasAsset::getLibraryAssetId, libraryAssetId)
                .last("LIMIT 1"));
        if (existing != null) return signed(existing);

        var detail = assetClient.getAssetInternal(libraryAssetId, userId);
        if (detail == null || detail.asset() == null || detail.media() == null || detail.media().isEmpty()) {
            throw new BusinessException(40401, "项目库素材不存在或没有媒体文件");
        }
        var media = detail.media().stream().filter(item -> item.primary()).findFirst()
                .orElse(detail.media().getFirst());
        String objectUrl = "/api/files/" + media.objectKey();
        CanvasAsset objectExisting = canvasAssetMapper.selectOne(new LambdaQueryWrapper<CanvasAsset>()
                .eq(CanvasAsset::getCanvasId, canvasId)
                .eq(CanvasAsset::getObjectUrl, objectUrl)
                .last("LIMIT 1"));
        if (objectExisting != null) return signed(objectExisting);

        CanvasAsset asset = new CanvasAsset();
        asset.setId(UUID.randomUUID().toString());
        asset.setCanvasId(canvasId);
        asset.setProjectId(canvas.getProjectId());
        asset.setUserId(userId);
        asset.setType(media.type().name().toLowerCase());
        asset.setMimeType(media.mimeType());
        asset.setObjectUrl(objectUrl);
        asset.setObjectKey(media.objectKey());
        asset.setProducerNodeId(producerNodeId);
        asset.setLibraryAssetId(libraryAssetId);
        asset.setLibrarySyncState("ready");
        asset.setStatus("ready");
        try {
            canvasAssetMapper.insert(asset);
        } catch (DuplicateKeyException duplicate) {
            CanvasAsset concurrent = canvasAssetMapper.selectOne(new LambdaQueryWrapper<CanvasAsset>()
                    .eq(CanvasAsset::getCanvasId, canvasId)
                    .eq(CanvasAsset::getObjectUrl, objectUrl)
                    .last("LIMIT 1"));
            if (concurrent != null) return signed(concurrent);
            throw duplicate;
        }
        return signed(asset);
    }

    @Override
    public CanvasAsset importMedia(String userId, String canvasId, String mediaId, String producerNodeId) {
        MorrowCanvas canvas = canvasService.get(userId, canvasId);
        MediaRef media = assetClient.getMediaInternal(mediaId, userId);
        String objectUrl = "/api/files/" + media.objectKey();
        CanvasAsset existing = canvasAssetMapper.selectOne(new LambdaQueryWrapper<CanvasAsset>()
                .eq(CanvasAsset::getCanvasId, canvasId)
                .eq(CanvasAsset::getObjectKey, media.objectKey())
                .last("LIMIT 1"));
        if (existing != null) return signed(existing);

        CanvasAsset asset = new CanvasAsset();
        asset.setId(UUID.randomUUID().toString());
        asset.setCanvasId(canvasId);
        asset.setProjectId(canvas.getProjectId());
        asset.setUserId(userId);
        asset.setType(media.type().name().toLowerCase());
        asset.setMimeType(media.mimeType());
        asset.setObjectUrl(objectUrl);
        asset.setObjectKey(media.objectKey());
        asset.setProducerNodeId(producerNodeId);
        asset.setLibraryAssetId(mediaId);
        asset.setLibrarySyncState("ready");
        asset.setStatus("ready");
        canvasAssetMapper.insert(asset);
        return signed(asset);
    }

    @Override
    public List<CanvasAsset> importGroup(
            String userId,
            String canvasId,
            String groupAssetId,
            String producerNodeId
    ) {
        MorrowCanvas canvas = canvasService.get(userId, canvasId);
        AssetGroupDetail group = assetClient.getGroupInternal(groupAssetId, userId);
        if (group == null || group.media() == null || group.media().isEmpty()) {
            throw new BusinessException(40401, "资产集不存在或没有媒体文件");
        }

        List<CanvasAsset> imported = new ArrayList<>();
        for (AssetGroupMediaItem item : group.media()) {
            MediaRef media = item.media();
            if (media == null) continue;
            String libraryAssetId = item.assetId() == null || item.assetId().isBlank()
                    ? groupAssetId + ":" + media.id()
                    : item.assetId();
            CanvasAsset existing = canvasAssetMapper.selectOne(new LambdaQueryWrapper<CanvasAsset>()
                    .eq(CanvasAsset::getCanvasId, canvasId)
                    .eq(CanvasAsset::getObjectKey, media.objectKey())
                    .last("LIMIT 1"));
            if (existing != null) {
                imported.add(existing);
                continue;
            }

            CanvasAsset asset = new CanvasAsset();
            asset.setId(UUID.randomUUID().toString());
            asset.setCanvasId(canvasId);
            asset.setProjectId(canvas.getProjectId());
            asset.setUserId(userId);
            asset.setType(media.type().name().toLowerCase());
            asset.setMimeType(media.mimeType());
            asset.setObjectUrl("/api/files/" + media.objectKey());
            asset.setObjectKey(media.objectKey());
            asset.setProducerNodeId(producerNodeId);
            asset.setLibraryAssetId(libraryAssetId);
            asset.setLibrarySyncState("ready");
            asset.setStatus("ready");
            canvasAssetMapper.insert(asset);
            imported.add(asset);
        }
        if (imported.isEmpty()) {
            throw new BusinessException(40401, "资产集不存在可导入的媒体文件");
        }
        return imported.stream().map(this::signed).toList();
    }

    private void append(
            List<AssetSummary> assets,
            List<AssetSummary> groups,
            String userId,
            AssetScope scope,
            String projectId,
            String folderId,
            String type,
            String correlationId
    ) {
        if (type != null && AssetType.GROUP.name().equals(type)) {
            var groupPage = assetClient.listAssetsForUser(
                    userId, scope, projectId, folderId, null, AssetType.GROUP.name(), 1, 100, correlationId);
            if (groupPage != null && groupPage.records() != null) {
                groups.addAll(groupPage.records());
            }
            return;
        }
        var mediaPage = assetClient.listAssetsForUser(
                userId, scope, projectId, folderId, null, type, 1, 100, correlationId);
        if (mediaPage != null && mediaPage.records() != null) {
            assets.addAll(mediaPage.records().stream()
                    .filter(asset -> asset.type() != AssetType.GROUP)
                    .toList());
        }
        var groupPage = assetClient.listAssetsForUser(
                userId, scope, projectId, folderId, null, AssetType.GROUP.name(), 1, 100, correlationId);
        if (groupPage != null && groupPage.records() != null) {
            groups.addAll(groupPage.records());
        }
    }
}
