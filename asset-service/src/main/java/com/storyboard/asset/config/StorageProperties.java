package com.storyboard.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storyboard.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        long uploadPartSizeBytes,
        int uploadSessionTtlHours,
        int signedUrlTtlMinutes,
        int trashRetentionDays,
        boolean orphanScanEnabled,
        int orphanScanIntervalHours,
        int orphanScanGraceHours
) {
    public static final int DEFAULT_TRASH_RETENTION_DAYS = 3;
    public static final int DEFAULT_ORPHAN_SCAN_INTERVAL_HOURS = 6;
    public static final int DEFAULT_ORPHAN_SCAN_GRACE_HOURS = 24;

    public StorageProperties {
        endpoint = endpoint == null ? "http://localhost:9000" : endpoint;
        accessKey = accessKey == null ? "minioadmin" : accessKey;
        secretKey = secretKey == null ? "minioadmin123" : secretKey;
        bucket = bucket == null ? "storyboard" : bucket;
        region = region == null ? "us-east-1" : region;
        uploadPartSizeBytes = uploadPartSizeBytes <= 0 ? 8L * 1024 * 1024 : uploadPartSizeBytes;
        uploadSessionTtlHours = uploadSessionTtlHours <= 0 ? 24 : uploadSessionTtlHours;
        signedUrlTtlMinutes = signedUrlTtlMinutes <= 0 ? 15 : signedUrlTtlMinutes;
        trashRetentionDays = trashRetentionDays <= 0 ? DEFAULT_TRASH_RETENTION_DAYS : trashRetentionDays;
        orphanScanIntervalHours =
                orphanScanIntervalHours <= 0 ? DEFAULT_ORPHAN_SCAN_INTERVAL_HOURS : orphanScanIntervalHours;
        orphanScanGraceHours = orphanScanGraceHours <= 0 ? DEFAULT_ORPHAN_SCAN_GRACE_HOURS : orphanScanGraceHours;
    }
}