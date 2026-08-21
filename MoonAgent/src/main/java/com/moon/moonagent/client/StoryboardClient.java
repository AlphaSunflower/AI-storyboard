package com.moon.moonagent.client;

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
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 主后端内部 API 客户端 — 替代直接 SceneMapper/AssetService 调用。
 * Agent 服务通过 HTTP 回调主后端 /api/internal/* 端点。
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

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", internalSecret);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ===== 分镜 =====

    /** 查项目分镜列表 */
    public List<Scene> getProjectScenes(String projectId) {
        String url = baseUrl + "/api/internal/projects/" + projectId + "/scenes";
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<List<Scene>>>() {});
        return resp.getBody() != null ? resp.getBody().data() : List.of();
    }

    /** 批量写入分镜 */
    public void batchInsertScenes(List<Scene> scenes) {
        String url = baseUrl + "/api/internal/scenes/batch";
        restTemplate.postForEntity(url, new HttpEntity<>(scenes, headers()), String.class);
    }

    /** 清空项目分镜 */
    public void deleteProjectScenes(String projectId) {
        String url = baseUrl + "/api/internal/scenes/project/" + projectId;
        restTemplate.exchange(url, HttpMethod.DELETE,
                new HttpEntity<>(headers()), String.class);
    }

    /** 更新项目全部分镜的生成参数（PATCH） */
    public void updateProjectSceneParams(String projectId, Map<String, String> params) {
        String url = baseUrl + "/api/internal/scenes/project/" + projectId + "/params";
        restTemplate.exchange(url, HttpMethod.PATCH,
                new HttpEntity<>(params, headers()), String.class);
    }

    /** 关联资产到场景 */
    public void linkSceneAssets(String sceneId, List<String> assetIds) {
        String url = baseUrl + "/api/internal/scenes/" + sceneId + "/assets";
        restTemplate.postForEntity(url, new HttpEntity<>(assetIds, headers()), String.class);
    }

    // ===== 资产 =====

    /** 查项目可用资产（项目资产 + 用户全局资产） */
    public List<AssetVO> getProjectAssets(String projectId) {
        String url = baseUrl + "/api/internal/projects/" + projectId + "/assets";
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<List<AssetVO>>>() {});
        return resp.getBody() != null ? resp.getBody().data() : List.of();
    }

    /**
     * 拼「设定集」文字块（本地工具方法，不需要 HTTP）。
     * 空列表返回空串；每资产一行「类型：名称\n描述：xxx」。
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

    /** 查项目信息（ScriptGenerationService 用） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getProject(String projectId) {
        String url = baseUrl + "/api/internal/projects/" + projectId;
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<Map<String, Object>>>() {});
        return resp.getBody() != null ? resp.getBody().data() : null;
    }

    // ===== 单场景操作（AI 服务需要） =====

    /** 按 videoTaskId 查场景（视频异步轮询终态更新用） */
    public Scene getSceneByVideoTaskId(String videoTaskId) {
        String url = baseUrl + "/api/internal/scenes/by-video-task/" + videoTaskId;
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<Scene>>() {});
        return resp.getBody() != null ? resp.getBody().data() : null;
    }

    /** 查单个场景（AgentGenerationService / VideoGenerationService 用） */
    public Scene getScene(String sceneId) {
        String url = baseUrl + "/api/internal/scenes/" + sceneId;
        var resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers()),
                new ParameterizedTypeReference<ApiResponse<Scene>>() {});
        return resp.getBody() != null ? resp.getBody().data() : null;
    }

    /** 更新场景 */
    public void updateScene(Scene scene) {
        String url = baseUrl + "/api/internal/scenes/" + scene.getId();
        restTemplate.exchange(url, HttpMethod.PUT,
                new HttpEntity<>(scene, headers()), String.class);
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
