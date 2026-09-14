package com.storyboard.asset.dto;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;

import java.time.OffsetDateTime;
import java.util.List;

public final class ImportContracts {

    private ImportContracts() {
    }

    public record ScanRequest(String projectId, AssetScope scope, List<Entry> entries) {
    }

    public record Entry(String itemKey, String relativePath, String fileName, long sizeBytes, String mimeType) {
    }

    public record ScanResponse(
            String jobId,
            String confirmationToken,
            int imageCount,
            long totalBytes,
            int ignoredCount,
            OffsetDateTime expiresAt
    ) {
    }

    public record ItemResult(String status, AssetSummary asset, String message) {
    }
}
