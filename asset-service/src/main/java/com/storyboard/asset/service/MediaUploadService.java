package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.storage.ObjectStorage;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MediaUploadService {

    AssetSummary uploadDirect(
            String userId,
            String projectId,
            AssetScope scope,
            String name,
            String description,
            String relativePath,
            MultipartFile file,
            String idempotencyKey
    );

    com.storyboard.asset.client.AssetContracts.MediaRef uploadToAsset(
            String userId,
            String assetId,
            MultipartFile file,
            String constraintText
    );

    void deleteAssetMedia(String userId, String assetId, String mediaId);

    MultipartSession initMultipart(
            String userId,
            String projectId,
            AssetScope scope,
            String fileName,
            String relativePath,
            String mimeType,
            long sizeBytes,
            String sha256
    );

    String presignPart(String userId, String sessionId, int partNumber);

    AssetSummary completeMultipart(String userId, String sessionId, List<ObjectStorage.CompletedPart> parts);

    void cancelMultipart(String userId, String sessionId);

    record MultipartSession(
            String sessionId,
            String objectKey,
            long partSize,
            int partCount,
            java.time.OffsetDateTime expiresAt
    ) {
    }
}
