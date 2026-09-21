package com.storyboard.asset.dto;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;

import java.time.OffsetDateTime;
import java.util.List;

public final class ImportContracts {

    private ImportContracts() {
    }

    public enum ImportMode {
        ASSETS,
        GROUP,
        TREE
    }

    public record ScanRequest(
            String projectId,
            AssetScope scope,
            ImportMode mode,
            String groupName,
            List<Entry> entries
    ) {
    }

    public record Entry(String itemKey, String relativePath, String fileName, long sizeBytes, String mimeType) {
    }

    public record ScanResponse(
            String jobId,
            String confirmationToken,
            int imageCount,
            long totalBytes,
            int ignoredCount,
            String groupId,
            OffsetDateTime expiresAt
    ) {
    }

    public record ItemResult(String status, AssetSummary asset, String message) {
    }

    public record ImportJobStatus(
            String id,
            String status,
            String projectId,
            AssetScope scope,
            ImportMode mode,
            String groupId,
            int totalCount,
            int processedCount,
            int succeededCount,
            int duplicateCount,
            int failedCount,
            String errorMessage,
            OffsetDateTime updatedAt
    ) {
    }

    public record AnalysisTaskStatus(
            String assetId,
            String status,
            String errorMessage,
            OffsetDateTime updatedAt
    ) {
    }
}
