package com.storyboard.asset.storage;

import com.storyboard.asset.config.StorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioObjectStorageTest {

    private static StorageProperties properties() {
        return new StorageProperties(
                "http://localhost:9000", "minioadmin", "minioadmin123", "storyboard", "us-east-1",
                0, 0, 0);
    }

    @Test
    void listsObjectsWithPaginationToken() {
        S3Client client = mock(S3Client.class);
        MinioObjectStorage storage = new MinioObjectStorage(properties(), client);
        Instant lastModified = Instant.parse("2026-09-01T00:00:00Z");
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder()
                        .key("images/a.png")
                        .size(123L)
                        .lastModified(lastModified)
                        .build())
                .isTruncated(true)
                .nextContinuationToken("token-2")
                .build());

        ObjectStorage.ObjectPage page = storage.listObjects("images/", null, 1000);

        assertThat(page.objects()).hasSize(1);
        assertThat(page.objects().get(0).key()).isEqualTo("images/a.png");
        assertThat(page.objects().get(0).sizeBytes()).isEqualTo(123L);
        assertThat(page.objects().get(0).lastModified()).isEqualTo(lastModified);
        assertThat(page.nextContinuationToken()).isEqualTo("token-2");
    }

    @Test
    void returnsNullTokenWhenListingIsComplete() {
        S3Client client = mock(S3Client.class);
        MinioObjectStorage storage = new MinioObjectStorage(properties(), client);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("videos/b.mp4").size(9L).lastModified(Instant.EPOCH).build())
                .isTruncated(false)
                .build());

        ObjectStorage.ObjectPage page = storage.listObjects("videos/", null, 1000);

        assertThat(page.objects()).hasSize(1);
        assertThat(page.nextContinuationToken()).isNull();
    }
}