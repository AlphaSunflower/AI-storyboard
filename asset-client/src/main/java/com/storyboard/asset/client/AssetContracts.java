package com.storyboard.asset.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** asset-service 与消费方共享的 HTTP 契约；此类型不包含任何数据库或存储实现。 */
public final class AssetContracts {

    private AssetContracts() {
    }

    public enum AssetScope { PROJECT, GLOBAL }

    public enum AssetType { IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER, AI_ASSET, GROUP }

    public enum AiAssetKind { CHARACTER, PROP, SCENE }

    public enum AssetStatus { ACTIVE, TRASHED, PURGE_PENDING, PURGE_BLOCKED, PURGED }

    /** 项目库素材来源语义：把系统生成的产出与手动上传区分开。 */
    public enum LibraryOrigin {
        /** 系统生成并回写的产出（Agent 回写、画布生成）。 */
        GENERATED(List.of("generation", "canvas-generation"));

        private final List<String> sourceTypes;

        LibraryOrigin(List<String> sourceTypes) {
            this.sourceTypes = sourceTypes;
        }

        public List<String> sourceTypes() {
            return sourceTypes;
        }
    }

    public enum MetadataState { NONE, AI_GENERATING, AI_GENERATED, AI_FAILED, EDITED }

    public enum TagMode { ALL, ANY }

    public enum MediaRole { REFERENCE, FIRST_FRAME, LAST_FRAME }

    public enum MediaType { IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiEnvelope<T>(int code, String message, T data) {
    }

    public record PageResponse<T>(List<T> records, long total, int page, int size) {
    }

    public record TagRef(String id, String name, String color, String source) {
    }

    public record ProjectLinkRef(String projectId, OffsetDateTime createdAt) {
    }

    public record MediaRef(
            String id,
            String blobId,
            MediaType type,
            String url,
            String contentUrl,
            String objectKey,
            String fileName,
            String relativePath,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            Double durationSeconds,
            int sortOrder,
            MediaRole role,
            boolean primary,
            String constraintText
    ) {
    }

    public record AssetSummary(
            String id,
            String ownerUserId,
            AssetScope scope,
            String ownerProjectId,
            AssetType type,
            AiAssetKind aiKind,
            String name,
            String description,
            String aiDescription,
            boolean manualOverride,
            MetadataState metadataState,
            AssetStatus status,
            String coverUrl,
            long version,
            List<TagRef> tags,
            List<ProjectLinkRef> projects,
            String folderId,
            List<String> folderPath,
            String sourceType,
            String sourceId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            /** 产出素材所属对话；一个对话对应「产出素材」里的一个文件夹。上传素材为 null。 */
            String conversationId,
            /** 产出素材来源镜头；同一对话 + 同一镜头的多次生成归为一组版本。 */
            String sceneId,
            /** 挑片星级 0–5。 */
            Integer rating,
            /** 挑片旗标：pick / reject；空为未标记。 */
            String flag,
            /** 挑片色标：red / yellow / green / blue / purple。 */
            String colorLabel
    ) {
        /** 兼容既有调用：不带目录路径的 19 参构造。 */
        public AssetSummary(
                String id,
                String ownerUserId,
                AssetScope scope,
                String ownerProjectId,
                AssetType type,
                AiAssetKind aiKind,
                String name,
                String description,
                String aiDescription,
                boolean manualOverride,
                MetadataState metadataState,
                AssetStatus status,
                String coverUrl,
                long version,
                List<TagRef> tags,
                List<ProjectLinkRef> projects,
                String sourceType,
                String sourceId,
                OffsetDateTime createdAt,
                OffsetDateTime updatedAt
        ) {
            this(id, ownerUserId, scope, ownerProjectId, type, aiKind, name, description,
                    aiDescription, manualOverride, metadataState, status, coverUrl, version, tags,
                    projects, null, List.of(), sourceType, sourceId, createdAt, updatedAt, null, null,
                    0, null, null);
        }

        /** 兼容既有调用：带目录路径但没有对话归属的 21 参构造。 */
        public AssetSummary(
                String id,
                String ownerUserId,
                AssetScope scope,
                String ownerProjectId,
                AssetType type,
                AiAssetKind aiKind,
                String name,
                String description,
                String aiDescription,
                boolean manualOverride,
                MetadataState metadataState,
                AssetStatus status,
                String coverUrl,
                long version,
                List<TagRef> tags,
                List<ProjectLinkRef> projects,
                String folderId,
                List<String> folderPath,
                String sourceType,
                String sourceId,
                OffsetDateTime createdAt,
                OffsetDateTime updatedAt
        ) {
            this(id, ownerUserId, scope, ownerProjectId, type, aiKind, name, description,
                    aiDescription, manualOverride, metadataState, status, coverUrl, version, tags,
                    projects, folderId, folderPath, sourceType, sourceId, createdAt, updatedAt, null, null,
                    0, null, null);
        }
    }

