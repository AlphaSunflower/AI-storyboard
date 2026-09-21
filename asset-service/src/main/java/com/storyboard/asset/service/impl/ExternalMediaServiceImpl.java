package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.ExternalMediaRegistration;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.domain.MediaValidation;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ExternalMediaService;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class ExternalMediaServiceImpl implements ExternalMediaService {

    private final LibraryRepository repository;
    private final AssetCatalogService catalogService;
    private final ObjectStorage storage;

    public ExternalMediaServiceImpl(
            LibraryRepository repository,
            AssetCatalogService catalogService,
            ObjectStorage storage
    ) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.storage = storage;
    }

    @Override
    @Transactional
    public AssetSummary register(String actorUserId, ExternalMediaRegistration request) {
        validate(actorUserId, request);
        var existing = repository.findAssetBySource(
                request.ownerUserId(), request.sourceType(), request.sourceId());
        if (existing.isPresent()) return existing.get();

        String objectKey = cleanObjectKey(request.objectKey());
        String mediaTypeValue = request.type() == null || request.type().isBlank()
                ? MediaValidation.mediaTypeFromObjectKey(objectKey)
                : request.type().trim().toUpperCase();
        MediaType mediaType = parseMediaType(mediaTypeValue);
        AssetType assetType = AssetType.valueOf(mediaType.name());
        long size = request.sizeBytes() == null ? storage.contentLength(objectKey) : request.sizeBytes();
        if (size < 0) throw new BusinessException(50201, "外部媒体对象不存在: " + objectKey);
        String sha256 = request.sha256() == null || !request.sha256().matches("[0-9a-fA-F]{64}")
                ? LibraryRepository.sha256Hex(objectKey.getBytes(StandardCharsets.UTF_8))
                : request.sha256().toLowerCase();
        String mimeType = request.mimeType() == null || request.mimeType().isBlank()
                ? defaultMimeType(mediaType, objectKey)
                : request.mimeType().trim();
        String name = request.name() == null || request.name().isBlank()
                ? fileName(objectKey)
                : request.name().trim();
        AssetScope scope = request.projectId() == null || request.projectId().isBlank()
                ? AssetScope.GLOBAL
                : AssetScope.PROJECT;
        MediaRole role = request.role() == null ? MediaRole.REFERENCE : request.role();
        int sortOrder = request.sortOrder() == null ? 0 : Math.max(0, request.sortOrder());

        return catalogService.registerMedia(
                request.ownerUserId(),
                new AssetCatalogService.MediaRegistration(
                        request.projectId(),
                        scope,
                        assetType,
                        name,
                        request.description(),
                        request.sourceType(),
                        request.sourceId(),
                        objectKey,
                        sha256,
                        mimeType,
                        size,
                        null,
                        null,
                        null,
                        mediaType,
                        role,
                        true,
                        null,
                        sortOrder
                ),
                "external:" + request.sourceType() + ":" + request.sourceId()
        );
    }

    private static void validate(String actorUserId, ExternalMediaRegistration request) {
        if (request == null
                || actorUserId == null
                || request.ownerUserId() == null
                || request.ownerUserId().isBlank()
                || request.sourceType() == null
                || request.sourceType().isBlank()
                || request.sourceId() == null
                || request.sourceId().isBlank()
                || request.objectKey() == null
                || request.objectKey().isBlank()) {
            throw new BusinessException(40001, "外部媒体登记参数不完整");
        }
        if (!actorUserId.equals(request.ownerUserId())) {
            throw new BusinessException(40301, "无权登记其他用户的媒体");
        }
    }

    private static MediaType parseMediaType(String value) {
        try {
            return MediaType.valueOf(value);
        } catch (Exception e) {
            throw new BusinessException(40001, "不支持的媒体类型: " + value);
        }
    }

    private static String cleanObjectKey(String objectKey) {
        String cleaned = objectKey.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (!cleaned.startsWith("images/") && !cleaned.startsWith("videos/") && !cleaned.startsWith("audios/")) {
            throw new BusinessException(40001, "对象 key 不属于媒体目录");
        }
        return cleaned;
    }

    private static String defaultMimeType(MediaType mediaType, String objectKey) {
        String extension = objectKey.substring(objectKey.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            default -> switch (mediaType) {
                case IMAGE -> "image/png";
                case VIDEO -> "video/mp4";
                case AUDIO -> "audio/mpeg";
            };
        };
    }

    private static String fileName(String objectKey) {
        return objectKey.substring(objectKey.lastIndexOf('/') + 1);
    }
}
