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
import java.time.Duration;
import java.util.*;

/**
 * 视频生成服务 —— 调用 Laozhang API v1/videos multipart 接口。
 */
@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public VideoGenerationService(AiConfigProperties config, SceneMapper sceneMapper,
                                   FileStorageService fileStorageService) {
        this.config = config;
        this.sceneMapper = sceneMapper;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 创建视频生成任务（multipart/form-data）。
     */
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, String size, String aspectRatio,
                                   Integer duration, String negativePrompt, Long seed,
                                   List<String> referenceImages, String generatedImageUrl) {
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("视频生成 prompt 不能为空（Dify 变量可能未正确设置）");
        }

        String actualModel = alias != null
                ? config.getVideoModelAliasMap().getOrDefault(alias, alias)
                : "veo-3.1-fast-generate-preview";  // 默认模型

        // 使用请求参数或配置默认值
        String effSize = size != null ? size : config.getDefaultVideoSize();
        String effResolution = resolution != null ? resolution : config.getDefaultVideoResolution();
        String effAspectRatio = aspectRatio != null ? aspectRatio : config.getDefaultVideoAspectRatio();
        int effDuration = duration != null ? duration : Integer.parseInt(config.getDefaultVideoDuration());

        try {
            // 构建 multipart 请求体
            MultipartBuilder mp = new MultipartBuilder()
                .field("model", actualModel)
                .field("prompt", prompt)
                .field("seconds", String.valueOf(effDuration))
                .field("duration", String.valueOf(effDuration))
                .field("size", effSize)
                .field("resolution", effResolution)
                .field("aspectRatio", effAspectRatio);

            // metadata JSON
            String metadata = objectMapper.writeValueAsString(Map.of(
                "durationSeconds", effDuration,
                "resolution", effResolution,
                "aspectRatio", effAspectRatio
            ));
            mp.field("metadata", metadata);

            if (negativePrompt != null && !negativePrompt.isEmpty()) {
                mp.field("negativePrompt", negativePrompt);
            }
            if (seed != null) {
                mp.field("seed", String.valueOf(seed));
            }

            // 参考图片：优先使用已生成图片，其次使用第一张参考图
            if (generatedImageUrl != null && !generatedImageUrl.isEmpty()) {
                String filename = extractFilename(generatedImageUrl);
                Path localFile = fileStorageService.resolveImage(filename);
                if (Files.exists(localFile)) {
                    byte[] bytes = Files.readAllBytes(localFile);
                    mp.file("input_reference", filename, fileStorageService.contentType(filename), bytes);
                } else {
                    log.warn("Reference image not found: {}", localFile);
                }
            } else if (referenceImages != null && !referenceImages.isEmpty()) {
                String base64 = referenceImages.get(0);
                byte[] bytes = decodeBase64Image(base64);
                mp.file("input_reference", "reference.png", "image/png", bytes);
            }

            byte[] body = mp.build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrlOpenai() + config.getEndpointVideoCreate()))
                .header("Content-Type", "multipart/form-data; boundary=" + mp.boundary())
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
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
     * 轮询视频任务状态，完成后自动下载视频文件。
     */
    public Map<String, String> pollVideoTask(String taskId) {
        try {
            String baseUrl = config.getBaseUrlOpenai();
            String respBody = callGet(baseUrl + config.getEndpointVideoStatus() + taskId);
            if (respBody == null) {
                respBody = callGet(baseUrl + config.getEndpointVideoStatusFallback() + taskId);
            }
            if (respBody == null) {
                return Map.of("status", "failed", "error", "All polling endpoints failed for taskId=" + taskId);
            }

            log.info("Video poll response: {}", respBody);
            JsonNode root = objectMapper.readTree(respBody);
            String status = root.path("status").asText();

            Map<String, String> result = new HashMap<>();
            result.put("taskId", taskId);

            if ("completed".equals(status) || "succeeded".equals(status)) {
                String localPath = downloadVideoContent(baseUrl, taskId);
                result.put("status", "completed");
                result.put("videoUrl", localPath);

                var scenes = sceneMapper.selectList(
                    new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, taskId));
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoUrl(localPath);
                    scene.setVideoStatus("completed");
                    sceneMapper.updateById(scene);
                }
            } else if ("failed".equals(status) || "error".equals(status)) {
                result.put("status", "failed");
                var scenes = sceneMapper.selectList(
                    new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, taskId));
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
            return Map.of("status", "failed", "error", e.getMessage());
        }
    }

    private String downloadVideoContent(String baseUrl, String taskId) {
        int maxRetries = 3;
        long retryDelayMs = 15_000; // 15 秒等待落盘

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + config.getEndpointVideoContent() + taskId + "/content"))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .timeout(Duration.ofSeconds(180))
                    .GET().build();
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() == 200) {
                    String filename = UUID.randomUUID() + config.getVideoFileExtension();
                    Path target = Paths.get(config.getVideoUploadDir()).resolve(filename);
                    Files.createDirectories(target.getParent());
                    try (InputStream in = resp.body()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.info("Video downloaded: {} (attempt {}/{})", target, attempt, maxRetries);
                    return config.getVideoUrlPrefix() + filename;
                }

                // 400 可能是"task status is IN_PROGRESS"等短暂状态
                String respBody = "";
                try { respBody = new String(resp.body().readAllBytes()); } catch (Exception ignored) {}
                log.warn("Video content download returned {} on attempt {}/{}: {}",
                    resp.statusCode(), attempt, maxRetries,
                    respBody.length() > 200 ? respBody.substring(0, 200) : respBody);

            } catch (Exception e) {
                log.warn("Video content download failed on attempt {}/{}: {}",
                    attempt, maxRetries, e.getMessage());
            }

            // 非最后一次则等待重试
            if (attempt < maxRetries) {
                try { Thread.sleep(retryDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.error("Video content download failed after {} attempts for taskId={}", maxRetries, taskId);
        return null;
    }

    private String callGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            log.warn("GET {} returned {}", url, resp.statusCode());
        } catch (Exception e) {
            log.warn("GET {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    private String extractFilename(String urlPath) {
        int idx = urlPath.lastIndexOf('/');
        return idx >= 0 ? urlPath.substring(idx + 1) : urlPath;
    }

    private byte[] decodeBase64Image(String base64Data) {
        String clean = base64Data;
        if (clean.contains(",") && clean.contains("base64")) {
            clean = clean.substring(clean.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(clean);
    }
}
