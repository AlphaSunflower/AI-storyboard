package com.storyboard.asset.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static com.storyboard.asset.client.AssetContracts.ApiEnvelope;
import static com.storyboard.asset.client.AssetContracts.AssetDetail;
import static com.storyboard.asset.client.AssetContracts.AssetGroupDetail;
import static com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import static com.storyboard.asset.client.AssetContracts.AssetScope;
import static com.storyboard.asset.client.AssetContracts.AssetSummary;
import static com.storyboard.asset.client.AssetContracts.AssignTagsRequest;
import static com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import static com.storyboard.asset.client.AssetContracts.CreateGroupRequest;
import static com.storyboard.asset.client.AssetContracts.CreateTagRequest;
import static com.storyboard.asset.client.AssetContracts.ExternalMediaRegistration;
import static com.storyboard.asset.client.AssetContracts.FolderTreeResponse;
import static com.storyboard.asset.client.AssetContracts.GenerationOutputEvent;
import static com.storyboard.asset.client.AssetContracts.LinkProjectRequest;
import static com.storyboard.asset.client.AssetContracts.MediaRef;
import static com.storyboard.asset.client.AssetContracts.PageResponse;
import static com.storyboard.asset.client.AssetContracts.PromoteRequest;
import static com.storyboard.asset.client.AssetContracts.ReplaceGroupItemsRequest;
import static com.storyboard.asset.client.AssetContracts.SnapshotRequest;
import static com.storyboard.asset.client.AssetContracts.TagMode;
import static com.storyboard.asset.client.AssetContracts.TagRef;
import static com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;

/**
 * asset-service 的唯一 HTTP 客户端。
 * <p>所有写请求都带幂等键；所有请求都生成或透传 correlationId。
 */
public class AssetClient {

    /**
     * 解析 asset-service 响应用的 mapper。
     *
     * <p>必须**容忍未知字段**：契约是「服务端先加字段、消费方后升级」的，
     * asset-service 与消费方（Backend / MoonAgent / XiaoYueCanvas）各自独立部署，
     * 服务端一旦新增字段，尚未重新打包的消费方就会解析失败。
     * 2026-10-19 的事故正是如此：asset-service 的 AssetSummary 新增了
     * {@code conversationId} 等字段，画布服务（10-17 打的包）在
     * {@code /api/internal/library/assets/by-source} 上直接抛
     * {@code Unrecognized field "conversationId"}，画布资产同步永久卡在 failed。
     *
     * <p>原来这里是每次响应都 {@code new ObjectMapper()}（Jackson 默认
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=true}），比 Spring Boot 自动配置的 mapper
     * 严格，且每个响应都重建一次 mapper；现在复用同一个实例。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final RestClient restClient;
    private final String internalToken;

    public AssetClient(RestClient restClient, String internalToken) {
        this.restClient = restClient;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    public static AssetClient create(AssetClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds()));
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return new AssetClient(restClient, properties.internalToken());
    }

    public PageResponse<AssetSummary> listAssets(
            AssetScope scope,
            String projectId,
            String q,
            String type,
            Collection<String> tagIds,
            TagMode tagMode,
            boolean deleted,
            int page,
            int size
    ) {
        return listAssets(scope, projectId, null, q, type, tagIds, tagMode, deleted, page, size);
    }

    /** 以内置用户身份查询项目库，供画布服务等受信任调用方代理前端使用。 */
    public PageResponse<AssetSummary> listAssetsForUser(
            String actorUserId,
            AssetScope scope,
            String projectId,
            String folderId,
            String q,
            String type,
            int page,
            int size,
            String correlationId
    ) {
        return exchange("GET", "/api/library/assets",
                uri -> {
                    UriBuilder builder = uri.path("/api/library/assets");
                    if (scope != null) builder.queryParam("scope", scope.name());
                    if (projectId != null && !projectId.isBlank()) builder.queryParam("projectId", projectId);
                    if (folderId != null && !folderId.isBlank()) builder.queryParam("folderId", folderId);
                    if (q != null && !q.isBlank()) builder.queryParam("q", q);
                    if (type != null && !type.isBlank()) builder.queryParam("type", type);
                    builder.queryParam("page", Math.max(1, page));
                    builder.queryParam("size", Math.min(100, Math.max(1, size)));
                    return builder.build();
                },
                null, null, correlationId, actorUserId,
                new ParameterizedTypeReference<>() {});
    }

