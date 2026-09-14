package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.CreateGroupRequest;
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
        List<com.storyboard.asset.client.AssetContracts.MediaRef> media = repository.listGroupMediaIds(userId, groupId).stream()
                .map(mediaId -> repository.findMedia(userId, mediaId).orElse(null))
                .filter(Objects::nonNull)
                .map(item -> new com.storyboard.asset.client.AssetContracts.MediaRef(
                        item.id(), item.blobId(), item.type(), item.url(),
                        item.contentUrl() + "?token=" + signer.sign(item.id(), userId, Instant.now().plusSeconds(900)),
                        item.objectKey(), item.fileName(), item.relativePath(), item.mimeType(),
                        item.sizeBytes(), item.width(), item.height(), item.durationSeconds(),
                        item.sortOrder(), item.role(), item.primary(), item.constraintText()))
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
}
