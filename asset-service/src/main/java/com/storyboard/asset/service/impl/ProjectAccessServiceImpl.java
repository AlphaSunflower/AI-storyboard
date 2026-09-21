package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.ApiEnvelope;
import com.storyboard.asset.client.AssetContracts.ProjectOwnership;
import com.storyboard.asset.config.InternalApiProperties;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 项目归属只读校验；正负结果缓存 60 秒，服务故障时写操作明确失败。 */
@Service
public class ProjectAccessServiceImpl implements ProjectAccessPort {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final String internalToken;
    private final Map<String, CachedOwnership> cache = new ConcurrentHashMap<>();

    public ProjectAccessServiceImpl(InternalApiProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.projectUrl() == null ? "http://localhost:8082" : properties.projectUrl())
                .build();
        this.internalToken = properties.secret() == null ? "" : properties.secret();
    }

    @Override
    public ProjectOwnership requireOwned(String userId, String projectId) {
        if (userId == null || userId.isBlank() || projectId == null || projectId.isBlank()) {
            throw new BusinessException(40001, "项目和用户不能为空");
        }
        String cacheKey = userId + "\n" + projectId;
        CachedOwnership cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return ownershipOrThrow(userId, cached.ownership());
        }
        try {
            ApiEnvelope<ProjectOwnership> envelope = restClient.get()
                    .uri("/api/internal/projects/{id}/ownership", projectId)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            ProjectOwnership ownership = envelope == null ? null : envelope.data();
            cache.put(cacheKey, new CachedOwnership(ownership, Instant.now().plus(CACHE_TTL)));
            return ownershipOrThrow(userId, ownership);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50301, "项目归属校验暂不可用", e);
        }
    }

    private ProjectOwnership ownershipOrThrow(String userId, ProjectOwnership ownership) {
        if (ownership == null || !ownership.exists() || ownership.userId() == null) {
            throw new BusinessException(40401, "项目不存在");
        }
        if (!userId.equals(ownership.userId())) {
            throw new BusinessException(40401, "项目不存在或无权访问");
        }
        return ownership;
    }

    private record CachedOwnership(ProjectOwnership ownership, Instant expiresAt) {
    }
}
