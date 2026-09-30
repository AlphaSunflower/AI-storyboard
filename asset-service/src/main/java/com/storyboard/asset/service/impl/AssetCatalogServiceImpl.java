package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.AiAssetKind;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.domain.AssetStatePolicy;
import com.storyboard.asset.domain.AssetSearchText;
import com.storyboard.asset.dto.ImportContracts.AnalysisTaskStatus;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.AssetPurgeService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Service
public class AssetCatalogServiceImpl implements AssetCatalogService {

    private static final int MAX_BULK_ASSETS = 500;

    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final MediaUrlSigner signer;
    private final AssetPurgeService purgeService;
    private final StorageProperties properties;

    public AssetCatalogServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            MediaUrlSigner signer,
            AssetPurgeService purgeService,
            StorageProperties properties
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.signer = signer;
        this.purgeService = purgeService;
        this.properties = properties;
    }

    @Override
    public PageResponse<AssetSummary> list(
            String userId, AssetScope scope, String projectId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode, boolean deleted, int page, int size
    ) {
        return list(userId, scope, projectId, null, query, type, tagIds, tagMode, deleted, page, size);
    }

    @Override
    public PageResponse<AssetSummary> list(
            String userId, AssetScope scope, String projectId, String folderId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode, boolean deleted, int page, int size
    ) {
        if (scope == AssetScope.PROJECT && projectId != null && !projectId.isBlank()) {
            projectAccess.requireOwned(userId, projectId);
        }
        return repository.searchAssets(userId, scope, projectId, folderId, query, type, tagIds,
                tagMode == null ? TagMode.ALL : tagMode, deleted, page, size);
    }

    @Override
    public AssetDetail get(String userId, String assetId) {
        AssetSummary asset = requireAsset(userId, assetId);
        List<MediaRef> media = repository.listMedia(assetId).stream()
                .map(item -> withSignedUrl(userId, item))
                .toList();
        return new AssetDetail(asset, media, asset.projects());
    }

    @Override
    public AssetDetail findBySource(String userId, String sourceType, String sourceId) {
        AssetSummary asset = repository.findAssetBySource(userId, sourceType, sourceId)
                .orElseThrow(() -> new BusinessException(40401, "来源媒体不存在"));
        List<MediaRef> media = repository.listMedia(asset.id()).stream()
                .map(item -> withSignedUrl(userId, item))
                .toList();
        return new AssetDetail(asset, media, asset.projects());
    }

    @Override
    public MediaRef getMedia(String userId, String mediaId) {
        MediaRef media = repository.findMedia(userId, mediaId)
                .orElseThrow(() -> new BusinessException(40401, "媒体不存在"));
        return withSignedUrl(userId, media);
    }

    @Override
    @Transactional
    public AssetSummary create(String actorUserId, CreateAssetRequest request, String idempotencyKey) {
        validateCreate(actorUserId, request);
        var existing = repository.findIdempotencyResource(actorUserId, "asset-create", idempotencyKey);
        if (existing.isPresent()) return requireAsset(actorUserId, existing.get());
        AssetScope scope = request.ownerProjectId() == null ? AssetScope.GLOBAL : AssetScope.PROJECT;
        if (scope == AssetScope.PROJECT) projectAccess.requireOwned(actorUserId, request.ownerProjectId());
        AssetType type = parseType(request.type());
        AiAssetKind aiKind = request.aiKind() == null ? null : AiAssetKind.valueOf(request.aiKind().toUpperCase());
        String assetId = repository.insertAsset(
                actorUserId, scope, request.ownerProjectId(), type, aiKind,
                request.name().trim(), trimToNull(request.description()), request.sourceType(), null);
        if (scope == AssetScope.PROJECT) repository.linkProject(actorUserId, assetId, request.ownerProjectId());
        repository.createIdempotencyKey(actorUserId, "asset-create", idempotencyKey, assetId);
        return requireAsset(actorUserId, assetId);
    }

    @Override
    @Transactional
    public AssetSummary update(String userId, String assetId, UpdateAssetRequest request) {
        requireAsset(userId, assetId);
        if (request.expectedVersion() != null) {
            boolean updated = repository.updateAsset(
                    userId, assetId, trimToNull(request.name()), trimToNull(request.description()),
                    trimToNull(request.aiDescription()), Boolean.TRUE.equals(request.manualOverride()),
                    request.expectedVersion());
            if (!updated) throw new BusinessException(40901, "资产已被其他操作修改，请刷新后重试");
        } else {
            AssetSummary current = requireAsset(userId, assetId);
            boolean updated = repository.updateAsset(
                    userId, assetId, trimToNull(request.name()), trimToNull(request.description()),
                    trimToNull(request.aiDescription()), Boolean.TRUE.equals(request.manualOverride()),
                    current.version());
            if (!updated) throw new BusinessException(40901, "资产已被其他操作修改，请刷新后重试");
        }
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public AssetSummary promote(String userId, String assetId, long expectedVersion) {
        requireAsset(userId, assetId);
        if (!repository.promote(userId, assetId, expectedVersion)) {
            throw new BusinessException(40901, "资产版本已变化，无法提升到全局库");
        }
        repository.removeAssetFolderMappings(userId, assetId);
        repository.unlinkAllProjects(assetId);
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public BulkAssetOperationResult bulkPromote(String userId, Collection<String> assetIds) {
        List<AssetSummary> assets = validateBulkAssets(userId, assetIds);
        List<String> updated = new java.util.ArrayList<>(assets.size());
        for (AssetSummary asset : assets) {
            if (asset.scope() == AssetScope.GLOBAL) continue;
            if (!repository.promote(userId, asset.id(), asset.version())) {
                throw new BusinessException(40901, "资产状态已变化，批量提升已取消");
            }
            repository.removeAssetFolderMappings(userId, asset.id());
            repository.unlinkAllProjects(asset.id());
            updated.add(asset.id());
        }
        return new BulkAssetOperationResult(List.copyOf(updated), List.of());
    }

    @Override
    @Transactional
    public BulkAssetOperationResult duplicate(String userId, DuplicateAssetsRequest request) {
        if (request == null || request.assetIds() == null || request.assetIds().isEmpty()) {
            throw new BusinessException(40001, "请选择要复制的资产");
        }
        List<String> sourceIds = request.assetIds().stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (sourceIds.isEmpty()) throw new BusinessException(40001, "请选择要复制的资产");
        if (sourceIds.size() > MAX_BULK_ASSETS) throw new BusinessException(40001, "单次最多复制 500 项资产");

        var targetFolder = request.folderId() == null || request.folderId().isBlank()
                ? null
                : repository.findFolder(userId, request.folderId())
                    .orElseThrow(() -> new BusinessException(40401, "目标文件夹不存在"));

        List<String> updated = new java.util.ArrayList<>(sourceIds.size());
        List<com.storyboard.asset.client.AssetContracts.SkippedAsset> skipped = new java.util.ArrayList<>();
        for (String sourceId : sourceIds) {
            AssetSummary source = repository.findAsset(userId, sourceId)
                    .orElseThrow(() -> new BusinessException(40401, "资产不存在"));
            if (source.status() != AssetStatus.ACTIVE) {
                skipped.add(new com.storyboard.asset.client.AssetContracts.SkippedAsset(sourceId, "资产不可用"));
                continue;
            }
            if (targetFolder != null && (
                    targetFolder.scope() != source.scope()
                    || !java.util.Objects.equals(targetFolder.ownerProjectId(), source.ownerProjectId()))) {
                skipped.add(new com.storyboard.asset.client.AssetContracts.SkippedAsset(sourceId, "不能复制到其他项目文件夹"));
                continue;
            }

            String name = duplicateName(source.name());
            String searchText = AssetSearchText.build(
                    name,
                    source.description(),
                    source.aiDescription(),
                    source.tags().stream().map(tag -> tag.name()).toList()
            );
            String copyId = repository.duplicateAsset(source, name, searchText);
            repository.copyMediaLinks(source.id(), copyId);
            repository.copyAssetTags(source.id(), copyId);
            repository.copyProjectLinks(source.id(), copyId, userId);
            if (source.type() == AssetType.GROUP) repository.duplicateGroup(source.id(), copyId);
            if (targetFolder != null) {
                repository.assignAssetsToFolder(
                        userId, source.scope(), source.ownerProjectId(), targetFolder.id(), List.of(copyId));
            }
            updated.add(copyId);
        }
        return new BulkAssetOperationResult(List.copyOf(updated), List.copyOf(skipped));
    }

    @Override
    @Transactional
    public void linkProject(String userId, String assetId, String projectId) {
        AssetSummary asset = requireAsset(userId, assetId);
        projectAccess.requireOwned(userId, projectId);
        repository.linkProject(userId, assetId, projectId);
    }

    @Override
    @Transactional
    public AssetSummary trash(String userId, String assetId, long expectedVersion) {
        requireAsset(userId, assetId);
        boolean updated = repository.trash(userId, assetId, expectedVersion, OffsetDateTime.now().plusDays(properties.trashRetentionDays()));
        if (!updated) throw new BusinessException(40901, "资产版本已变化，删除失败");
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public AssetSummary restore(String userId, String assetId) {
        AssetSummary asset = requireAsset(userId, assetId);
        if (!AssetStatePolicy.canRestore(asset.status().name())) {
            throw new BusinessException(40901, "当前状态不可恢复");
        }
        if (!repository.restore(userId, assetId)) throw new BusinessException(40901, "恢复失败，请刷新后重试");
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public void purge(String userId, String assetId) {
        AssetSummary asset = requireAsset(userId, assetId);
        if (!AssetStatePolicy.canPurge(asset.status().name())) {
            throw new BusinessException(40901, "只有回收站资产可以彻底删除");
        }
        purgeService.purgeManually(assetId);
    }

    @Override
    @Transactional
    public AssetSummary registerMedia(String userId, MediaRegistration registration, String idempotencyKey) {
        var existing = repository.findIdempotencyResource(userId, "media-register", idempotencyKey);
        if (existing.isPresent()) return requireAsset(userId, existing.get());
        if (registration.scope() == AssetScope.PROJECT) {
            projectAccess.requireOwned(userId, registration.projectId());
        }
        String assetId = repository.insertAsset(
                userId, registration.scope(), registration.projectId(), registration.assetType(), null,
                registration.name().trim(), trimToNull(registration.description()),
                registration.sourceType(), registration.sourceId());
        String blobId = repository.upsertBlob(
                registration.objectKey(), registration.sha256(), registration.mimeType(),
                registration.sizeBytes(), registration.width(), registration.height(),
                registration.durationSeconds(), registration.mediaType());
        repository.upsertMedia(
                assetId, blobId, registration.sortOrder(), registration.role(),
                registration.primary(), registration.constraintText());
        String url = "/api/files/" + registration.objectKey();
        repository.updateCover(assetId, url);
        if (registration.scope() == AssetScope.PROJECT) repository.linkProject(userId, assetId, registration.projectId());
        if (registration.scope() == AssetScope.PROJECT
                && registration.mediaType() == com.storyboard.asset.client.AssetContracts.MediaType.IMAGE) {
            repository.createAnalysisTask(assetId);
        }
        repository.createIdempotencyKey(userId, "media-register", idempotencyKey, assetId);
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public BulkAssetOperationResult bulkTrash(String userId, Collection<String> assetIds) {
        List<AssetSummary> assets = validateBulkAssets(userId, assetIds);
        List<String> updated = new java.util.ArrayList<>(assets.size());
        java.time.OffsetDateTime purgeAfter = OffsetDateTime.now().plusDays(properties.trashRetentionDays());
        for (AssetSummary asset : assets) {
            if (!repository.trash(userId, asset.id(), asset.version(), purgeAfter)) {
                throw new BusinessException(40901, "资产状态已变化，批量删除已取消");
            }
            updated.add(asset.id());
        }
        return new BulkAssetOperationResult(List.copyOf(updated), List.of());
    }

    @Override
    public List<AnalysisTaskStatus> listAnalysisTaskStatuses(String userId, Collection<String> assetIds) {
        return repository.listAnalysisTaskStatuses(userId, assetIds);
    }

    private void validateCreate(String userId, CreateAssetRequest request) {
        if (userId == null || userId.isBlank()) throw new BusinessException(40001, "用户不能为空");
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(40001, "资产名称不能为空");
        }
    }

    private AssetSummary requireAsset(String userId, String assetId) {
        return repository.findAsset(userId, assetId).orElseThrow(() -> new BusinessException(40401, "资产不存在"));
    }

    private List<AssetSummary> validateBulkAssets(String userId, Collection<String> assetIds) {
        List<String> normalized = assetIds == null ? List.of() : assetIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (normalized.isEmpty()) throw new BusinessException(40001, "请选择资产");
        if (normalized.size() > MAX_BULK_ASSETS) {
            throw new BusinessException(40001, "单次最多操作 500 项资产");
        }
        List<AssetSummary> assets = normalized.stream()
                .map(id -> repository.findAsset(userId, id)
                        .orElseThrow(() -> new BusinessException(40401, "资产不存在")))
                .toList();
        if (assets.stream().anyMatch(asset -> asset.status() != AssetStatus.ACTIVE)) {
            throw new BusinessException(40901, "所选资产中包含不可操作项");
        }
        return assets;
    }

    private static AssetType parseType(String type) {
        try {
            return AssetType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            throw new BusinessException(40001, "资产类型不合法");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String duplicateName(String name) {
        String base = name == null || name.isBlank() ? "未命名素材" : name.trim();
        return base + " - 副本";
    }

    private MediaRef withSignedUrl(String userId, MediaRef media) {
        String token = signer.sign(media.id(), userId, Instant.now().plusSeconds(900));
        return new MediaRef(
                media.id(), media.blobId(), media.type(), media.url(),
                media.contentUrl() + "?token=" + token, media.objectKey(), media.fileName(),
                media.relativePath(), media.mimeType(), media.sizeBytes(), media.width(), media.height(),
                media.durationSeconds(), media.sortOrder(), media.role(), media.primary(), media.constraintText());
    }
}