    public FolderTreeResponse getFolderTreeForUser(
            String actorUserId,
            AssetScope scope,
            String projectId,
            String correlationId
    ) {
        return exchange("GET", "/api/library/folders/tree",
                uri -> {
                    UriBuilder builder = uri.path("/api/library/folders/tree")
                            .queryParam("scope", scope.name());
                    if (projectId != null && !projectId.isBlank()) builder.queryParam("projectId", projectId);
                    return builder.build();
                },
                null, null, correlationId, actorUserId,
                new ParameterizedTypeReference<>() {});
    }

    public PageResponse<AssetSummary> listAssets(
            AssetScope scope,
            String projectId,
            String folderId,
            String q,
            String type,
            Collection<String> tagIds,
            TagMode tagMode,
            boolean deleted,
            int page,
            int size
    ) {
        return get("/api/library/assets", uri -> {
            UriBuilder builder = uri.path("/api/library/assets");
            if (scope != null) builder.queryParam("scope", scope.name());
            if (projectId != null && !projectId.isBlank()) builder.queryParam("projectId", projectId);
            if (folderId != null && !folderId.isBlank()) builder.queryParam("folderId", folderId);
            if (q != null && !q.isBlank()) builder.queryParam("q", q);
            if (type != null && !type.isBlank()) builder.queryParam("type", type);
            if (tagIds != null && !tagIds.isEmpty()) builder.queryParam("tagIds", String.join(",", tagIds));
            if (tagMode != null) builder.queryParam("tagMode", tagMode.name());
            builder.queryParam("deleted", deleted);
            builder.queryParam("page", Math.max(1, page));
            builder.queryParam("size", Math.min(100, Math.max(1, size)));
            return builder.build();
        }, new ParameterizedTypeReference<>() {});
    }

    public AssetDetail getAsset(String assetId) {
        return get("/api/library/assets/{id}", uri -> uri.path("/api/library/assets/{id}").build(assetId), new ParameterizedTypeReference<>() {});
    }

    public AssetDetail getAssetInternal(String assetId, String actingUserId) {
        return exchange("GET", "/api/library/assets/{id}",
                uri -> uri.path("/api/library/assets/{id}").build(assetId),
                null, null, UUID.randomUUID().toString(), actingUserId,
                new ParameterizedTypeReference<>() {});
    }

    public AssetGroupDetail getGroupInternal(String groupId, String actingUserId) {
        return exchange("GET", "/api/library/groups/{id}",
                uri -> uri.path("/api/library/groups/{id}").build(groupId),
                null, null, UUID.randomUUID().toString(), actingUserId,
                new ParameterizedTypeReference<>() {});
    }

    public MediaRef getMediaInternal(String mediaId, String actingUserId) {
        return exchange("GET", "/api/internal/library/media/{id}",
                uri -> uri.path("/api/internal/library/media/{id}")
                        .queryParam("ownerUserId", actingUserId)
                        .build(mediaId),
                null, null, UUID.randomUUID().toString(), actingUserId,
                new ParameterizedTypeReference<>() {});
    }

    public AssetSummary createAsset(CreateAssetRequest request, String idempotencyKey, String correlationId) {
        return post("/api/library/assets", request, idempotencyKey, correlationId, request.ownerUserId(), new ParameterizedTypeReference<>() {});
    }

    public AssetSummary updateAsset(String assetId, UpdateAssetRequest request, long expectedVersion, String correlationId, String actingUserId) {
        return exchange("PUT", "/api/library/assets/{id}", uri -> uri.path("/api/library/assets/{id}").build(assetId),
                request, "asset-update:" + assetId + ":" + expectedVersion, correlationId, actingUserId, new ParameterizedTypeReference<>() {});
    }

    public void linkProject(String assetId, LinkProjectRequest request, String correlationId) {
        exchange("POST", "/api/library/assets/{id}/projects", uri -> uri.path("/api/library/assets/{id}/projects").build(assetId),
                request, "asset-link:" + assetId + ":" + request.projectId(), correlationId, new ParameterizedTypeReference<ApiEnvelope<Void>>() {});
    }

    public AssetSummary promote(String assetId, PromoteRequest request, String correlationId) {
        return exchange("POST", "/api/library/assets/{id}/promote", uri -> uri.path("/api/library/assets/{id}/promote").build(assetId),
                request, "asset-promote:" + assetId + ":" + request.expectedVersion(), correlationId, request.actorUserId(), new ParameterizedTypeReference<>() {});
    }

