package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetMarkRequest;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.AssetUsage;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.LibraryGroupInsight;
import com.storyboard.asset.client.AssetContracts.LibraryOrigin;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.OutputConversationSummary;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;
import com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult;
import com.storyboard.asset.client.AssetContracts.BulkMarkRequest;
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

    /** 在上一版本基础上按对话过滤：conversationId=UNASSIGNED 表示未归属对话的产出。 */
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
            String conversationId,
            int page,
            int size
    );

    /** 再叠加挑片标记过滤：minRating / flag / colorLabel。 */
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
            String conversationId,
            Integer minRating,
            String flag,
            String colorLabel,
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

    /** 「产出素材」按对话聚合的虚拟文件夹列表。 */
    List<OutputConversationSummary> listOutputConversations(
            String userId,
            String projectId,
            String query,
            AssetType type,
            Collection<String> tagIds,
            TagMode tagMode);

    List<OutputConversationSummary> listOutputConversations(
            String userId,
            String projectId,
            String query,
            AssetType type,
            Collection<String> tagIds,
            TagMode tagMode,
            Integer minRating,
            String flag,
            String colorLabel);

    /** 资产组使用侧信息：成员数、快照次数、最近快照时间。 */
    List<LibraryGroupInsight> listGroupInsights(String userId, String projectId);

    /** 单张素材挑片标记：rating/flag/colorLabel 整组覆盖，空值即清除。 */
    AssetSummary mark(String userId, String assetId, AssetMarkRequest request);

    /** 批量挑片标记，最多 500 项。 */
    BulkAssetOperationResult bulkMark(String userId, BulkMarkRequest request);

    /** 素材使用位置：分镜 / 对话 / 资产组 / 画布。 */
    AssetUsage getUsage(String userId, String assetId);

    /** 未被分镜、资产组、画布引用的素材（不含资产组本身）。 */
    PageResponse<AssetSummary> listUnused(String userId, String projectId, int page, int size);

    /** 智能搜索：关键词（AI 描述 + pg_trgm）、主色、找相似。 */
    List<AssetSummary> search(String userId, String projectId, String query,
                              String color, String similarTo, int limit);

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
            int sortOrder,
            String conversationId,
            String sceneId
    ) {
        /** 兼容既有上传调用：上传素材不属于任何对话/镜头。 */
        public MediaRegistration(
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
            this(projectId, scope, assetType, name, description, sourceType, sourceId,
                    objectKey, sha256, mimeType, sizeBytes, width, height, durationSeconds,
                    mediaType, role, primary, constraintText, sortOrder, null, null);
        }

        /** 兼容生成回写中还没有镜头信息的调用。 */
        public MediaRegistration(
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
                int sortOrder,
                String conversationId
        ) {
            this(projectId, scope, assetType, name, description, sourceType, sourceId,
                    objectKey, sha256, mimeType, sizeBytes, width, height, durationSeconds,
                    mediaType, role, primary, constraintText, sortOrder, conversationId, null);
        }
    }
}
