package com.storyboard.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    private static final Map<String, String> MODEL_ALIAS = Map.of(
        "veo-3.1-fast", "veo-3.1-fast-generate-preview",
        "veo-3.1-fast-fl", "veo-3.1-fast-generate-preview",
        "veo-3.1", "veo-3.1-generate-preview",
        "veo-3.1-fl", "veo-3.1-generate-preview"
    );

    public VideoGenerationService(AiConfigProperties config, SceneMapper sceneMapper,
                                   FileStorageService fileStorageService) {
        this.config = config;
        this.sceneMapper = sceneMapper;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Create a video generation task via JSON body (not multipart).
     */
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, Integer duration,
                                   List<String> referenceImages, String generatedImageUrl) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        String actualModel = MODEL_ALIAS.getOrDefault(alias, alias);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", actualModel);
            body.put("prompt", prompt);
            body.put("duration", "8");  // 必须是字符串
            body.put("n", 1);

            // 图生视频：优先用已生成的图片
            String refImage = generatedImageUrl != null ? generatedImageUrl
                : (referenceImages != null && !referenceImages.isEmpty() ? referenceImages.get(0) : null);
            if (refImage != null) {
                body.put("input_reference", refImage);
            }

            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlOpenai() + "/videos"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(java.time.Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new RuntimeException("Video API returned " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("id").asText();

            scene.setVideoTaskId(taskId);
            scene.setVideoStatus("generating");
            sceneMapper.updateById(scene);

            return taskId;
        } catch (Exception e) {
            scene.setVideoStatus("failed");
            sceneMapper.updateById(scene);
            throw new RuntimeException("AI 视频生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * Poll video task status.
     * Tries /videos/{id} first, falls back to /video/generations/{id}.
     */
    public Map<String, String> pollVideoTask(String taskId) {
        try {
            String baseUrl = config.getBaseUrlOpenai();
            String respBody = callGet(baseUrl + "/videos/" + taskId);
            if (respBody == null) {
                respBody = callGet(baseUrl + "/video/generations/" + taskId);
            }
            if (respBody == null) {
                Map<String, String> error = new HashMap<>();
                error.put("status", "failed");
                error.put("error", "All polling endpoints failed for taskId=" + taskId);
                return error;
            }

            log.info("Video poll response: {}", respBody);
            JsonNode root = objectMapper.readTree(respBody);
            String status = root.path("status").asText();

            Map<String, String> result = new HashMap<>();
            result.put("taskId", taskId);

            if ("completed".equals(status) || "succeeded".equals(status)) {
                // Try to download via content endpoint
                String localPath = null;
                try {
                    HttpRequest contentReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/videos/" + taskId + "/content"))
                        .header("Authorization", "Bearer " + config.getApiKey())
                        .timeout(java.time.Duration.ofSeconds(180))
                        .GET()
                        .build();
                    HttpResponse<InputStream> contentResp = httpClient.send(contentReq,
                        HttpResponse.BodyHandlers.ofInputStream());
                    if (contentResp.statusCode() == 200) {
                        String filename = UUID.randomUUID().toString() + ".mp4";
                        Path target = Paths.get("uploads/videos").resolve(filename);
                        Files.createDirectories(target.getParent());
                        try (InputStream in = contentResp.body()) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        localPath = "/api/files/videos/" + filename;
                        log.info("Video downloaded and saved: {}", target);
                    } else {
                        log.warn("Video content endpoint returned {}, async download may be unavailable", contentResp.statusCode());
                    }
                } catch (Exception e) {
                    log.warn("Video content download failed (async may be unavailable): {}", e.getMessage());
                }
                result.put("status", "completed");
                result.put("videoUrl", localPath);

                // 更新 scene
                var scenes = sceneMapper.selectList(
                    new LambdaQueryWrapper<Scene>()
                        .eq(Scene::getVideoTaskId, taskId)
                );
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoUrl(localPath);
                    scene.setVideoStatus("completed");
                    sceneMapper.updateById(scene);
                }
            } else if ("failed".equals(status) || "error".equals(status)) {
                result.put("status", "failed");
                // 更新失败的 scene
                var scenes = sceneMapper.selectList(
                    new LambdaQueryWrapper<Scene>()
                        .eq(Scene::getVideoTaskId, taskId)
                );
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoStatus("failed");
                    sceneMapper.updateById(scene);
                }
            } else {
                result.put("status", "processing");
                result.put("progress", root.path("progress").asText(""));
            }
            return result;
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("status", "failed");
            error.put("error", e.getMessage());
            return error;
        }
    }

    private String callGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(java.time.Duration.ofSeconds(120))
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            log.warn("GET {} returned {}", url, resp.statusCode());
        } catch (Exception e) {
            log.warn("GET {} failed: {}", url, e.getMessage());
        }
        return null;
    }
}