    public void trash(String assetId, String actorUserId, long expectedVersion, String correlationId) {
        exchange("DELETE", "/api/library/assets/{id}", uri -> uri.path("/api/library/assets/{id}")
                        .queryParam("actorUserId", actorUserId).queryParam("expectedVersion", expectedVersion).build(assetId),
                null, "asset-trash:" + assetId + ":" + expectedVersion, correlationId, actorUserId, new ParameterizedTypeReference<ApiEnvelope<Void>>() {});
    }

    public AssetSummary restore(String assetId, String actorUserId, String correlationId) {
        return exchange("POST", "/api/library/assets/{id}/restore",
                uri -> uri.path("/api/library/assets/{id}/restore").queryParam("actorUserId", actorUserId).build(assetId),
                null, "asset-restore:" + assetId, correlationId, new ParameterizedTypeReference<>() {});
    }

    public void attachTags(String assetId, AssignTagsRequest request, String correlationId) {
        exchange("PUT", "/api/library/assets/{id}/tags", uri -> uri.path("/api/library/assets/{id}/tags").build(assetId),
                request, "asset-tags:" + assetId + ":" + String.join(",", request.tagIds()), correlationId,
                new ParameterizedTypeReference<ApiEnvelope<Void>>() {});
    }

    public AssetContracts.MediaRef addAssetMedia(String assetId, Resource resource, String correlationId, String actingUserId) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", resource);
        return exchange("POST", "/api/library/assets/{id}/media",
                uri -> uri.path("/api/library/assets/{id}/media").build(assetId),
                body.build(), "asset-media:" + assetId + ":" + resource.getFilename(),
                correlationId, actingUserId, new ParameterizedTypeReference<>() {});
    }

    public void deleteAssetMedia(String assetId, String mediaId, String correlationId, String actingUserId) {
        exchange("DELETE", "/api/library/assets/{assetId}/media/{mediaId}",
                uri -> uri.path("/api/library/assets/{assetId}/media/{mediaId}").build(assetId, mediaId),
                null, "asset-media-delete:" + assetId + ":" + mediaId,
                correlationId, actingUserId, new ParameterizedTypeReference<ApiEnvelope<Void>>() {});
    }

    public TagRef createTag(CreateTagRequest request, String correlationId) {
        return post("/api/library/tags", request, "tag:" + request.ownerUserId() + ":" + request.name(), correlationId,
                new ParameterizedTypeReference<>() {});
    }

    public AssetSummary createGroup(CreateGroupRequest request, String idempotencyKey, String correlationId) {
        return post("/api/library/groups", request, idempotencyKey, correlationId, new ParameterizedTypeReference<>() {});
    }

    public void replaceGroupItems(String groupId, ReplaceGroupItemsRequest request, long expectedVersion, String correlationId) {
        exchange("PUT", "/api/library/groups/{id}/items", uri -> uri.path("/api/library/groups/{id}/items").build(groupId),
                request, "group-items:" + groupId + ":" + expectedVersion, correlationId, new ParameterizedTypeReference<ApiEnvelope<Void>>() {});
    }

    public AssetGroupSnapshot createSnapshot(String groupId, SnapshotRequest request, String correlationId) {
        return exchange("POST", "/api/library/groups/{id}/snapshots",
                uri -> uri.path("/api/library/groups/{id}/snapshots").build(groupId),
                request,
                "group-snapshot:" + groupId + ":" + request.maxImages() + ":" + request.maxVideos() + ":" + request.maxAudios(),
                correlationId, request.actorUserId(), new ParameterizedTypeReference<>() {});
    }

    public AssetGroupSnapshot getSnapshot(String snapshotId, String actorUserId, String correlationId) {
        return exchange("GET", "/api/library/snapshots/{id}",
                uri -> uri.path("/api/library/snapshots/{id}").queryParam("actorUserId", actorUserId).build(snapshotId),
                null, null, correlationId, actorUserId, new ParameterizedTypeReference<>() {});
    }

    public AssetSummary registerGenerationOutput(GenerationOutputEvent event, String correlationId) {
        return post("/api/internal/library/generation-outputs", event,
                event.producer() + ":" + event.generationRecordId() + ":" + event.resultIndex(),
                correlationId, event.ownerUserId(), new ParameterizedTypeReference<>() {});
    }

    public AssetSummary registerExternalMedia(
            ExternalMediaRegistration request,
            String correlationId
    ) {
        String idempotencyKey = "external:" + request.sourceType() + ":" + request.sourceId();
        return post("/api/internal/library/external-media", request, idempotencyKey,
                correlationId, request.ownerUserId(), new ParameterizedTypeReference<>() {});
    }

    public AssetDetail findAssetBySource(
            String ownerUserId,
            String sourceType,
            String sourceId,
            String correlationId
    ) {
        return exchange("GET", "/api/internal/library/assets/by-source",
                uri -> uri.path("/api/internal/library/assets/by-source")
                        .queryParam("ownerUserId", ownerUserId)
                        .queryParam("sourceType", sourceType)
                        .queryParam("sourceId", sourceId)
                        .build(),
                null, null, correlationId, ownerUserId,
                new ParameterizedTypeReference<>() {});
    }

    private <T> T get(String path, java.util.function.Function<UriBuilder, java.net.URI> uriFn,
                      ParameterizedTypeReference<T> type) {
        return exchange("GET", path, uriFn, null, null, UUID.randomUUID().toString(), type);
    }

    private <T> T post(String path, Object body, String idempotencyKey, String correlationId,
                       ParameterizedTypeReference<T> type) {
        return post(path, body, idempotencyKey, correlationId, null, type);
    }

    private <T> T post(String path, Object body, String idempotencyKey, String correlationId,
                       String actingUserId, ParameterizedTypeReference<T> type) {
        return exchange("POST", path, uri -> uri.path(path).build(), body, idempotencyKey,
                correlationId, actingUserId, type);
    }

    private <T> T exchange(
            String method,
            String path,
            java.util.function.Function<UriBuilder, java.net.URI> uriFn,
            Object body,
            String idempotencyKey,
            String correlationId,
            ParameterizedTypeReference<T> type
    ) {
        return exchange(method, path, uriFn, body, idempotencyKey, correlationId, null, type);
    }

    private <T> T exchange(
            String method,
            String path,
            java.util.function.Function<UriBuilder, java.net.URI> uriFn,
            Object body,
            String idempotencyKey,
            String correlationId,
            String actingUserId,
            ParameterizedTypeReference<T> type
    ) {
        RestClient.RequestBodySpec spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(uriBuilder -> uriFn.apply(uriBuilder.path("")));
        if (!internalToken.isBlank()) spec.header("X-Internal-Token", internalToken);
        if (actingUserId != null && !actingUserId.isBlank()) spec.header("X-User-Id", actingUserId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) spec.header("Idempotency-Key", idempotencyKey);
        if (correlationId != null && !correlationId.isBlank()) spec.header("X-Correlation-Id", correlationId);
        if (body != null) spec.body(body);
        if (body instanceof org.springframework.util.MultiValueMap<?, ?>) {
            spec.contentType(MediaType.MULTIPART_FORM_DATA);
        }

        return spec.exchange((request, response) -> {
            ObjectMapper mapper = MAPPER;
            JavaType innerType = mapper.getTypeFactory().constructType(type.getType());
            JavaType javaType = mapper.getTypeFactory().constructParametricType(ApiEnvelope.class, innerType);
            var responseBody = response.getBody();
            int httpStatus = response.getStatusCode().value();
            if (responseBody == null) {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new AssetServiceException(httpStatus, httpStatus,
                            "asset-service 请求失败: HTTP " + httpStatus, correlationId);
                }
                return null;
            }
            ApiEnvelope<T> envelope;
            try {
                envelope = mapper.readValue(responseBody, javaType);
            } catch (Exception e) {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new AssetServiceException(httpStatus, httpStatus,
                            "asset-service 请求失败: HTTP " + httpStatus, correlationId);
                }
                throw new AssetServiceException(50000, httpStatus,
                        "asset-service 响应解析失败: " + e.getMessage(), correlationId);
            }
            if (envelope.code() != 0 && envelope.code() != 200) {
                throw new AssetServiceException(envelope.code(), httpStatus, envelope.message(), correlationId);
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new AssetServiceException(httpStatus, httpStatus,
                        "asset-service 请求失败: HTTP " + httpStatus, correlationId);
            }
            return envelope.data();
        });
    }
}
