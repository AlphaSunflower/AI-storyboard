package com.storyboard.asset.storage;

import com.storyboard.asset.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private final StorageProperties properties;
    private final S3Client injectedClient;
    private S3Client client;
    private S3Presigner presigner;

    public MinioObjectStorage(StorageProperties properties) {
        this(properties, null);
    }

    MinioObjectStorage(StorageProperties properties, S3Client injectedClient) {
        this.properties = properties;
        this.injectedClient = injectedClient;
        this.client = injectedClient;
    }

    @PostConstruct
    void init() {
        if (injectedClient != null) {
            this.client = injectedClient;
            return;
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        S3Configuration configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration)
                .build();
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (S3Exception e) {
            if (e.statusCode() != 409) throw e;
        }
    }

    @Override
    public ObjectPage listObjects(String prefix, String continuationToken, int maxKeys) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(properties.bucket())
                .prefix(prefix)
                .maxKeys(maxKeys);
        if (continuationToken != null && !continuationToken.isBlank()) {
            request.continuationToken(continuationToken);
        }
        ListObjectsV2Response response = client.listObjectsV2(request.build());
        List<ObjectSummary> objects = response.contents().stream()
                .map((S3Object object) -> new ObjectSummary(
                        object.key(), object.size() == null ? 0L : object.size(), object.lastModified()))
                .toList();
        String next = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        return new ObjectPage(objects, next);
    }

    @Override
    public String objectKey(String mediaType, String extension) {
        String folder = switch (mediaType == null ? "IMAGE" : mediaType.toUpperCase()) {
            case "VIDEO" -> "videos";
            case "AUDIO" -> "audios";
            default -> "images";
        };
        String normalizedExtension = extension == null || extension.isBlank() ? "bin" : extension.replace(".", "").toLowerCase();
        return folder + "/" + UUID.randomUUID() + "." + normalizedExtension;
    }

    @Override
    public long contentLength(String objectKey) {
        return client.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build()).contentLength();
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void put(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .contentLength(contentLength)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(inputStream, contentLength));
    }

    @Override
    public StoredObject get(String objectKey, String rangeHeader) {
        GetObjectRequest.Builder request = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey);
        if (rangeHeader != null && !rangeHeader.isBlank()) request.range(rangeHeader);
        ResponseInputStream<GetObjectResponse> stream = client.getObject(request.build());
        GetObjectResponse response = stream.response();
        return new StoredObject(
                stream,
                response.contentLength(),
                response.contentType(),
                response.contentRange()
        );
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
    }

    @Override
    public MultipartUpload createMultipart(String objectKey, String contentType) {
        String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .build())
                .uploadId();
        return new MultipartUpload(uploadId, objectKey);
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();
        return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                        .signatureDuration(ttl)
                        .uploadPartRequest(request)
                        .build())
                .url()
                .toString();
    }

    @Override
    public void completeMultipart(String objectKey, String uploadId, java.util.List<CompletedPart> parts) {
        java.util.List<software.amazon.awssdk.services.s3.model.CompletedPart> s3Parts = parts.stream()
                .map(part -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.etag())
                        .build())
                .toList();
        client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(s3Parts).build())
                .build());
    }

    @Override
    public void abortMultipart(String objectKey, String uploadId) {
        client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .uploadId(uploadId)
                .build());
    }
}
