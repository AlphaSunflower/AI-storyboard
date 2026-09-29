package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.CreateGroupRequest;
import com.storyboard.asset.client.AssetContracts.CreateGroupFromAssetsRequest;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.ReplaceGroupItemsRequest;
import com.storyboard.asset.client.AssetContracts.SnapshotRequest;
import com.storyboard.asset.domain.GroupSnapshotExpander;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.repository.LibraryRepository.GroupItemWrite;
import com.storyboard.asset.service.GroupService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class GroupServiceImpl implements GroupService {

    private static final int MAX_BULK_ASSETS = 500;

    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final com.storyboard.asset.security.MediaUrlSigner signer;

    public GroupServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            com.storyboard.asset.security.MediaUrlSigner signer
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.signer = signer;
    }

    @Override
    @Transactional
    public String create(String userId, CreateGroupRequest request, String idempotencyKey) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(40001, "资产组名称不能为空");
        }
        var existing = repository.findIdempotencyResource(userId, "group-create", idempotencyKey);
        if (existing.isPresent()) return existing.get();
        AssetScope scope = request.scope() == null
                ? (request.ownerProjectId() == null ? AssetScope.GLOBAL : AssetScope.PROJECT)
                : request.scope();
        if (scope == AssetScope.PROJECT) projectAccess.requireOwned(userId, request.ownerProjectId());
        String assetId = repository.insertAsset(
                userId, scope, request.ownerProjectId(), AssetType.GROUP, null,
                request.name().trim(), request.description(), "group", null);
        repository.insertGroup(assetId, userId, scope, request.ownerProjectId());
        if (scope == AssetScope.PROJECT) repository.linkProject(userId, assetId, request.ownerProjectId());
        repository.createIdempotencyKey(userId, "group-create", idempotencyKey, assetId);
        return assetId;
    }

    @Override
    @Transactional
    public AssetSummary createFromAssets(
            String userId,
            CreateGroupFromAssetsRequest request,
            String idempotencyKey
    ) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(40001, "资产组名称不能为空");
        }
        if (request.ownerProjectId() == null || request.ownerProjectId().isBlank()) {
            throw new BusinessException(40001, "项目资产组必须指定项目");
        }
        var existing = repository.findIdempotencyResource(userId, "group-from-assets", idempotencyKey);
        if (existing.isPresent()) {
            return repository.findAsset(userId, existing.get())
                    .orElseThrow(() -> new BusinessException(40401, "资产组不存在"));
        }
        List<String> assetIds = request.assetIds() == null ? List.of() : request.assetIds().stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (assetIds.isEmpty()) throw new BusinessException(40001, "请选择要合并的素材");
        if (assetIds.size() > MAX_BULK_ASSETS) throw new BusinessException(40001, "单次最多合并 500 项素材");
        projectAccess.requireOwned(userId, request.ownerProjectId());
        List<AssetSummary> assets = assetIds.stream()
                .map(id -> repository.findAsset(userId, id)
                        .orElseThrow(() -> new BusinessException(40401, "资产不存在")))
                .toList();
        if (assets.stream().anyMatch(asset -> asset.status() != AssetStatus.ACTIVE)) {
            throw new BusinessException(40901, "所选素材中包含不可用项");
        }
        if (assets.stream().anyMatch(asset -> !belongsToProject(asset, request.ownerProjectId()))) {
            throw new BusinessException(40001, "只能合并同一项目中的素材");
        }
        if (assets.stream().anyMatch(asset -> asset.type() != AssetType.IMAGE
                && asset.type() != AssetType.VIDEO
                && asset.type() != AssetType.AUDIO)) {
            throw new BusinessException(40001, "资产组只允许图片、视频或音频素材");
        }
        String groupId = repository.insertAsset(
                userId,
                AssetScope.PROJECT,
                request.ownerProjectId(),
                AssetType.GROUP,
                null,
                request.name().trim(),
                null,
                "group-from-assets",
                null
        );
        repository.insertGroup(groupId, userId, AssetScope.PROJECT, request.ownerProjectId());
        repository.linkProject(userId, groupId, request.ownerProjectId());
        for (int index = 0; index < assets.size(); index += 1) {
            AssetSummary asset = assets.get(index);
            String mediaId = repository.findPrimaryMediaId(asset.id())
                    .orElseThrow(() -> new BusinessException(40001, "素材缺少可用媒体文件"));
            repository.linkGroupItem(groupId, mediaId, asset.id(), index);
        }
        repository.createIdempotencyKey(userId, "group-from-assets", idempotencyKey, groupId);
        return repository.findAsset(userId, groupId)
                .orElseThrow(() -> new BusinessException(50000, "资产组创建失败"));
    }

    @Override
    @Transactional
    public void replaceItems(String userId, String groupId, ReplaceGroupItemsRequest request, long expectedVersion) {
        var group = repository.findGroup(userId, groupId)
                .orElseThrow(() -> new BusinessException(40401, "资产组不存在"));
        if (group.version() != expectedVersion) throw new BusinessException(40901, "资产组已更新，请刷新后重试");
        List<GroupItemWrite> items = request == null || request.items() == null ? List.of() : request.items().stream()
                .map(item -> new GroupItemWrite(
                        item.mediaId(),
                        item.sortOrder() == null ? 0 : item.sortOrder(),
                        (item.role() == null ? MediaRole.REFERENCE : item.role()).name()))
                .toList();
        repository.replaceGroupItems(userId, groupId, items);
    }

    @Override
    @Transactional
    public AssetGroupSnapshot createSnapshot(String userId, String groupId, SnapshotRequest request) {
        var group = repository.findGroup(userId, groupId)
                .orElseThrow(() -> new BusinessException(40401, "资产组不存在"));
        int maxImages = request == null || request.maxImages() == null ? 9 : request.maxImages();
        int maxVideos = request == null || request.maxVideos() == null ? 3 : request.maxVideos();
        int maxAudios = request == null || request.maxAudios() == null ? 3 : request.maxAudios();
        List<GroupSnapshotExpander.Item> expanded = GroupSnapshotExpander.expand(
                repository.listGroupItems(userId, groupId), maxImages, maxVideos, maxAudios);
        String canonical = expanded.stream()
                .map(item -> item.assetId() + ":" + item.mediaId() + ":" + item.blobId() + ":"
                        + item.role() + ":" + item.sortOrder())
                .reduce("", (a, b) -> a + "|" + b);
        String contentHash = LibraryRepository.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        String target = request == null || request.target() == null ? "VIDEO" : request.target().toUpperCase();
        String snapshotId = repository.upsertSnapshot(userId, groupId, group.version(), contentHash, target);
        repository.replaceSnapshotItems(snapshotId, expanded);
        return repository.findSnapshot(userId, snapshotId)
                .map(row -> new AssetGroupSnapshot(
                        row.id(), row.groupId(), row.groupVersion(), row.contentHash(), row.target(),
                        signItems(userId, repository.listSnapshotItems(row.id())), row.createdAt()))
                .orElseThrow(() -> new BusinessException(50000, "快照创建失败"));
    }

    @Override
    public AssetGroupSnapshot getSnapshot(String userId, String snapshotId) {
        var row = repository.findSnapshot(userId, snapshotId)
                .orElseThrow(() -> new BusinessException(40401, "资产组快照不存在"));
        return new AssetGroupSnapshot(
                row.id(), row.groupId(), row.groupVersion(), row.contentHash(), row.target(),
                signItems(userId, repository.listSnapshotItems(row.id())), row.createdAt());
    }

    @Override
    public com.storyboard.asset.dto.AssetGroupDetailResponse get(String userId, String groupId) {
        var group = repository.findGroup(userId, groupId)
                .orElseThrow(() -> new BusinessException(40401, "资产组不存在"));
        List<com.storyboard.asset.dto.AssetGroupDetailResponse.AssetGroupMediaItem> media =
                repository.listGroupItems(userId, groupId).stream()
                .map(item -> {
                    var mediaRef = repository.findMedia(userId, item.mediaId()).orElse(null);
                    if (mediaRef == null) return null;
                    AssetSummary member = repository.findAsset(userId, item.assetId()).orElse(null);
                    var signed = new com.storyboard.asset.client.AssetContracts.MediaRef(
                            mediaRef.id(), mediaRef.blobId(), mediaRef.type(), mediaRef.url(),
                            mediaRef.contentUrl() + "?token=" + signer.sign(
                                    mediaRef.id(), userId, Instant.now().plusSeconds(900)),
                            mediaRef.objectKey(), mediaRef.fileName(), mediaRef.relativePath(),
                            mediaRef.mimeType(), mediaRef.sizeBytes(), mediaRef.width(), mediaRef.height(),
                            mediaRef.durationSeconds(), mediaRef.sortOrder(), mediaRef.role(),
                            mediaRef.primary(), mediaRef.constraintText());
                    return new com.storyboard.asset.dto.AssetGroupDetailResponse.AssetGroupMediaItem(
                            signed,
                            member,
                            item.assetId(),
                            member == null ? null : member.name(),
                            member == null ? null : member.description(),
                            member == null ? 0 : member.version(),
                            member == null ? List.of() : member.tags());
                })
                .filter(Objects::nonNull)
                .toList();
        return new com.storyboard.asset.dto.AssetGroupDetailResponse(
                group.id(), group.name(), group.description(), group.version(), media);
    }

    private List<com.storyboard.asset.client.AssetContracts.SnapshotItem> signItems(
            String userId,
            List<com.storyboard.asset.client.AssetContracts.SnapshotItem> items
    ) {
        return items.stream().map(item -> new com.storyboard.asset.client.AssetContracts.SnapshotItem(
                item.assetId(), item.mediaId(), item.blobId(), item.objectKey(),
                item.contentUrl() + "?token=" + signer.sign(item.mediaId(), userId, Instant.now().plusSeconds(900)),
                item.type(), item.role(), item.sortOrder())).toList();
    }

    private static boolean belongsToProject(AssetSummary asset, String projectId) {
        if (java.util.Objects.equals(asset.ownerProjectId(), projectId)) return true;
        return asset.projects().stream()
                .anyMatch(link -> java.util.Objects.equals(link.projectId(), projectId));
    }
}