    /** 「产出素材」按对话聚合出来的虚拟文件夹。conversationId 为空表示未归属对话的生成结果。 */
    public record OutputConversationSummary(
            String conversationId,
            long assetCount,
            long imageCount,
            long videoCount,
            long documentCount,
            String coverUrl,
            List<String> previewUrls,
            String lastAssetName,
            OffsetDateTime lastGeneratedAt
    ) {
    }

    /** 资产组的使用侧信息：成员数量、快照次数和最近一次快照时间。 */
    public record LibraryGroupInsight(
            String groupId,
            long version,
            long mediaCount,
            long snapshotCount,
            OffsetDateTime lastSnapshotAt
    ) {
    }

    /** 同一份对象（sha256 相同）被多个素材引用时的一组重复项。 */
    public record DuplicateGroup(
            String blobId,
            String sha256,
            String mimeType,
            long sizeBytes,
            String scope,
            String ownerProjectId,
            List<AssetSummary> assets
    ) {
    }

    /** 合并重复素材：保留 survivor，其余素材的引用迁过去后进回收站。 */
    public record MergeDuplicatesRequest(
            String survivorAssetId,
            List<String> duplicateAssetIds
    ) {
    }

    /** 单张/批量挑片标记；null 表示清空该标记。 */
    public record AssetMarkRequest(
            Integer rating,
            String flag,
            String colorLabel
    ) {
    }

    public record BulkMarkRequest(
            List<String> assetIds,
            Integer rating,
            String flag,
            String colorLabel
    ) {
    }

