package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.config.AssetProperties;
import com.storyboard.asset.domain.MediaValidation;
import com.storyboard.asset.dto.ImportContracts.Entry;
import com.storyboard.asset.dto.ImportContracts.ItemResult;
import com.storyboard.asset.dto.ImportContracts.ScanRequest;
import com.storyboard.asset.dto.ImportContracts.ScanResponse;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ImportService;
import com.storyboard.asset.service.MediaUploadService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final String signingSecret;

    public ImportServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            MediaUploadService uploadService,
            AssetCatalogService catalogService,
            AssetProperties properties
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.uploadService = uploadService;
        this.catalogService = catalogService;
        this.signingSecret = properties.signingSecret() == null ? "" : properties.signingSecret();
    }

    @Override
    @Transactional
    public ScanResponse scan(String userId, ScanRequest request) {
        if (request == null || request.entries() == null || request.entries().isEmpty()) {
            throw new BusinessException(40001, "目录清单为空");
        }
        if (request.entries().size() > MAX_FILES) throw new BusinessException(40001, "单次最多导入 10000 个文件");
        AssetScope scope = request.scope() == null
                ? (request.projectId() == null ? AssetScope.GLOBAL : AssetScope.PROJECT)
                : request.scope();
        if (scope == AssetScope.PROJECT) projectAccess.requireOwned(userId, request.projectId());
        List<Entry> images = request.entries().stream().filter(this::isImage).toList();
        long totalBytes = images.stream().mapToLong(Entry::sizeBytes).sum();
        if (totalBytes > MAX_TOTAL_BYTES) throw new BusinessException(40001, "目录总大小超过 20GB");
        for (Entry entry : images) {
            MediaValidation.validateSize("IMAGE", entry.sizeBytes());
            MediaValidation.cleanRelativePath(entry.relativePath());
        }
        String canonical = request.entries().stream()
                .map(entry -> entry.itemKey() + "|" + entry.relativePath() + "|" + entry.sizeBytes())
                .reduce("", (a, b) -> a + "\n" + b);
        String manifestHash = LibraryRepository.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        String confirmationToken = LibraryRepository.sha256Hex(
                (manifestHash + "|" + userId + "|" + scope + "|" + signingSecret).getBytes(StandardCharsets.UTF_8));
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(15);
        String jobId = repository.createImportJob(
                userId, request.projectId(), scope, manifestHash, confirmationToken,
                expiresAt, images.size(), totalBytes, request.entries().size() - images.size());
        for (Entry entry : images) {
            repository.insertImportItem(
                    jobId, entry.itemKey(), MediaValidation.cleanRelativePath(entry.relativePath()),
                    entry.fileName(), entry.sizeBytes(), entry.mimeType());
        }
        return new ScanResponse(
                jobId, confirmationToken, images.size(), totalBytes,
                request.entries().size() - images.size(), expiresAt);
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
            MediaValidation.validateMagic("IMAGE", bytes);
            String sha256 = LibraryRepository.sha256Hex(bytes);
            var existingBlob = repository.findBlobBySha(sha256);
            if (existingBlob.isPresent()) {
                String existingAsset = repository.findAssetIdByBlobAndOwner(userId, existingBlob.get().id()).orElse(null);
                if (existingAsset != null) {
                    repository.finishImportItem(item.id(), "DUPLICATE_LINKED", existingAsset, sha256, null);
                    return new ItemResult("DUPLICATE_LINKED", catalogService.get(userId, existingAsset).asset(), "已关联相同文件");
                }
            }
            AssetSummary asset = uploadService.uploadDirect(
                    userId, job.projectId(), job.scope(), item.fileName(), null,
                    item.relativePath(), file, "import:" + jobId + ":" + itemKey);
            repository.finishImportItem(item.id(), "REGISTERED", asset.id(), sha256, null);
            return new ItemResult("REGISTERED", asset, null);
        } catch (Exception e) {
            repository.finishImportItem(item.id(), "FAILED", null, item.sha256(), trim(e.getMessage()));
            throw e instanceof BusinessException business ? business
                    : new BusinessException(50201, "导入文件失败", e);
        }
    }

    private boolean isImage(Entry entry) {
        return entry != null && entry.mimeType() != null && entry.mimeType().startsWith("image/");
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return "导入失败";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
