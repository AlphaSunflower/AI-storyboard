package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.CreateGroupRequest;
import com.storyboard.asset.config.AssetProperties;
import com.storyboard.asset.domain.MediaValidation;
import com.storyboard.asset.domain.FolderPath;
import com.storyboard.asset.dto.ImportContracts.Entry;
import com.storyboard.asset.dto.ImportContracts.ImportJobStatus;
import com.storyboard.asset.dto.ImportContracts.ImportMode;
import com.storyboard.asset.dto.ImportContracts.ItemResult;
import com.storyboard.asset.dto.ImportContracts.ScanRequest;
import com.storyboard.asset.dto.ImportContracts.ScanResponse;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.GroupService;
import com.storyboard.asset.service.ImportService;
import com.storyboard.asset.service.LibraryFolderService;
import com.storyboard.asset.service.MediaUploadService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ImportServiceImpl implements ImportService {

    private static final int MAX_FILES = 10_000;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024;

    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final MediaUploadService uploadService;
    private final AssetCatalogService catalogService;
    private final GroupService groupService;
    private final LibraryFolderService folderService;
    private final ObjectStorage storage;
    private final String signingSecret;

    public ImportServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            MediaUploadService uploadService,
            AssetCatalogService catalogService,
            GroupService groupService,
            LibraryFolderService folderService,
            ObjectStorage storage,
            AssetProperties properties
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.uploadService = uploadService;
        this.catalogService = catalogService;
        this.groupService = groupService;
        this.folderService = folderService;
        this.storage = storage;
        this.signingSecret = properties.signingSecret() == null ? "" : properties.signingSecret();
    }

    @Override
    @Transactional
    public ScanResponse scan(String userId, ScanRequest request) {
        if (request == null || request.entries() == null || request.entries().isEmpty()) {
            throw new BusinessException(40001, "目录清单为空");
        }
        if (request.entries().size() > MAX_FILES) throw new BusinessException(40001, "单次最多导入 10000 个文件");
        ImportMode mode = request.mode() == null ? ImportMode.TREE : request.mode();
        if (request.projectId() == null || request.projectId().isBlank()) {
            throw new BusinessException(40001, "文件夹导入必须选择项目");
        }
        AssetScope scope = AssetScope.PROJECT;
        if (scope == AssetScope.PROJECT) projectAccess.requireOwned(userId, request.projectId());
        // 资产组仍只收图片；ASSETS/TREE 模式收录任意类型文件。
        boolean imagesOnly = mode == ImportMode.GROUP;
        List<Entry> importable = imagesOnly
                ? request.entries().stream().filter(this::isImage).toList()
                : request.entries();
        long totalBytes = importable.stream().mapToLong(Entry::sizeBytes).sum();
        if (totalBytes > MAX_TOTAL_BYTES) throw new BusinessException(40001, "目录总大小超过 20GB");
        for (Entry entry : importable) {
            MediaValidation.validateSize(entryType(entry), entry.sizeBytes());
            MediaValidation.cleanRelativePath(entry.relativePath());
        }
        String canonical = request.entries().stream()
                .map(entry -> entry.itemKey() + "|" + entry.relativePath() + "|" + entry.sizeBytes())
                .reduce("", (a, b) -> a + "\n" + b);
        String manifestHash = LibraryRepository.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        String confirmationToken = LibraryRepository.sha256Hex(
                (manifestHash + "|" + userId + "|" + scope + "|" + signingSecret).getBytes(StandardCharsets.UTF_8));
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(15);
        String groupId = null;
        if (mode == ImportMode.GROUP) {
            String groupName = request.groupName() == null || request.groupName().isBlank()
                    ? "文件夹资产组" : request.groupName().trim();
            groupId = groupService.create(
                    userId,
                    new CreateGroupRequest(userId, request.projectId(), AssetScope.PROJECT, groupName, null),
                    "folder-import:" + manifestHash);
        }
        String jobId = repository.createImportJob(
                userId, request.projectId(), scope, mode, groupId, manifestHash, confirmationToken,
                expiresAt, importable.size(), totalBytes, request.entries().size() - importable.size());
        for (int index = 0; index < importable.size(); index += 1) {
            Entry entry = importable.get(index);
            repository.insertImportItem(
                    jobId, entry.itemKey(), MediaValidation.cleanRelativePath(entry.relativePath()),
                    entry.fileName(), entry.sizeBytes(), entry.mimeType(), index);
        }
        return new ScanResponse(
                jobId, confirmationToken, importable.size(), totalBytes,
                request.entries().size() - importable.size(), groupId, expiresAt);
    }

    @Override
    @Transactional
    public ItemResult uploadItem(
            String userId, String jobId, String itemKey, String confirmationToken, MultipartFile file
    ) {
        LibraryRepository.ImportJob job = repository.findImportJob(userId, jobId)
                .orElseThrow(() -> new BusinessException(40401, "导入任务不存在"));
        if (!job.confirmationHash().equals(confirmationToken)) throw new BusinessException(40301, "导入确认令牌无效");
        if (job.confirmationExpiresAt().isBefore(OffsetDateTime.now())) throw new BusinessException(40901, "导入确认已过期");
        LibraryRepository.ImportItem item = repository.findImportItem(jobId, itemKey);
        if (!"PENDING".equals(item.status()) && !"FAILED".equals(item.status())) {
            return new ItemResult(item.status(), item.assetId() == null ? null
                    : catalogService.get(userId, item.assetId()).asset(), "导入项已处理");
        }
        try {
            byte[] bytes = file.getBytes();
            String mediaType = entryType(item.mimeType(), item.fileName());
            MediaValidation.validateSize(mediaType, bytes.length);
            MediaValidation.validateMagic(mediaType, bytes);
            String sha256 = LibraryRepository.sha256Hex(bytes);
            var existingBlob = repository.findBlobBySha(sha256);
            if (existingBlob.isPresent()) {
                String existingAsset = repository.findAssetIdByBlobAndOwner(userId, existingBlob.get().id()).orElse(null);
                if (existingAsset != null) {
                    repairExistingMedia(existingBlob.get(), existingAsset, bytes, item.mimeType());
                    linkImportedAsset(job, item, existingAsset);
                    repository.finishImportItem(item.id(), "DUPLICATE_LINKED", existingAsset, sha256, null);
                    return new ItemResult("DUPLICATE_LINKED", catalogService.get(userId, existingAsset).asset(), "已关联相同文件");
                }
            }
            AssetSummary asset = uploadService.uploadDirect(
                    userId, job.projectId(), job.scope(), item.fileName(), null,
                    item.relativePath(), file, "import:" + jobId + ":" + itemKey);
            linkImportedAsset(job, item, asset.id());
            repository.finishImportItem(item.id(), "REGISTERED", asset.id(), sha256, null);
            return new ItemResult("REGISTERED", asset, null);
        } catch (Exception e) {
            repository.finishImportItem(item.id(), "FAILED", null, item.sha256(), trim(e.getMessage()));
            throw e instanceof BusinessException business ? business
                    : new BusinessException(50201, "导入文件失败", e);
        }
    }

    @Override
    public ImportJobStatus getJob(String userId, String jobId) {
        LibraryRepository.ImportJob job = repository.findImportJob(userId, jobId)
                .orElseThrow(() -> new BusinessException(40401, "导入任务不存在"));
        return new ImportJobStatus(
                job.id(), job.status(), job.projectId(), job.scope(), job.mode(), job.groupId(),
                job.totalCount(), job.processedCount(), job.succeededCount(), job.duplicateCount(),
                job.failedCount(), null, job.updatedAt());
    }

    private boolean isImage(Entry entry) {
        return entry != null && entry.mimeType() != null && entry.mimeType().startsWith("image/");
    }

    private static String entryType(Entry entry) {
        return entry == null ? "OTHER" : entryType(entry.mimeType(), entry.fileName());
    }

    private static String entryType(String mimeType, String fileName) {
        return MediaValidation.mediaType(mimeType, fileName);
    }

    private void repairExistingMedia(
            LibraryRepository.BlobRow blob,
            String assetId,
            byte[] bytes,
            String mimeType
    ) {
        if (!storage.exists(blob.objectKey())) {
            storage.put(
                    blob.objectKey(),
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    mimeType
            );
        }
        repository.updateCover(assetId, "/api/files/" + blob.objectKey());
    }

    private void linkImportedAsset(
            LibraryRepository.ImportJob job,
            LibraryRepository.ImportItem item,
            String assetId
    ) {
        if (job.scope() == AssetScope.PROJECT) {
            repository.linkProject(job.ownerUserId(), assetId, job.projectId());
        }
        if (job.mode() == ImportMode.TREE) {
            String folderId = folderService.ensurePath(
                    job.ownerUserId(),
                    job.scope(),
                    job.projectId(),
                    FolderPath.folderSegments(item.relativePath())
            );
            if (folderId != null && !folderId.isBlank()) {
                repository.assignAssetsToFolder(
                        job.ownerUserId(),
                        job.scope(),
                        job.projectId(),
                        folderId,
                        List.of(assetId)
                );
            }
            return;
        }
        if (job.mode() != ImportMode.GROUP || job.groupId() == null || job.groupId().isBlank()) return;
        repository.findPrimaryMediaId(assetId).ifPresent(mediaId ->
                repository.linkGroupItem(job.groupId(), mediaId, assetId, item.sortOrder()));
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return "导入失败";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
