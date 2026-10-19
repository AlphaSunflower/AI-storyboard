package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.DuplicateGroup;
import com.storyboard.asset.client.AssetContracts.MergeDuplicatesRequest;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.DuplicateService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class DuplicateServiceImpl implements DuplicateService {

    private static final int MAX_MERGE_ASSETS = 50;

    private final LibraryRepository repository;
    private final AssetCatalogService assetCatalogService;
    private final ProjectAccessPort projectAccess;
    private final StorageProperties properties;

    public DuplicateServiceImpl(
            LibraryRepository repository,
            AssetCatalogService assetCatalogService,
            ProjectAccessPort projectAccess,
            StorageProperties properties
    ) {
        this.repository = repository;
        this.assetCatalogService = assetCatalogService;
        this.projectAccess = projectAccess;
        this.properties = properties;
    }

    @Override
    public PageResponse<DuplicateGroup> list(String userId, String projectId, int page, int size) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(40001, "请先选择项目");
        }
        projectAccess.requireOwned(userId, projectId);
        return repository.listDuplicateGroups(userId, projectId, page, size);
    }

    @Override
    @Transactional
    public AssetSummary merge(String userId, MergeDuplicatesRequest request) {
        if (request == null || request.survivorAssetId() == null || request.survivorAssetId().isBlank()) {
            throw new BusinessException(40001, "请选择要保留的素材");
        }
        List<String> duplicateIds = request.duplicateAssetIds() == null
                ? List.of()
                : request.duplicateAssetIds().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .filter(id -> !Objects.equals(id, request.survivorAssetId()))
                        .distinct()
                        .toList();
        if (duplicateIds.isEmpty()) {
            throw new BusinessException(40001, "请选择要合并的重复素材");
        }
        if (duplicateIds.size() > MAX_MERGE_ASSETS) {
            throw new BusinessException(40001, "单次最多合并 50 个重复素材");
        }

        AssetSummary survivor = requireActiveAsset(userId, request.survivorAssetId());
        OffsetDateTime purgeAfter = OffsetDateTime.now().plusDays(properties.trashRetentionDays());
        for (String duplicateId : duplicateIds) {
            AssetSummary duplicate = requireActiveAsset(userId, duplicateId);
            if (duplicate.scope() != survivor.scope()
                    || !Objects.equals(duplicate.ownerProjectId(), survivor.ownerProjectId())) {
                throw new BusinessException(40001, "只能合并同一项目、同一范围的素材");
            }
            if (!repository.shareBlob(survivor.id(), duplicate.id())) {
                throw new BusinessException(40001, "所选素材不是同一份文件，无法合并");
            }
            repository.copyMissingMedia(survivor.id(), duplicate.id());
            repository.moveFolderMappings(survivor.id(), duplicate.id());
            repository.moveTags(survivor.id(), duplicate.id());
            repository.moveProjectLinks(survivor.id(), duplicate.id());
            repository.moveGroupItems(survivor.id(), duplicate.id());
            repository.moveGroupOutputs(survivor.id(), duplicate.id());
            boolean trashed = repository.trash(
                    userId, duplicate.id(), duplicate.version(), purgeAfter);
            if (!trashed) {
                throw new BusinessException(40901, "素材状态已变化，合并已取消");
            }
        }
        return assetCatalogService.get(userId, survivor.id()).asset();
    }

    private AssetSummary requireActiveAsset(String userId, String assetId) {
        AssetSummary asset = repository.findAsset(userId, assetId)
                .orElseThrow(() -> new BusinessException(40401, "素材不存在"));
        if (asset.status() != AssetStatus.ACTIVE) {
            throw new BusinessException(40901, "素材不在可用状态，无法合并");
        }
        if (asset.scope() != AssetScope.PROJECT) {
            throw new BusinessException(40001, "重复合并只支持项目素材");
        }
        return asset;
    }
}
