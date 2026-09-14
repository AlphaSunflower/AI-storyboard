package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;

import java.util.Collection;

/** 统一资产目录用例。 */
public interface AssetCatalogService {

    PageResponse<AssetSummary> list(
            String userId,
            AssetScope scope,
            String projectId,
            String query,
            AssetType type,
            Collection<String> tagIds,
            TagMode tagMode,
            boolean deleted,
            int page,
            int size
    );

    AssetDetail get(String userId, String assetId);

    AssetSummary create(String actorUserId, CreateAssetRequest request, String idempotencyKey);

    AssetSummary update(String userId, String assetId, UpdateAssetRequest request);

    AssetSummary promote(String userId, String assetId, long expectedVersion);

    void linkProject(String userId, String assetId, String projectId);

    AssetSummary trash(String userId, String assetId, long expectedVersion);

    AssetSummary restore(String userId, String assetId);

    void purge(String userId, String assetId);

    AssetSummary registerMedia(String userId, MediaRegistration registration, String idempotencyKey);

    record MediaRegistration(
            String projectId,
            AssetScope scope,
            AssetType assetType,
            String name,
            String description,
            String sourceType,
            String sourceId,
            String objectKey,
            String sha256,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            Double durationSeconds,
            MediaType mediaType,
            MediaRole role,
            boolean primary,
            String constraintText,
            int sortOrder
    ) {
    }
}
