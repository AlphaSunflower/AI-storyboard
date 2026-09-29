package com.storyboard.asset.storage;

import java.io.InputStream;
import java.time.Duration;

/** 对象存储端口，asset-service 是唯一写入者。 */
public interface ObjectStorage {

    String objectKey(String mediaType, String extension);

    long contentLength(String objectKey);

    boolean exists(String objectKey);

    void put(String objectKey, InputStream inputStream, long contentLength, String contentType);

    StoredObject get(String objectKey, String rangeHeader);

    void delete(String objectKey);

    MultipartUpload createMultipart(String objectKey, String contentType);

    String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl);

    void completeMultipart(String objectKey, String uploadId, java.util.List<CompletedPart> parts);

    void abortMultipart(String objectKey, String uploadId);

    ObjectPage listObjects(String prefix, String continuationToken, int maxKeys);

    record ObjectSummary(String key, long sizeBytes, java.time.Instant lastModified) {
    }

    record ObjectPage(java.util.List<ObjectSummary> objects, String nextContinuationToken) {
    }

    record MultipartUpload(String uploadId, String objectKey) {
    }

    record CompletedPart(int partNumber, String etag) {
    }
}
