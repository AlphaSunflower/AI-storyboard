package com.moon.moonagent.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.MinimaxVideoService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.storyboard.common.BusinessException;

/**
 * MiniMax 视频生成服务实现 —— 网关通道（协议转换已下沉 LLM 网关）。
 */
@Service
@RequiredArgsConstructor
public class MinimaxVideoServiceImpl implements MinimaxVideoService {

    private static final Logger log = LoggerFactory.getLogger(MinimaxVideoServiceImpl.class);

    private final AiConfigProperties config;
    private final GatewayModelService gatewayModelService;
    private final StoryboardClient storyboardClient;
    private final AgentAssetMapper agentAssetMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 4 分钟 giveUp 窗口基准：网关统一响应不含上游完成时间戳，
     * 以本地首次观察到 succeeded 且下载失败的时刻为基准计时。
     */
    private final Map<String, Long> firstSucceededAt = new ConcurrentHashMap<>();

    /**
     * 创建视频生成任务（统一走网关 POST /v1/videos）。
     * 返回 task_id / id / taskId 任一解析出的 taskId 并落库 scene.videoTaskId（sceneId 非空时）。
     */
    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                  String resolution, String size, String aspectRatio,
                                  Integer duration, String negativePrompt, Long seed,
                                  List<String> referenceImages, String generatedImageUrl) {
        Scene scene = sceneId != null ? storyboardClient.getScene(sceneId) : null;
        if (sceneId != null && scene == null) throw new BusinessException(40401, "资源不存在: " + sceneId);
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(40001, "视频生成 prompt 不能为空（Dify 变量可能未正确设置）");
        }

        String actualModel = alias != null
                ? config.getVideoModelAliasMap().getOrDefault(alias, alias)
                : gatewayModelService.getDefaultVideoModel();  // 默认模型：网关 model_params.is_default（MiniMax 直连自家，非 laozhang）

        try {
            // 统一 OpenAI 风格 JSON 请求体（模型→渠道路由、协议转换已下沉网关）
            Map<String, Object> body = new HashMap<>();
            body.put("model", actualModel);
            body.put("prompt", prompt);
            // 未显式传值的参数不下发，由网关兜底默认（768P/8s/16:9/1280x720，与旧 config 默认一致）
            if (size != null) body.put("size", size);
            if (resolution != null) body.put("resolution", resolution);
            if (aspectRatio != null) body.put("aspectRatio", aspectRatio);
            if (duration != null) body.put("duration", duration);
            if (negativePrompt != null && !negativePrompt.isEmpty()) {
                body.put("negativePrompt", negativePrompt);
            }
            if (seed != null) {
                body.put("seed", seed);
            }

            // 图生视频 imageUrl：优先已生成图片（本地文件 → data URI 内联——
            // 图片在业务 uploads 目录，网关无权限访问，设计 §6.2），其次参考图第一张
            if (generatedImageUrl != null && !generatedImageUrl.isEmpty()) {
                String filename = extractFilename(generatedImageUrl);
                Path localFile = fileStorageService.resolveImage(filename);
                if (Files.exists(localFile)) {
                    byte[] bytes = Files.readAllBytes(localFile);
                    String mime = FileStorageService.contentType(filename);
                    body.put("imageUrl", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
                } else {
                    log.warn("Reference image not found: {}", localFile);
                }
            } else if (referenceImages != null && !referenceImages.isEmpty()) {
                body.put("imageUrl", normalizeImageUrl(referenceImages.get(0)));
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getGatewayApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new BusinessException(50201, "Video API returned " + resp.statusCode() + ": " + resp.body());
            }
            // 网关透传上游响应：task_id（minimax）/ id / taskId（laozhang）三选一
            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("task_id").asText(
                    root.path("id").asText(root.path("taskId").asText("")));
            if (taskId.isBlank()) {
                throw new BusinessException(50201, "视频创建响应缺少 taskId: " + resp.body());
            }

            if (scene != null) {
                scene.setVideoTaskId(taskId);
                scene.setVideoStatus("generating");
                storyboardClient.updateScene(scene);
            }
            log.info("视频任务已创建(网关): taskId={}, model={}, resolution={}, duration={}",
                    taskId, actualModel, resolution, duration);
            return taskId;
        } catch (Exception e) {
            if (scene != null) {
                scene.setVideoStatus("failed");
                storyboardClient.updateScene(scene);
            }
            throw new BusinessException(50201, "AI 视频生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 轮询视频任务状态，成功后经网关下载并转存本地（统一响应处理）。
     * 状态映射：succeeded→completed（网关下载到 uploads/videos）；
     * failed→failed（透传 error）；processing→processing。
     */
    @Override
    public Map<String, String> pollVideoTask(String taskId) {
        try {
            String respBody = callGet(config.getGatewayBaseUrl() + "/v1/videos/" + taskId);
            if (respBody == null) {
                return Map.of("status", "failed", "error", "视频任务查询失败: taskId=" + taskId);
            }

            log.info("Video poll response: {}", respBody);
            JsonNode root = objectMapper.readTree(respBody);
            String status = root.path("status").asText("");

            Map<String, String> result = new HashMap<>();
            result.put("taskId", taskId);

            if ("succeeded".equals(status)) {
                String localPath = downloadVideoContent(taskId);
                boolean giveUp = false;
                if (localPath != null) {
                    firstSucceededAt.remove(taskId);
                    result.put("status", "completed");
                    result.put("videoUrl", localPath);
                } else {
                    // 下载失败不立即判死：4 分钟内持续返回 processing 让调用方继续轮询，
                    // 每次轮询重新尝试下载；超过 4 分钟仍失败才转 failed
                    long firstSeenMs = firstSucceededAt.computeIfAbsent(taskId, k -> System.currentTimeMillis());
                    giveUp = (System.currentTimeMillis() - firstSeenMs) > 240_000;
                    if (giveUp) {
                        firstSucceededAt.remove(taskId);
                        result.put("status", "failed");
                        result.put("error", "视频已生成但超过4分钟仍无法下载（上游内容服务异常），请重试");
                    } else {
                        result.put("status", "processing");
                        result.put("progress", "99");
                    }
                }
                if (localPath != null || giveUp) {
                    updateTaskOwner(taskId, localPath, localPath != null ? "completed" : "failed",
                            result.get("error"));
                }
            } else if ("failed".equals(status)) {
                result.put("status", "failed");
                // 网关统一响应 failed 时带 error 字段，直接透传
                String err = root.path("error").asText("");
                result.put("error", err.isBlank() ? "视频生成失败" : err);
                updateTaskOwner(taskId, null, "failed", result.get("error"));
            } else {
                result.put("status", "processing");
                result.put("progress", root.path("progress").asText(""));
            }
            return result;
        } catch (Exception e) {
            return Map.of("status", "failed", "error", e.getMessage());
        }
    }

    /** 从网关下载视频流并转存本地 uploads/videos（重试 3 次，每次间隔 15s） */
    private String downloadVideoContent(String taskId) {
        int maxRetries = 3;
        long retryDelayMs = 15_000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos/" + taskId + "/content"))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(180))
                    .GET().build();
                HttpResponse<InputStream> resp = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() == 200) {
                    String filename = UUID.randomUUID() + config.getVideoFileExtension();
                    Path target = Paths.get(config.getVideoUploadDir()).resolve(filename);
                    Files.createDirectories(target.getParent());
                    try (InputStream in = resp.body()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.info("视频已转存本地: {} (attempt {}/{})", target, attempt, maxRetries);
                    return config.getVideoUrlPrefix() + filename;
                }

                String respBody = "";
                try { respBody = new String(resp.body().readAllBytes()); } catch (Exception ignored) {}
                log.warn("视频内容下载返回 {} (attempt {}/{}): {}",
                        resp.statusCode(), attempt, maxRetries,
                        respBody.length() > 200 ? respBody.substring(0, 200) : respBody);
            } catch (Exception e) {
                log.warn("视频内容下载失败 (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());
            }
            if (attempt < maxRetries) {
                try { Thread.sleep(retryDelayMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        log.error("视频内容下载失败({} 次尝试): taskId={}", maxRetries, taskId);
        return null;
    }

    /** 终态更新 scene / agent_asset（双通道反查，scene.videoTaskId 优先） */
    private void updateTaskOwner(String taskId, String videoUrl, String status, String error) {
        Scene scene = storyboardClient.getSceneByVideoTaskId(taskId);
        if (scene != null) {
            scene.setVideoUrl(videoUrl);
            scene.setVideoStatus(status);
            storyboardClient.updateScene(scene);
            return;
        }
        var assets = agentAssetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getTaskId, taskId));
        if (!assets.isEmpty()) {
            AgentAsset asset = assets.getFirst();
            asset.setUrl(videoUrl);
            asset.setStatus(status);
            asset.setError(error);
            agentAssetMapper.updateById(asset);
        }
    }

    /** GET 网关端点（Bearer 网关 Key），非 200 或异常返回 null */
    private String callGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.getGatewayApiKey())
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
     * 参考图 data URI 归一化：已是 data URI 原样返回；裸 base64 补齐前缀
     * （协议转换已下沉网关，业务侧只需保证 imageUrl 是合法 data URI）。
     */
    private String normalizeImageUrl(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        if (base64.startsWith("data:")) return base64;
        if (base64.contains(",") && base64.contains("base64")) {
            return base64.substring(base64.indexOf("data:"));
        }
        return "data:image/png;base64," + base64;
    }
}
