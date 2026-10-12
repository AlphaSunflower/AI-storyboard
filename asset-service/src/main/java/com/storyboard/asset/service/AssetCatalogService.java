package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.LibraryOrigin;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;
import com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult;
import com.storyboard.asset.dto.ImportContracts.AnalysisTaskStatus;
import com.storyboard.asset.dto.ImportContracts.AnalysisRequestResult;

import java.util.Collection;
import java.util.List;

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

    PageResponse<AssetSummary> list(
            String userId,
            AssetScope scope,
            String projectId,
            String folderId,
            String query,
            AssetType type,
            Collection<String> tagIds,
            TagMode tagMode,
            boolean deleted,
            boolean includeGlobal,
            LibraryOrigin origin,
            int page,
            int size
    );

    AssetDetail get(String userId, String assetId);

    AssetDetail findBySource(String userId, String sourceType, String sourceId);

    MediaRef getMedia(String userId, String mediaId);

    AssetSummary create(String actorUserId, CreateAssetRequest request, String idempotencyKey);

    AssetSummary update(String userId, String assetId, UpdateAssetRequest request);

    AssetSummary promote(String userId, String assetId, long expectedVersion);

    BulkAssetOperationResult bulkPromote(String userId, Collection<String> assetIds);

    void linkProject(String userId, String assetId, String projectId);

    AssetSummary trash(String userId, String assetId, long expectedVersion);

    BulkAssetOperationResult bulkTrash(String userId, Collection<String> assetIds);

    BulkAssetOperationResult duplicate(String userId, DuplicateAssetsRequest request);

    AssetSummary restore(String userId, String assetId);

    void purge(String userId, String assetId);

    AssetSummary registerMedia(String userId, MediaRegistration registration, String idempotencyKey);

    List<AnalysisTaskStatus> listAnalysisTaskStatuses(String userId, Collection<String> assetIds);

    /** 用户主动触发：为指定图片素材排队 AI 元数据完善。 */
    AnalysisRequestResult requestAnalysis(String userId, Collection<String> assetIds);

    /** 用户主动触发：为目录（含子目录）内的图片素材排队 AI 元数据完善。 */
    AnalysisRequestResult requestFolderAnalysis(
            String userId,
            AssetScope scope,
            String projectId,
            String folderId);

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
