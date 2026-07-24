package com.storyboard.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.FileStorageService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VideoGenerationService {

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

    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, Integer duration,
                                   List<String> referenceImages) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        String actualModel = MODEL_ALIAS.getOrDefault(alias, alias);

        try {
            // POST /v1/videos (multipart)
            String boundary = "----FormBoundary" + System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            sb.append(actualModel).append("\r\n");
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n");
            sb.append(prompt).append("\r\n");
            if (resolution != null) {
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"resolution\"\r\n\r\n");
                sb.append(resolution).append("\r\n");
            }
            if (duration != null) {
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"duration\"\r\n\r\n");
                sb.append(duration).append("\r\n");
            }
            sb.append("--").append(boundary).append("--\r\n");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlOpenai() + "/videos"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(java.time.Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
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

    public Map<String, String> pollVideoTask(String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlOpenai() + "/videos/" + taskId))
                .timeout(java.time.Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + config.getApiKey())
                .GET()
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            String status = root.path("status").asText();

            Map<String, String> result = new HashMap<>();
            result.put("taskId", taskId);

            if ("completed".equals(status) || "succeeded".equals(status)) {
                String videoUrl = root.path("url").asText();
                // Download and save locally
                String localPath = fileStorageService.saveVideo(videoUrl);
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
}
