package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.domain.MediaValidation;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.MediaUploadService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.common.BusinessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class MediaUploadServiceImpl implements MediaUploadService {

    private static final int MAX_PARTS = 10_000;

    private final AssetCatalogService assetCatalogService;
    private final ProjectAccessPort projectAccess;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final LibraryRepository repository;

    public MediaUploadServiceImpl(
            AssetCatalogService assetCatalogService,
            ProjectAccessPort projectAccess,
            ObjectStorage storage,
            StorageProperties storageProperties,
            LibraryRepository repository
    ) {
        this.assetCatalogService = assetCatalogService;
        this.projectAccess = projectAccess;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.repository = repository;
    }

    @Override
    @Transactional
    public AssetSummary uploadDirect(
            String userId, String projectId, AssetScope scope, String name, String description,
            String relativePath, MultipartFile file, String idempotencyKey
    ) {
        if (file == null || file.isEmpty()) throw new BusinessException(40001, "上传文件为空");
        AssetScope safeScope = scope == null ? (projectId == null ? AssetScope.GLOBAL : AssetScope.PROJECT) : scope;
        if (safeScope == AssetScope.PROJECT) projectAccess.requireOwned(userId, projectId);
        String mediaType = MediaValidation.mediaType(file.getContentType());
        MediaValidation.validateSize(mediaType, file.getSize());
        String extension = MediaValidation.extension(file.getContentType(), file.getOriginalFilename());
        String objectKey = storage.objectKey(mediaType, extension);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(50201, "读取上传文件失败", e);
        }
        String sha256 = LibraryRepository.sha256Hex(bytes);
        MediaValidation.validateMagic(mediaType, bytes);
        if (repository.findBlobBySha(sha256).isEmpty()) {
            storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, file.getContentType());
        }
        String assetName = name == null || name.isBlank() ? file.getOriginalFilename() : name;
        return assetCatalogService.registerMedia(userId, new AssetCatalogService.MediaRegistration(
                projectId, safeScope, AssetType.valueOf(mediaType), assetName,
                description, "upload", null, objectKey, sha256, file.getContentType(),
                bytes.length, null, null, null, MediaType.valueOf(mediaType),
                MediaRole.REFERENCE, true, null, 0
        ), idempotencyKey);
    }

    @Override
    @Transactional
    public com.storyboard.asset.client.AssetContracts.MediaRef uploadToAsset(
            String userId, String assetId, MultipartFile file, String constraintText
    ) {
        var assetDetail = assetCatalogService.get(userId, assetId);
        if (file == null || file.isEmpty()) throw new BusinessException(40001, "上传文件为空");
        String mediaType = MediaValidation.mediaType(file.getContentType());
        MediaValidation.validateSize(mediaType, file.getSize());
        String extension = MediaValidation.extension(file.getContentType(), file.getOriginalFilename());
        String objectKey = storage.objectKey(mediaType, extension);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(50201, "读取上传文件失败", e);
        }
        MediaValidation.validateMagic(mediaType, bytes);
        String sha256 = LibraryRepository.sha256Hex(bytes);
        var existingBlob = repository.findBlobBySha(sha256);
        String blobId;
        String effectiveObjectKey;
        if (existingBlob.isPresent()) {
            blobId = existingBlob.get().id();
            effectiveObjectKey = existingBlob.get().objectKey();
        } else {
            storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, file.getContentType());
            blobId = repository.upsertBlob(
                    objectKey, sha256, file.getContentType(), bytes.length, null, null, null,
                    MediaType.valueOf(mediaType));
            effectiveObjectKey = objectKey;
        }
        int sortOrder = assetDetail.media().size();
        String mediaId = repository.upsertMedia(
                assetId, blobId, sortOrder, MediaRole.REFERENCE, sortOrder == 0, constraintText);
        if (sortOrder == 0) repository.updateCover(assetId, "/api/files/" + effectiveObjectKey);
        if ("IMAGE".equals(mediaType)) repository.createAnalysisTask(assetId);
        return repository.findMedia(userId, mediaId)
                .orElseThrow(() -> new BusinessException(50000, "媒体写入失败"));
    }

    @Override
    @Transactional
    public void deleteAssetMedia(String userId, String assetId, String mediaId) {
        assetCatalogService.get(userId, assetId);
        com.storyboard.asset.client.AssetContracts.MediaRef media = repository.findMedia(userId, mediaId)
                .orElseThrow(() -> new BusinessException(40401, "媒体不存在"));
        if (!assetId.equals(repository.findMediaAssetId(userId, mediaId).orElse(null))) {
            throw new BusinessException(40401, "媒体不属于该资产");
        }
        repository.deleteMedia(mediaId);
        if (repository.listMedia(assetId).isEmpty()) repository.updateCover(assetId, null);
    }

    @Override
    public MultipartSession initMultipart(
            String userId, String projectId, AssetScope scope, String fileName, String relativePath,
            String mimeType, long sizeBytes, String sha256
    ) {
        AssetScope safeScope = scope == null ? (projectId == null ? AssetScope.GLOBAL : AssetScope.PROJECT) : scope;
        if (safeScope == AssetScope.PROJECT) projectAccess.requireOwned(userId, projectId);
        String mediaType = MediaValidation.mediaType(mimeType);
        MediaValidation.validateSize(mediaType, sizeBytes);
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new BusinessException(40001, "SHA-256 不合法");
        }
        String extension = MediaValidation.extension(mimeType, fileName);
        String objectKey = storage.objectKey(mediaType, extension);
        ObjectStorage.MultipartUpload upload = storage.createMultipart(objectKey, mimeType);
        int partCount = (int) Math.ceil((double) sizeBytes / storageProperties.uploadPartSizeBytes());
        if (partCount <= 0 || partCount > MAX_PARTS) throw new BusinessException(40001, "文件分块数量不合法");
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(storageProperties.uploadSessionTtlHours());
        String sessionId = repository.createUploadSession(
                userId, projectId, fileName, MediaValidation.cleanRelativePath(relativePath),
                mimeType, sizeBytes, sha256.toLowerCase(), objectKey, upload.uploadId(),
                storageProperties.uploadPartSizeBytes(), expiresAt);
        return new MultipartSession(
                sessionId, objectKey, storageProperties.uploadPartSizeBytes(), partCount, expiresAt);
    }

    @Override
    public String presignPart(String userId, String sessionId, int partNumber) {
        LibraryRepository.UploadSession session = requireSession(userId, sessionId);
        int partCount = (int) Math.ceil((double) session.sizeBytes() / session.partSize());
        if (partNumber < 1 || partNumber > partCount) throw new BusinessException(40001, "分块编号不合法");
        return storage.presignPart(
                session.objectKey(), session.uploadId(), partNumber,
                Duration.ofMinutes(storageProperties.signedUrlTtlMinutes()));
    }

    @Override
    @Transactional
    public AssetSummary completeMultipart(String userId, String sessionId, List<ObjectStorage.CompletedPart> parts) {
        LibraryRepository.UploadSession session = requireSession(userId, sessionId);
        int expected = (int) Math.ceil((double) session.sizeBytes() / session.partSize());
        if (parts == null || parts.size() != expected) throw new BusinessException(40001, "分块列表不完整");
        storage.completeMultipart(session.objectKey(), session.uploadId(), parts);
        long actual = storage.contentLength(session.objectKey());
        if (actual != session.sizeBytes()) throw new BusinessException(40001, "上传对象大小与清单不一致");
        AssetSummary asset = assetCatalogService.registerMedia(userId, new AssetCatalogService.MediaRegistration(
                session.projectId(),
                session.projectId() == null ? AssetScope.GLOBAL : AssetScope.PROJECT,
                AssetType.valueOf(MediaValidation.mediaType(session.mimeType())),
                session.fileName(), null, "upload", null, session.objectKey(), session.sha256(),
                session.mimeType(), session.sizeBytes(), null, null, null,
                MediaType.valueOf(MediaValidation.mediaType(session.mimeType())),
                MediaRole.REFERENCE, true, null, 0
        ), "multipart:" + sessionId);
        repository.markUploadComplete(userId, sessionId);
        return asset;
    }

    @Override
    public void cancelMultipart(String userId, String sessionId) {
        LibraryRepository.UploadSession session = requireSession(userId, sessionId);
        storage.abortMultipart(session.objectKey(), session.uploadId());
        repository.markUploadCancelled(userId, sessionId);
    }

    @Scheduled(fixedDelay = 300_000)
    public void abortExpiredMultipartUploads() {
        for (LibraryRepository.UploadSession session : repository.expiredUploadSessions(50)) {
            try {
                storage.abortMultipart(session.objectKey(), session.uploadId());
                repository.markUploadCancelled(session.ownerUserId(), session.id());
            } catch (Exception ignored) {
                // 下一轮继续清理
            }
        }
    }

    private LibraryRepository.UploadSession requireSession(String userId, String sessionId) {
        LibraryRepository.UploadSession session = repository.findUploadSession(userId, sessionId)
                .orElseThrow(() -> new BusinessException(40401, "上传会话不存在"));
        if (session.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessException(40901, "上传会话已过期");
        }
        return session;
    }
}
