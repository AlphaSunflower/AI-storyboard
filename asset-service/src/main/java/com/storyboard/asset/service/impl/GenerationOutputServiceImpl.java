package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.GenerationOutputEvent;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.domain.MediaValidation;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.GenerationOutputService;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class GenerationOutputServiceImpl implements GenerationOutputService {

    private final LibraryRepository repository;
    private final AssetCatalogService assetCatalogService;
    private final ObjectStorage storage;

    public GenerationOutputServiceImpl(
            LibraryRepository repository,
            AssetCatalogService assetCatalogService,
            ObjectStorage storage
    ) {
        this.repository = repository;
        this.assetCatalogService = assetCatalogService;
        this.storage = storage;
    }

    @Override
    @Transactional
    public AssetSummary register(GenerationOutputEvent event) {
        validate(event);
        String existing = repository.findExistingOutput(
                event.producer(), event.generationRecordId(), event.resultIndex()).orElse(null);
        if (existing != null) {
            repository.attachOutputConversation(existing, metadataString(event, "conversationId"));
            repository.attachOutputScene(existing, metadataString(event, "sceneId"));
            return assetCatalogService.get(event.ownerUserId(), existing).asset();
        }

        String objectKey = objectKey(event.resultUrl());
        String mediaType = event.mediaType() == null || event.mediaType().isBlank()
                ? MediaValidation.mediaTypeFromObjectKey(objectKey)
                : event.mediaType().toUpperCase();
        String mimeType = MediaValidation.defaultMimeType(mediaType, objectKey);
        long size;
        try {
            size = storage.contentLength(objectKey);
        } catch (Exception e) {
            throw new BusinessException(50201, "生成结果对象不存在: " + objectKey, e);
        }
        String sha256 = metadataString(event, "sha256");
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            sha256 = LibraryRepository.sha256Hex(objectKey.getBytes(StandardCharsets.UTF_8));
        }
        String idempotencyKey = event.producer() + ":" + event.generationRecordId() + ":" + event.resultIndex();
        AssetSummary asset = assetCatalogService.registerMedia(event.ownerUserId(),
                new AssetCatalogService.MediaRegistration(
                        event.projectId(),
                        event.projectId() == null ? AssetScope.GLOBAL : AssetScope.PROJECT,
                        AssetType.valueOf(mediaType),
                        fileName(objectKey),
                        event.prompt(),
                        "generation",
                        event.resultAssetId() == null ? event.generationRecordId() : event.resultAssetId(),
                        objectKey,
                        sha256,
                        mimeType,
                        size,
                        null,
                        null,
                        null,
                        MediaType.valueOf(mediaType),
                        MediaRole.REFERENCE,
                        true,
                        null,
                        0,
                        metadataString(event, "conversationId"),
                        metadataString(event, "sceneId")
                ),
                idempotencyKey);
        if (event.groupId() != null && !event.groupId().isBlank()) {
            repository.linkGroupOutput(
                    event.groupId(), event.producer(), event.generationRecordId(), event.resultIndex(), asset.id());
        }
        return asset;
    }

    private void validate(GenerationOutputEvent event) {
        if (event == null || event.ownerUserId() == null || event.producer() == null
                || event.generationRecordId() == null || event.resultIndex() == null || event.resultUrl() == null) {
            throw new BusinessException(40001, "生成结果回写参数不完整");
        }
    }

    private static String objectKey(String url) {
        String clean = url;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        String marker = "/api/files/";
        int index = clean.indexOf(marker);
        if (index >= 0) return clean.substring(index + marker.length());
        if (clean.startsWith("images/") || clean.startsWith("videos/") || clean.startsWith("audios/")
                || clean.startsWith("documents/") || clean.startsWith("files/")) {
            return clean;
        }
        throw new BusinessException(40001, "生成结果 URL 不属于资产对象存储");
    }

    private static String fileName(String objectKey) {
        return objectKey.substring(objectKey.lastIndexOf('/') + 1);
    }

    private static String metadataString(GenerationOutputEvent event, String key) {
        Object value = event.metadata() == null ? null : event.metadata().get(key);
        return value == null ? null : value.toString();
    }
}
