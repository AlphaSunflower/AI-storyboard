package com.storyboard.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.AgentAssetMapper;
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
    private final AgentAssetMapper agentAssetMapper;
    private final FileStorageService fileStorageService;
    private final MinimaxVideoService minimaxVideoService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public VideoGenerationService(AiConfigProperties config, SceneMapper sceneMapper,
                                   AgentAssetMapper agentAssetMapper,
                                   FileStorageService fileStorageService,
                                   MinimaxVideoService minimaxVideoService) {
        this.config = config;
        this.sceneMapper = sceneMapper;
        this.agentAssetMapper = agentAssetMapper;
        this.fileStorageService = fileStorageService;
        this.minimaxVideoService = minimaxVideoService;
    }

    /**
     * 创建视频生成任务（multipart/form-data）。
     */
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, String size, String aspectRatio,
                                   Integer duration, String negativePrompt, Long seed,
                                   List<String> referenceImages, String generatedImageUrl) {
        // Provider 分发：minimax（默认）走 MiniMax V2 链路，laozhang 走下方原逻辑（保留可切回）
        if ("minimax".equals(config.getVideoProvider())) {
            return minimaxVideoService.createVideoTask(sceneId, prompt, alias, resolution, size,
                    aspectRatio, duration, negativePrompt, seed, referenceImages, generatedImageUrl);
        }
        Scene scene = sceneId != null ? sceneMapper.selectById(sceneId) : null;
        if (sceneId != null && scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

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

        // ── 上游兼容性兜底（重要）────────────────────────────────────────────
        // 老张网关按 resolution/size 路由到不同的上游 Google 项目池：
        //   · 标准小写 "720p" + "1280x720" → 正常池，可用
        //   · 1080p/4K 档位（含 "1080P"/"4K" 等大小写变体）→ 路由到无模型权限的
        //     池子（locations/global 下无 veo-3.1-fast-generate-preview），
        //     返回 404 fail_to_fetch_task（实测 2026-08-03 复现）
        // 因此在 Laozhang 修复前，统一降级为 720p；竖屏保留 9:16 尺寸。
        // 待上游修复后可删除本兜底。
        String safeSize = "9:16".equals(effAspectRatio) ? "720x1280" : "1280x720";
        String safeResolution = "720p";
        if (!safeSize.equals(effSize)) {
            log.warn("视频生成 size={} 上游暂不可用, 降级为 {}", effSize, safeSize);
            effSize = safeSize;
        }
        if (!safeResolution.equalsIgnoreCase(effResolution)) {
            log.warn("视频生成 resolution={} 上游暂不可用, 降级为 {}", effResolution, safeResolution);
            effResolution = safeResolution;
        }

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

            HttpResponse<String> resp = null;
            // ── 自动重试（重要）──────────────────────────────────────────────
            // 老张网关将视频请求轮询路由到多个上游 Google 项目，其中大部分项目
            // 未开通 veo-3.1-fast-generate-preview（locations/global 下无此模型），
            // 命中即返回 404 fail_to_fetch_task。实测 2026-08-04 成功率仅约 1/8
            // 且失败集中在 fir-2-80d08，故重试上限提到 10 次（~73% 成功率），
            // 每次重试会重新路由到不同上游项目。待上游修复后可视情况调回。
            // 另外部分池子的服务账号已失效，创建返回 500（body 含 do_request_failed
            // / invalid_grant: account not found，实测 2026-08-04 复现）。该错误发生在
            // 请求发往 Google 之前的 token 获取阶段，任务必然未创建，可安全重试换池。
            int maxAttempts = 10;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                String respBody = resp.body();
                if (!isRetryableVideoCreate(code, respBody) || attempt == maxAttempts) {
                    break;
                }
                log.warn("视频任务创建返回 {}(上游项目池故障), 第 {}/{} 次重试, body={}",
                    code, attempt, maxAttempts,
                    respBody.length() > 150 ? respBody.substring(0, 150) : respBody);
                Thread.sleep(500L * attempt);
            }
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new RuntimeException("Video API returned " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("id").asText();

            if (scene != null) {
                scene.setVideoTaskId(taskId);
                scene.setVideoStatus("generating");
                sceneMapper.updateById(scene);
            }

            return taskId;
        } catch (Exception e) {
            if (scene != null) {
                scene.setVideoStatus("failed");
                sceneMapper.updateById(scene);
            }
            throw new RuntimeException("AI 视频生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 轮询视频任务状态，完成后自动下载视频文件。
     */
    public Map<String, String> pollVideoTask(String taskId) {
        // Provider 分发：minimax（默认）走 MiniMax V2 链路，laozhang 走下方原逻辑（保留可切回）
        if ("minimax".equals(config.getVideoProvider())) {
            return minimaxVideoService.pollVideoTask(taskId);
        }
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
                boolean giveUp = false;
                if (localPath != null) {
                    result.put("status", "completed");
                    result.put("videoUrl", localPath);
                } else {
                    // 下载失败不立即判死：以 completed_at 为基准，4 分钟内持续返回
                    // processing 让调用方继续轮询（每次轮询都会重新尝试下载），
                    // 覆盖上游 "Failed to resolve Gemini video URL" 等短暂故障；
                    // 超过 4 分钟仍失败才转 failed（避免前端/工作流无限等待或僵尸状态）
                    long completedAtSec = root.path("completed_at").asLong(0);
                    giveUp = completedAtSec > 0
                            && (System.currentTimeMillis() / 1000 - completedAtSec) > 240;
                    if (giveUp) {
                        result.put("status", "failed");
                        result.put("error", "视频已生成但超过4分钟仍无法下载（上游内容服务异常），请重试");
                    } else {
                        result.put("status", "processing");
                        result.put("progress", "99");
                    }
                }

                // 仅在终态（下载成功或放弃）时更新 scene/asset；
                // processing 期间保持原状，等待下次轮询重试下载
                if (localPath != null || giveUp) {
                    var scenes = sceneMapper.selectList(
                        new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, taskId));
                    if (!scenes.isEmpty()) {
                        Scene scene = scenes.get(0);
                        scene.setVideoUrl(localPath);
                        scene.setVideoStatus(localPath != null ? "completed" : "failed");
                        sceneMapper.updateById(scene);
                    } else {
                        var assets = agentAssetMapper.selectList(
                            new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getTaskId, taskId));
                        if (!assets.isEmpty()) {
                            AgentAsset asset = assets.get(0);
                            asset.setUrl(localPath);
                            asset.setStatus(localPath != null ? "completed" : "failed");
                            asset.setError(localPath != null ? null : result.get("error"));
                            agentAssetMapper.updateById(asset);
                        }
                    }
                }
            } else if ("failed".equals(status) || "error".equals(status)) {
                result.put("status", "failed");
                // M2：先把上游错误信息填入 error（message 优先，回退 error 字段），
                // 避免后续 setError(result.get("error")) 恒为 null
                result.put("error", root.path("message").asText(root.path("error").asText("")));
                var scenes = sceneMapper.selectList(
                    new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, taskId));
                if (!scenes.isEmpty()) {
                    Scene scene = scenes.get(0);
                    scene.setVideoStatus("failed");
                    sceneMapper.updateById(scene);
                } else {
                    var assets = agentAssetMapper.selectList(
                        new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getTaskId, taskId));
                    if (!assets.isEmpty()) {
                        AgentAsset asset = assets.get(0);
                        asset.setStatus("failed");
                        asset.setError(result.get("error"));
                        agentAssetMapper.updateById(asset);
                    }
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
        long retryDelayMs = 15_000; // 单次轮询内重试窗口约 45s；跨轮询由 pollVideoTask 持续重试

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

    /**
     * 视频任务创建响应是否可安全重试（包内静态，便于直接单测）：
     * 404 = 上游池无模型权限（fail_to_fetch_task）；5xx 且 body 含
     * fail_to_fetch_task / do_request_failed（如 invalid_grant 凭据故障，发生在
     * 请求发往 Google 之前，任务未创建）→ 重试换池。
     * 其余（400 参数错、401 密钥错、普通 5xx 等）不重试，避免重复创建任务/白扣费。
     */
    static boolean isRetryableVideoCreate(int code, String body) {
        if (code == 404) return true;
        if (code < 500 || body == null) return false;
        return body.contains("fail_to_fetch_task") || body.contains("do_request_failed");
    }

    private byte[] decodeBase64Image(String base64Data) {
        String clean = base64Data;
        if (clean.contains(",") && clean.contains("base64")) {
            clean = clean.substring(clean.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(clean);
    }
}
