package com.storyboard.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.annotation.PostConstruct;
import java.net.URI;

/**
 * MinIO 对象存储封装（S3 兼容端点）。图片/视频/音频统一存对象存储，替代本地 uploads/。
 * object key 形如 {@code {type}/{filename}}（单桶 storyboard，key 即目录结构）。
 * <p>
 * 配置：{@code storyboard.storage.endpoint|access-key|secret-key|bucket|region}，默认指向本机 MinIO；
 * 生产用 application-prod.yml 覆盖。可迁阿里云 OSS / AWS S3——仅改 endpoint+region+access-key+secret-key，代码零改动（S3 协议通用）。
 */
@Component
public class MinioStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioStorage.class);

    @Value("${storyboard.storage.endpoint:http://localhost:9000}") private String endpoint;
    @Value("${storyboard.storage.access-key:minioadmin}") private String accessKey;
    @Value("${storyboard.storage.secret-key:minioadmin123}") private String secretKey;
    @Value("${storyboard.storage.bucket:storyboard}") private String bucket;
    @Value("${storyboard.storage.region:us-east-1}") private String region;

    private S3Client s3;

    @PostConstruct
    void init() {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)   // MinIO 需 path-style
                        .build())
                .build();
        ensureBucket();
        log.info("MinIO storage 就绪: endpoint={}, bucket={}", endpoint, bucket);
    }

    /** 创建 bucket（已存在则忽略，幂等）。 */
    private void ensureBucket() {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() != 409) {   // BucketAlreadyOwnedByYou / BucketAlreadyExists
                throw new IllegalStateException("创建 MinIO bucket 失败: " + e.getMessage(), e);
            }
        }
    }

    /** object key = {type}/{filename} */
    public String key(String type, String filename) {
        return type + "/" + filename;
    }

    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes));
    }

    /** 读取对象字节；不存在返回 null。 */
    public byte[] get(String key) {
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return in.readAllBytes();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (java.io.IOException e) {
            throw new RuntimeException("读取 MinIO 对象失败: key=" + key, e);
        }
    }

    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /** 删除对象（best-effort：失败仅告警不抛，删除残留由对象生命周期兜底）。 */
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            log.warn("删除 MinIO 对象失败(忽略): key={}, err={}", key, e.getMessage());
        }
    }
}
