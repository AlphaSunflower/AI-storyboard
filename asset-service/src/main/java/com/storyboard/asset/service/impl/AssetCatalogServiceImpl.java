package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.AiAssetKind;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;
import com.storyboard.asset.domain.AssetStatePolicy;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.AssetCatalogService;
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

    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final MediaUrlSigner signer;

    public AssetCatalogServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            MediaUrlSigner signer
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.signer = signer;
    }

    @Override
    public PageResponse<AssetSummary> list(
            String userId, AssetScope scope, String projectId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode, boolean deleted, int page, int size
    ) {
        if (scope == AssetScope.PROJECT && projectId != null && !projectId.isBlank()) {
            projectAccess.requireOwned(userId, projectId);
        }
        return repository.searchAssets(userId, scope, projectId, query, type, tagIds,
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
        return requireAsset(userId, assetId);
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
        boolean updated = repository.trash(userId, assetId, expectedVersion, OffsetDateTime.now().plusDays(7));
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
        if (repository.hasActiveReferences(assetId)) {
            repository.markPurgeBlocked(assetId);
            throw new BusinessException(40901, "资产仍被项目引用，已转为待处理状态");
        }
        if (!repository.markPurgePending(userId, assetId)) throw new BusinessException(40901, "清理状态已变化");
        for (MediaRef media : repository.listMedia(assetId)) {
            repository.createCleanupTask(media.objectKey());
        }
        repository.markPurged(assetId);
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
        if (registration.mediaType() == com.storyboard.asset.client.AssetContracts.MediaType.IMAGE) {
            repository.createAnalysisTask(assetId);
        }
        repository.createIdempotencyKey(userId, "media-register", idempotencyKey, assetId);
        return requireAsset(userId, assetId);
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

    private MediaRef withSignedUrl(String userId, MediaRef media) {
        String token = signer.sign(media.id(), userId, Instant.now().plusSeconds(900));
        return new MediaRef(
                media.id(), media.blobId(), media.type(), media.url(),
                media.contentUrl() + "?token=" + token, media.objectKey(), media.fileName(),
                media.relativePath(), media.mimeType(), media.sizeBytes(), media.width(), media.height(),
                media.durationSeconds(), media.sortOrder(), media.role(), media.primary(), media.constraintText());
    }
}
