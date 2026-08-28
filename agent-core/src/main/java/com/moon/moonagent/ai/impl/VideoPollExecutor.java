package com.moon.moonagent.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.SceneView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * agent-core 视频轮询/下载/归属更新公共执行器 —— 两个视频服务（VideoGenerationServiceImpl /
 * MinimaxVideoServiceImpl）共用，去重 poll→下载→4min give-up→scene/asset 归属更新的重复流程。
 * <p>
 * 只负责「轮询 + 下载 + 终态归属更新」；视频创建的请求体组装因各服务差异大而保留在各服务内。
 */
@Component
@RequiredArgsConstructor
public class VideoPollExecutor {

    private static final Logger log = LoggerFactory.getLogger(VideoPollExecutor.class);

    /** 4 分钟 giveUp 窗口基准：网关统一响应不含上游完成时间戳，以本地首次观察到 succeeded 且下载失败时刻为基准计时 */
    private final Map<String, Long> firstSucceededAt = new ConcurrentHashMap<>();

    private final AiConfigProperties config;
    private final AgentAssetMapper agentAssetMapper;
    private final SceneGenerationBridge sceneBridge;
    private final com.moon.moonagent.storage.MinioStorage minioStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 轮询视频任务状态，成功后经网关下载并转存本地（统一响应处理）。
     * 状态映射：succeeded→completed（网关下载到 uploads/videos）；failed→failed（透传 error）；processing→processing。
     */
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
                    safeUpdateTaskOwner(taskId, localPath, localPath != null ? "completed" : "failed",
                            result.get("error"));
                }
            } else if ("failed".equals(status)) {
                result.put("status", "failed");
                String err = root.path("error").asText("");
                result.put("error", err.isBlank() ? "视频生成失败" : err);
                safeUpdateTaskOwner(taskId, null, "failed", result.get("error"));
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
        long retryDelayMs = config.getVideoRetryDelayMs();
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos/" + taskId + "/content"))
                        .header("Authorization", "Bearer " + config.getGatewayApiKey())
                        .timeout(Duration.ofSeconds(config.getVideoDownloadTimeout()))
                        .GET().build();
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() == 200) {
                    byte[] bytes;
                    try (InputStream in = resp.body()) {
                        bytes = in.readAllBytes();
                    }
                    String filename = UUID.randomUUID() + config.getVideoFileExtension();
                    // 视频字节落 MinIO（统一对象存储；读端主后端 FileController 经 FileStorageService.readVideo → minioStorage.get）
                    minioStorage.put(minioStorage.key("videos", filename), bytes, "video/mp4");
                    log.info("视频已转存 MinIO: videos/{} ({} 字节) (attempt {}/{})", filename, bytes.length, attempt, maxRetries);
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
                try { Thread.sleep(retryDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        log.error("视频内容下载失败({} 次尝试): taskId={}", maxRetries, taskId);
        return null;
    }

    /** 终态更新 scene / agent_asset（双通道反查，scene.videoTaskId 优先） */
    private void updateTaskOwner(String taskId, String videoUrl, String status, String error) {
        SceneView scene = sceneBridge.getSceneByVideoTaskId(taskId);
        if (scene != null) {
            scene.videoUrl = videoUrl;
            scene.videoStatus = status;
            sceneBridge.updateScene(scene);
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

    /** 安全调用 updateTaskOwner：失败仅打 warn，不阻断 pollVideoTask 返回值 */
    private void safeUpdateTaskOwner(String taskId, String videoUrl, String status, String error) {
        try {
            updateTaskOwner(taskId, videoUrl, status, error);
        } catch (Exception e) {
            log.warn("updateTaskOwner 失败(不影响视频结果): taskId={}, 原因: {}", taskId, e.getMessage());
        }
    }

    /** GET 网关端点（Bearer 网关 Key），非 200 或异常返回 null */
    private String callGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(config.getVideoRequestTimeout()))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            log.warn("GET {} returned {}", url, resp.statusCode());
        } catch (Exception e) {
            log.warn("GET {} failed: {}", url, e.getMessage());
        }
        return null;
    }
}