    /** 可保存的筛选视图：filters/sort 直接存前端当前筛选条件。 */
    public record LibrarySavedView(
            String id,
            String ownerUserId,
            AssetScope scope,
            String ownerProjectId,
            String name,
            Map<String, Object> filters,
            Map<String, Object> sort,
            String viewMode,
            boolean pinned,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record SaveViewRequest(
            String projectId,
            String name,
            Map<String, Object> filters,
            Map<String, Object> sort,
            String viewMode,
            Boolean pinned
    ) {
    }

    public record UpdateSavedViewRequest(
            String name,
            Map<String, Object> filters,
            Map<String, Object> sort,
            String viewMode,
            Boolean pinned
    ) {
    }

    /** 素材的一条使用位置（分镜 / 对话 / 资产组 / 画布）。 */
    public record UsageRef(String id, String title, String detail) {
    }

    /** 素材被哪些业务对象引用；total 为四类引用数之和。 */
    public record AssetUsage(
            String assetId,
            int total,
            List<UsageRef> scenes,
            List<UsageRef> conversations,
            List<UsageRef> groups,
            List<UsageRef> canvases
    ) {
    }

    public record AssetDetail(AssetSummary asset, List<MediaRef> media, List<ProjectLinkRef> linkedProjects) {
    }

    public record AssetGroupDetail(
            String groupId,
            String name,
            String description,
            long version,
            List<AssetGroupMediaItem> media
    ) {
    }

    public record AssetGroupMediaItem(
            MediaRef media,
            AssetSummary asset,
            String assetId,
            String assetName,
            String assetDescription,
            long assetVersion,
            List<TagRef> assetTags
    ) {
        public AssetGroupMediaItem(
                MediaRef media,
                String assetId,
                String assetName,
                String assetDescription,
                long assetVersion,
                List<TagRef> assetTags
        ) {
            this(media, null, assetId, assetName, assetDescription, assetVersion, assetTags);
        }
    }

    public record CreateAssetRequest(
            String ownerUserId,
            String ownerProjectId,
            String type,
            String aiKind,
            String name,
            String description,
            String sourceType
    ) {
    }

    public record UpdateAssetRequest(
            String actorUserId,
            String name,
            String description,
            String aiDescription,
            Long expectedVersion,
            Boolean manualOverride
    ) {
    }

    public record LinkProjectRequest(String actorUserId, String projectId) {
    }

    public record PromoteRequest(String actorUserId, Long expectedVersion) {
    }

    public record CreateTagRequest(String ownerUserId, String name, String color) {
    }

    public record AssignTagsRequest(String actorUserId, List<String> tagIds) {
    }

    public record CreateGroupRequest(
            String ownerUserId,
            String ownerProjectId,
            AssetScope scope,
            String name,
            String description
    ) {
    }

    public record CreateFolderRequest(
            String actorUserId,
            AssetScope scope,
            String ownerProjectId,
            String parentId,
            String name
    ) {
    }

    public record UpdateFolderRequest(
            String actorUserId,
            String name,
            Long expectedVersion
    ) {
    }

    public record MoveAssetsToFolderRequest(
            String actorUserId,
            AssetScope scope,
            String ownerProjectId,
            String folderId,
            List<String> assetIds
    ) {
    }

    public record MoveFolderRequest(
            String actorUserId,
            String parentId,
            Long expectedVersion
    ) {
    }

    public record CopyFoldersRequest(
            String actorUserId,
            List<String> folderIds,
            String parentId
    ) {
    }

    public record LibraryFolderNode(
            String id,
            String parentId,
            String name,
            AssetScope scope,
            String ownerProjectId,
            long directAssetCount,
            long descendantAssetCount,
            long version
    ) {
    }

    public record FolderTreeResponse(
            AssetScope scope,
            String ownerProjectId,
            List<LibraryFolderNode> folders,
            long unfiledAssetCount
    ) {
    }

    public record BulkAssetIdsRequest(String actorUserId, List<String> assetIds) {
    }

    public record DuplicateAssetsRequest(
            String actorUserId,
            List<String> assetIds,
            String folderId
    ) {
    }

    public record BulkAssetOperationResult(
            List<String> updatedIds,
            List<SkippedAsset> skipped
    ) {
    }

    public record SkippedAsset(String assetId, String reason) {
    }

    public record CreateGroupFromAssetsRequest(
            String actorUserId,
            String ownerProjectId,
            String name,
            List<String> assetIds
    ) {
    }

    public record GroupItemRef(String mediaId, Integer sortOrder, MediaRole role) {
    }

    public record ReplaceGroupItemsRequest(String actorUserId, List<GroupItemRef> items) {
    }

    public record SnapshotRequest(String actorUserId, String target, Integer maxImages, Integer maxVideos, Integer maxAudios) {
    }

    public record SnapshotItem(
            String assetId,
            String mediaId,
            String blobId,
            String objectKey,
            String contentUrl,
            MediaType type,
            MediaRole role,
            int sortOrder
    ) {
    }

    public record AssetGroupSnapshot(
            String snapshotId,
            String groupId,
            long groupVersion,
            String contentHash,
            String target,
            List<SnapshotItem> items,
            OffsetDateTime createdAt
    ) {
    }

    public record GenerationOutputEvent(
            String producer,
            String ownerUserId,
            String projectId,
            String generationRecordId,
            Integer resultIndex,
            String groupId,
            String resultAssetId,
            String resultUrl,
            String mediaType,
            String prompt,
            String model,
            Map<String, Object> metadata
    ) {
    }

    /**
     * 登记已经存在于共享对象存储中的媒体对象。
     * consumer 只需提供对象键和来源标识，asset-service 负责补齐 blob/media 目录记录。
     */
    public record ExternalMediaRegistration(
            String ownerUserId,
            String projectId,
            String type,
            String name,
            String description,
            String sourceType,
            String sourceId,
            String objectKey,
            String mimeType,
            Long sizeBytes,
            String sha256,
            MediaRole role,
            Integer sortOrder
    ) {
    }

    /** 供下游保存稳定引用，生成时再换取签名 URL。 */
    public record LibraryMediaReference(
            String assetId,
            String mediaId,
            MediaType type,
            MediaRole role
    ) {
    }

    public record ProjectOwnership(String projectId, String userId, boolean exists) {
    }
}
