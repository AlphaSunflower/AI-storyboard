package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.CreateFolderRequest;
import com.storyboard.asset.client.AssetContracts.CopyFoldersRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.FolderTreeResponse;
import com.storyboard.asset.client.AssetContracts.LibraryFolderNode;
import com.storyboard.asset.client.AssetContracts.MoveAssetsToFolderRequest;
import com.storyboard.asset.client.AssetContracts.MoveFolderRequest;
import com.storyboard.asset.client.AssetContracts.UpdateFolderRequest;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.LibraryFolderService;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class LibraryFolderServiceImpl implements LibraryFolderService {

    private static final int MAX_BULK_ASSETS = 500;

    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final AssetCatalogService catalogService;

    public LibraryFolderServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            AssetCatalogService catalogService
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.catalogService = catalogService;
    }

    @Override
    @Transactional
    public LibraryFolderNode create(String userId, CreateFolderRequest request) {
        if (request == null) throw new BusinessException(40001, "目录参数不能为空");
        AssetScope scope = resolveScope(request.scope(), request.ownerProjectId());
        String name = normalizeName(request.name());
        String projectId = requireProject(scope, request.ownerProjectId());
        requireScopeAccess(userId, scope, projectId);
        if (request.parentId() != null && !request.parentId().isBlank()) {
            LibraryRepository.FolderRow parent = repository.findFolder(userId, request.parentId())
                    .orElseThrow(() -> new BusinessException(40401, "父目录不存在"));
            if (!sameScope(parent, scope, projectId)) {
                throw new BusinessException(40001, "父目录不属于当前项目");
            }
        }
        String normalized = normalizedName(name);
        var existing = repository.findFolderBySibling(userId, scope, projectId, blankToNull(request.parentId()), normalized);
        String effectiveName = existing.isPresent()
                ? uniqueFolderName(userId, scope, projectId, blankToNull(request.parentId()), name)
                : name;
        String effectiveNormalized = normalizedName(effectiveName);
        String folderId = repository.insertFolder(
                userId, scope, projectId, blankToNull(request.parentId()), effectiveName, effectiveNormalized);
        var created = repository.findFolder(userId, folderId)
                .orElseThrow(() -> new BusinessException(50000, "目录创建失败"));
        return toNode(created, 0, 0);
    }

    @Override
    @Transactional
    public LibraryFolderNode rename(String userId, String folderId, UpdateFolderRequest request) {
        if (request == null) throw new BusinessException(40001, "目录参数不能为空");
        LibraryRepository.FolderRow folder = requireFolder(userId, folderId);
        String name = normalizeName(request.name());
        String normalized = normalizedName(name);
        var duplicate = repository.findFolderBySibling(
                userId, folder.scope(), folder.ownerProjectId(), folder.parentId(), normalized);
        if (duplicate.isPresent() && !duplicate.get().id().equals(folderId)) {
            throw new BusinessException(40901, "同层已存在同名目录");
        }
        long expectedVersion = request.expectedVersion() == null ? folder.version() : request.expectedVersion();
        if (!repository.renameFolder(userId, folderId, name, normalized, expectedVersion)) {
            throw new BusinessException(40901, "目录已更新，请刷新后重试");
        }
        return toNode(requireFolder(userId, folderId), repository.countFolderAssets(folderId), 0);
    }

    @Override
    @Transactional
    public void delete(String userId, String folderId, long expectedVersion) {
        LibraryRepository.FolderRow folder = requireFolder(userId, folderId);
        if (folder.version() != expectedVersion) {
            throw new BusinessException(40901, "目录已更新，请刷新后重试");
        }
        List<String> folderIds = repository.listFolderTreeIds(userId, folderId);
        if (folderIds.isEmpty()) throw new BusinessException(40401, "目录不存在");
        repository.trashAssetsInFolders(userId, folder.scope(), folder.ownerProjectId(), folderIds);
        repository.deleteAssetFolderMappingsForFolders(userId, folderIds);
        for (String id : folderIds) repository.deleteFolderRow(userId, id);
    }

    @Override
    public FolderTreeResponse tree(String userId, AssetScope requestedScope, String projectId) {
        AssetScope scope = resolveScope(requestedScope, projectId);
        String normalizedProjectId = requireProject(scope, projectId);
        requireScopeAccess(userId, scope, normalizedProjectId);
        return new FolderTreeResponse(
                scope,
                normalizedProjectId,
                repository.listFolders(userId, scope, normalizedProjectId),
                repository.countUnfiledAssets(userId, scope, normalizedProjectId)
        );
    }

    @Override
    @Transactional
    public void moveAssets(String userId, MoveAssetsToFolderRequest request) {
        if (request == null || request.assetIds() == null || request.assetIds().isEmpty()) {
            throw new BusinessException(40001, "请选择要移动的素材");
        }
        List<String> assetIds = request.assetIds().stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (assetIds.isEmpty()) throw new BusinessException(40001, "请选择要移动的素材");
        if (assetIds.size() > MAX_BULK_ASSETS) throw new BusinessException(40001, "单次最多移动 500 项素材");
        AssetScope scope = resolveScope(request.scope(), request.ownerProjectId());
        String projectId = requireProject(scope, request.ownerProjectId());
        requireScopeAccess(userId, scope, projectId);
        if (request.folderId() != null && !request.folderId().isBlank()) {
            LibraryRepository.FolderRow target = repository.findFolder(userId, request.folderId())
                    .orElseThrow(() -> new BusinessException(40401, "目录不存在"));
            if (!sameScope(target, scope, projectId)) throw new BusinessException(40401, "目录不存在");
        }
        for (String assetId : assetIds) {
            AssetSummary asset = repository.findAsset(userId, assetId)
                    .orElseThrow(() -> new BusinessException(40401, "资产不存在"));
            if (asset.status() != com.storyboard.asset.client.AssetContracts.AssetStatus.ACTIVE) {
                throw new BusinessException(40901, "只能移动未删除的素材");
            }
            if (!belongsToScope(asset, scope, projectId)) {
                throw new BusinessException(40001, "所选素材不属于当前目录范围");
            }
        }
        repository.assignAssetsToFolder(
                userId, scope, projectId, blankToNull(request.folderId()), assetIds);
    }

    @Override
    @Transactional
    public void moveFolder(String userId, String folderId, MoveFolderRequest request) {
        LibraryRepository.FolderRow folder = requireFolder(userId, folderId);
        String parentId = request == null ? null : blankToNull(request.parentId());
        if (java.util.Objects.equals(folder.parentId(), parentId)) return;

        if (parentId != null) {
            LibraryRepository.FolderRow parent = requireFolder(userId, parentId);
            if (!sameScope(parent, folder.scope(), folder.ownerProjectId())) {
                throw new BusinessException(40001, "目标文件夹不属于当前项目");
            }
            if (parent.id().equals(folder.id())
                    || repository.listFolderTreeIds(userId, folder.id()).contains(parent.id())) {
                throw new BusinessException(40001, "不能将文件夹移动到自身或子文件夹中");
            }
        }

        var duplicate = repository.findFolderBySibling(
                userId,
                folder.scope(),
                folder.ownerProjectId(),
                parentId,
                folder.normalizedName()
        );
        if (duplicate.isPresent() && !duplicate.get().id().equals(folder.id())) {
            throw new BusinessException(40901, "目标位置已存在同名文件夹");
        }

        long expectedVersion = request == null || request.expectedVersion() == null
                ? folder.version()
                : request.expectedVersion();
        if (!repository.moveFolder(userId, folder.id(), parentId, expectedVersion)) {
            throw new BusinessException(40901, "文件夹已更新，请刷新后重试");
        }
    }

    @Override
    @Transactional
    public com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult copyFolders(
            String userId,
            CopyFoldersRequest request
    ) {
        if (request == null || request.folderIds() == null || request.folderIds().isEmpty()) {
            throw new BusinessException(40001, "请选择要复制的文件夹");
        }
        List<String> folderIds = request.folderIds().stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (folderIds.isEmpty()) throw new BusinessException(40001, "请选择要复制的文件夹");
        if (folderIds.size() > MAX_BULK_ASSETS) throw new BusinessException(40001, "单次最多复制 500 个文件夹");

        String parentId = blankToNull(request.parentId());
        LibraryRepository.FolderRow parent = null;
        if (parentId != null) parent = requireFolder(userId, parentId);

        List<String> copied = new java.util.ArrayList<>();
        for (String folderId : folderIds) {
            LibraryRepository.FolderRow source = requireFolder(userId, folderId);
            if (parent != null && !sameScope(parent, source.scope(), source.ownerProjectId())) {
                throw new BusinessException(40001, "不能复制到其他项目的文件夹");
            }
            copied.add(copyFolderRecursive(userId, source, parentId));
        }
        return new com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult(
                List.copyOf(copied), List.of());
    }

    @Override
    @Transactional
    public String ensurePath(
            String userId,
            AssetScope requestedScope,
            String projectId,
            List<String> segments
    ) {
        AssetScope scope = resolveScope(requestedScope, projectId);
        String normalizedProjectId = requireProject(scope, projectId);
        requireScopeAccess(userId, scope, normalizedProjectId);
        String parentId = null;
        for (String raw : segments == null ? List.<String>of() : segments) {
            String name = normalizeName(raw);
            String normalized = normalizedName(name);
            String currentParentId = parentId;
            var existing = repository.findFolderBySibling(
                    userId, scope, normalizedProjectId, currentParentId, normalized);
            parentId = existing.map(LibraryRepository.FolderRow::id)
                    .orElseGet(() -> repository.insertFolder(
                            userId, scope, normalizedProjectId, currentParentId, name, normalized));
        }
        return parentId;
    }

    private LibraryRepository.FolderRow requireFolder(String userId, String folderId) {
        return repository.findFolder(userId, folderId)
                .orElseThrow(() -> new BusinessException(40401, "目录不存在"));
    }

    private String copyFolderRecursive(
            String userId,
            LibraryRepository.FolderRow source,
            String targetParentId
    ) {
        String name = uniqueFolderName(
                userId, source.scope(), source.ownerProjectId(), targetParentId, source.name());
        String copyId = repository.insertFolder(
                userId,
                source.scope(),
                source.ownerProjectId(),
                targetParentId,
                name,
                normalizedName(name)
        );
        List<String> assetIds = repository.listDirectFolderAssetIds(userId, source.id());
        if (!assetIds.isEmpty()) {
            catalogService.duplicate(userId, new DuplicateAssetsRequest(userId, assetIds, copyId));
        }
        for (LibraryRepository.FolderRow child : repository.listChildFolders(userId, source.id())) {
            copyFolderRecursive(userId, child, copyId);
        }
        return copyId;
    }

    private String uniqueFolderName(
            String userId,
            AssetScope scope,
            String projectId,
            String parentId,
            String baseName
    ) {
        String candidate = baseName + "（1）";
        int suffix = 2;
        while (repository.findFolderBySibling(
                userId,
                scope,
                projectId,
                parentId,
                normalizedName(candidate)
        ).isPresent()) {
            candidate = baseName + "（" + suffix + "）";
            suffix += 1;
        }
        return candidate;
    }

    private void requireScopeAccess(String userId, AssetScope scope, String projectId) {
        if (scope == AssetScope.PROJECT) projectAccess.requireOwned(userId, projectId);
    }

    private static LibraryFolderNode toNode(
            LibraryRepository.FolderRow row,
            long directAssetCount,
            long descendantAssetCount
    ) {
        return new LibraryFolderNode(
                row.id(), row.parentId(), row.name(), row.scope(), row.ownerProjectId(),
                directAssetCount, descendantAssetCount, row.version());
    }

    private static boolean sameScope(
            LibraryRepository.FolderRow row,
            AssetScope scope,
            String projectId
    ) {
        return row.scope() == scope && java.util.Objects.equals(row.ownerProjectId(), projectId);
    }

    private static boolean belongsToScope(AssetSummary asset, AssetScope scope, String projectId) {
        if (scope == AssetScope.GLOBAL) return asset.scope() == AssetScope.GLOBAL;
        if (java.util.Objects.equals(asset.ownerProjectId(), projectId)) return true;
        return asset.projects().stream().anyMatch(link -> java.util.Objects.equals(link.projectId(), projectId));
    }

    private static AssetScope resolveScope(AssetScope scope, String projectId) {
        if (scope != null) return scope;
        return projectId == null || projectId.isBlank() ? AssetScope.GLOBAL : AssetScope.PROJECT;
    }

    private static String requireProject(AssetScope scope, String projectId) {
        String normalized = blankToNull(projectId);
        if (scope == AssetScope.PROJECT && normalized == null) {
            throw new BusinessException(40001, "项目目录必须指定项目");
        }
        return scope == AssetScope.PROJECT ? normalized : null;
    }

    private static String normalizeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw new BusinessException(40001, "目录名称不能为空");
        if (name.length() > 255) throw new BusinessException(40001, "目录名称不能超过 255 个字符");
        return name;
    }

    private static String normalizedName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
