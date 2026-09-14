package com.storyboard.asset.client;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** asset-service 与消费方共享的 HTTP 契约；此类型不包含任何数据库或存储实现。 */
public final class AssetContracts {

    private AssetContracts() {
    }

    public enum AssetScope { PROJECT, GLOBAL }

    public enum AssetType { IMAGE, VIDEO, AUDIO, AI_ASSET, GROUP }

    public enum AiAssetKind { CHARACTER, PROP, SCENE }

    public enum AssetStatus { ACTIVE, TRASHED, PURGE_PENDING, PURGE_BLOCKED, PURGED }

    public enum TagMode { ALL, ANY }

    public enum MediaRole { REFERENCE, FIRST_FRAME, LAST_FRAME }

    public enum MediaType { IMAGE, VIDEO, AUDIO }

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
    }

    public record AssetDetail(AssetSummary asset, List<MediaRef> media, List<ProjectLinkRef> linkedProjects) {
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

    public record ProjectOwnership(String projectId, String userId, boolean exists) {
    }
}
