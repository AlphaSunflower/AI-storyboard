package com.moon.moonagent.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.Scene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 主后端内部 API 客户端。
 *
 * <p>读操作带 Caffeine 本地缓存（30s 过期），写操作快速失败（3s 连接超时）。
 * 写操作后自动清对应缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryboardClient {

    private final RestTemplate restTemplate;

    @Value("${storyboard.internal-url:http://localhost:8082}")
    private String baseUrl;

    @Value("${storyboard.internal-secret:moon-internal-secret-2024}")
    private String internalSecret;

    /** 分镜列表缓存（projectId → scenes，30s 过期） */
    private final Cache<String, List<Scene>> sceneCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(100)
            .build();

    /** 资产列表缓存 */
    private final Cache<String, List<AssetVO>> assetCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(100)
            .build();

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", internalSecret);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ===== 分镜 =====

    /** 查项目分镜列表（带缓存） */
    public List<Scene> getProjectScenes(String projectId) {
        return sceneCache.get(projectId, this::fetchProjectScenes);
    }

    private List<Scene> fetchProjectScenes(String projectId) {
        String url = baseUrl + "/api/internal/projects/" + projectId + "/scenes";
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<List<Scene>>>() {});
        return resp.getBody() != null ? resp.getBody().data() : List.of();
    }

    /** 批量写入分镜（写后清缓存） */
    public void batchInsertScenes(List<Scene> scenes) {
        String url = baseUrl + "/api/internal/scenes/batch";
        try {
            restTemplate.postForEntity(url, new HttpEntity<>(scenes, headers()), String.class);
            // 写操作后清缓存
            scenes.stream().map(Scene::getProjectId).distinct().forEach(sceneCache::invalidate);
        } catch (ResourceAccessException e) {
            log.error("StoryboardClient.batchInsertScenes: 主后端不可用 {}", e.getMessage());
            throw new RuntimeException("主后端不可用，请稍后重试", e);
        }
    }

    /** 清空项目分镜（写后清缓存） */
    public void deleteProjectScenes(String projectId) {
        String url = baseUrl + "/api/internal/scenes/project/" + projectId;
        try {
            restTemplate.exchange(url, HttpMethod.DELETE,
                    new HttpEntity<>(headers()), String.class);
            sceneCache.invalidate(projectId);
        } catch (ResourceAccessException e) {
            log.error("StoryboardClient.deleteProjectScenes: 主后端不可用 {}", e.getMessage());
            throw new RuntimeException("主后端不可用，请稍后重试", e);
        }
    }

    /** 更新项目全部分镜的生成参数 */
    public void updateProjectSceneParams(String projectId, Map<String, String> params) {
        String url = baseUrl + "/api/internal/scenes/project/" + projectId + "/params";
        try {
            restTemplate.exchange(url, HttpMethod.PATCH,
                    new HttpEntity<>(params, headers()), String.class);
            sceneCache.invalidate(projectId);
        } catch (ResourceAccessException e) {
            log.error("StoryboardClient.updateProjectSceneParams: 主后端不可用 {}", e.getMessage());
            throw new RuntimeException("主后端不可用，请稍后重试", e);
        }
    }

    /** 关联资产到场景 */
    public void linkSceneAssets(String sceneId, List<String> assetIds) {
        String url = baseUrl + "/api/internal/scenes/" + sceneId + "/assets";
        restTemplate.postForEntity(url, new HttpEntity<>(assetIds, headers()), String.class);
    }

    // ===== 资产 =====

    /** 查项目可用资产（带缓存） */
    public List<AssetVO> getProjectAssets(String projectId) {
        return assetCache.get(projectId, this::fetchProjectAssets);
    }

    private List<AssetVO> fetchProjectAssets(String projectId) {
        String url = baseUrl + "/api/internal/projects/" + projectId + "/assets";
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<List<AssetVO>>>() {});
        return resp.getBody() != null ? resp.getBody().data() : List.of();
    }

    /**
     * 拼「设定集」文字块（本地工具方法，不需要 HTTP）。
     */
    public static String buildSheetText(List<AssetVO> assets) {
        if (assets == null || assets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (AssetVO a : assets) {
            sb.append(a.type()).append("：").append(a.name());
            if (a.description() != null && !a.description().isBlank()) {
                sb.append("\n描述：").append(a.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 通用 API 响应包装 */
    public record ApiResponse<T>(int code, String message, T data) {}

    /** 查项目信息 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProject(String projectId) {
        String url = baseUrl + "/api/internal/projects/" + projectId;
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {});
        return resp.getBody() != null ? resp.getBody().data() : null;
    }

    // ===== 单场景操作 =====

    /** 按 videoTaskId 查场景 */
    public Scene getSceneByVideoTaskId(String videoTaskId) {
        String url = baseUrl + "/api/internal/scenes/by-video-task/" + videoTaskId;
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<Scene>>() {});
        return resp.getBody() != null ? resp.getBody().data() : null;
    }

    /** 查单个场景 */
    public Scene getScene(String sceneId) {
        String url = baseUrl + "/api/internal/scenes/" + sceneId;
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<Scene>>() {});
        return resp.getBody() != null ? resp.getBody().data() : null;
    }

    /** 更新场景（写后清缓存） */
    public void updateScene(Scene scene) {
        String url = baseUrl + "/api/internal/scenes/" + scene.getId();
        try {
            restTemplate.exchange(url, HttpMethod.PUT,
                    new HttpEntity<>(scene, headers()), String.class);
            sceneCache.invalidate(scene.getProjectId());
        } catch (ResourceAccessException e) {
            log.error("StoryboardClient.updateScene: 主后端不可用 {}", e.getMessage());
            throw new RuntimeException("主后端不可用，请稍后重试", e);
        }
    }

    /** 插入单个场景 */
    public void insertScene(Scene scene) {
        batchInsertScenes(List.of(scene));
    }

    /** 查项目最大场景号 */
    public int maxSceneNumber(String projectId) {
        List<Scene> scenes = getProjectScenes(projectId);
        return scenes.stream().mapToInt(Scene::getSceneNumber).max().orElse(0);
    }
}
