package com.storyboard.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.MultipartBuilder;
import com.storyboard.service.ai.VideoGenerationService;
import lombok.RequiredArgsConstructor;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频生成服务实现 —— 创建/轮询/下载统一走 LLM 网关（/v1/videos）。
 */
@Service
@RequiredArgsConstructor
public class VideoGenerationServiceImpl implements VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationServiceImpl.class);

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 4 分钟 giveUp 窗口基准：网关统一响应 {taskId,status,progress?,error?} 不含
     * 上游完成时间戳，故以本地首次观察到 succeeded 且下载失败的时刻为基准计时；
     * 窗口内每次轮询都重新尝试下载，超过 4 分钟仍失败才转 failed（避免僵尸状态）。
     */
    private final Map<String, Long> firstSucceededAt = new ConcurrentHashMap<>();

    /**
     * 创建视频生成任务（统一走网关 POST /v1/videos，JSON 体，OpenAI 风格格式）。
     * 响应解析 task_id / id / taskId 任一 → 返回 taskId 落库（scene.videoTaskId 逻辑不变）。
     */
    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, String size, String aspectRatio,
                                   Integer duration, String negativePrompt, Long seed,
                                   List<String> referenceImages, String generatedImageUrl) {
        return createVideoTask(sceneId, prompt, alias, resolution, size, aspectRatio,
                duration, negativePrompt, seed, referenceImages, generatedImageUrl, null, null);
    }

    /**
     * 创建视频生成任务（多模态参考素材版）。
     * 参考素材任一存在 → 多模态参考模式（r2va，不传首帧 imageUrl）；否则沿用首帧/文生视频逻辑。
     */
    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                   String resolution, String size, String aspectRatio,
                                   Integer duration, String negativePrompt, Long seed,
                                   List<String> referenceImages, String generatedImageUrl,
                                   List<String> referenceVideos, List<String> referenceAudios) {
        Scene scene = sceneId != null ? sceneMapper.selectById(sceneId) : null;
        if (sceneId != null && scene == null) throw new RuntimeException("分镜不存在: " + sceneId);

        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("视频生成 prompt 不能为空（Dify 变量可能未正确设置）");
        }

        String actualModel = alias != null
                ? config.getVideoModelAliasMap().getOrDefault(alias, alias)
                : (config.getMinimaxVideoModel() != null ? config.getMinimaxVideoModel() : "MiniMax-H3");  // 默认模型：MiniMax-H3（修复通道翻转回归：默认走 minimax 渠道，显式传 veo 别名才走 laozhang）

        // 使用请求参数或配置默认值
        String effSize = size != null ? size : config.getDefaultVideoSize();
        String effResolution = resolution != null ? resolution : config.getDefaultVideoResolution();
        String effAspectRatio = aspectRatio != null ? aspectRatio : config.getDefaultVideoAspectRatio();
        int effDuration = duration != null ? duration : Integer.parseInt(config.getDefaultVideoDuration());

        try {
            // 统一 OpenAI 风格 JSON 请求体（模型→渠道路由、协议转换已下沉网关）
            Map<String, Object> body = new HashMap<>();
            body.put("model", actualModel);          // alias 映射保留在业务侧
            body.put("prompt", prompt);
            body.put("size", effSize);
            body.put("resolution", effResolution);
            body.put("aspectRatio", effAspectRatio);
            body.put("duration", effDuration);
            if (negativePrompt != null && !negativePrompt.isEmpty()) {
                body.put("negativePrompt", negativePrompt);
            }
            if (seed != null) {
                body.put("seed", seed);
            }

            // 参考素材转换：任一存在 → 多模态参考模式（r2va），不再传首帧 imageUrl
            // 本地文件（/api/files/... 相对路径）→ 小文件(≤10MB) data URI 内联 / 大文件经网关上传代理换 mm_file://
            List<String> refImages = new ArrayList<>();
            List<String> refVideos = new ArrayList<>();
            List<String> refAudios = new ArrayList<>();
            if (referenceVideos != null) {
                for (String v : referenceVideos) { String u = resolveReferenceUrl(v, "video"); if (u != null) refVideos.add(u); }
            }
            if (referenceAudios != null) {
                for (String a : referenceAudios) { String u = resolveReferenceUrl(a, "audio"); if (u != null) refAudios.add(u); }
            }
            if (referenceImages != null) {
                for (String i : referenceImages) { String u = resolveReferenceUrl(i, "image"); if (u != null) refImages.add(u); }
            }
            boolean hasRefs = !refImages.isEmpty() || !refVideos.isEmpty() || !refAudios.isEmpty();
            if (hasRefs) {
                body.put("referenceImages", refImages);
                body.put("referenceVideos", refVideos);
                body.put("referenceAudios", refAudios);
            } else {
                // 首帧图生视频 imageUrl：优先已生成图片（本地文件 → data URI 内联——
                // 图片在业务 uploads 目录，网关无权限访问，设计 §6.2 明确业务侧保留此转换），
                // 其次参考图第一张（base64 已有，直接用）
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
                    body.put("imageUrl", normalizeImageUrl(referenceImages.getFirst()));
                }
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
                throw new RuntimeException("Video API returned " + resp.statusCode() + ": " + resp.body());
            }
            // 网关透传上游响应：task_id（minimax）/ id / taskId（laozhang）三选一
            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("task_id").asText(
                    root.path("id").asText(root.path("taskId").asText("")));
            if (taskId.isBlank()) {
                throw new RuntimeException("视频创建响应缺少 taskId: " + resp.body());
            }

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
     * 轮询视频任务状态，完成后自动下载视频文件（统一走网关）。
     * 网关统一响应 {taskId, status, progress?, error?}（status: processing/succeeded/failed）：
     *   succeeded → GET 网关下载端点拿视频流 → 本地转存 uploads/videos → {status:completed, videoUrl}
     *   failed   → 透传 error
     *   processing → 返回 processing（透传 progress）
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
                    // 下载失败不立即判死：以本地首次观察到 succeeded 的时刻为基准，4 分钟内
                    // 持续返回 processing 让调用方继续轮询（每次轮询都会重新尝试下载），
                    // 覆盖网关/上游短暂故障；超过 4 分钟仍失败才转 failed
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

                // 仅在终态（下载成功或放弃）时更新 scene/asset；
                // processing 期间保持原状，等待下次轮询重试下载
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
        long retryDelayMs = 15_000; // 单次轮询内重试窗口约 45s；跨轮询由 pollVideoTask 持续重试

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/videos/" + taskId + "/content"))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
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

    /** 终态更新 scene / agent_asset（双通道反查，scene.videoTaskId 优先） */
    private void updateTaskOwner(String taskId, String videoUrl, String status, String error) {
        var scenes = sceneMapper.selectList(
            new LambdaQueryWrapper<Scene>().eq(Scene::getVideoTaskId, taskId));
        if (!scenes.isEmpty()) {
            Scene scene = scenes.getFirst();
            scene.setVideoUrl(videoUrl);
            scene.setVideoStatus(status);
            sceneMapper.updateById(scene);
            return;
        }
        var assets = agentAssetMapper.selectList(
            new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getTaskId, taskId));
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
     * 参考素材 data URI 归一化：已是 data URI 原样返回；裸 base64 补齐前缀
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

    /** 参考素材转换：已内联/绝对 URL 原样返回；本地文件 ≤10MB 转 data URI，大文件经网关上传代理换 mm_file:// */
    private String resolveReferenceUrl(String urlPath, String type) {
        if (urlPath == null || urlPath.isBlank()) return null;
        if (urlPath.startsWith("data:") || urlPath.startsWith("http") || urlPath.startsWith("mm_file://")) {
            return urlPath;
        }
        String filename = extractFilename(urlPath);
        Path localFile = switch (type) {
            case "video" -> fileStorageService.resolveVideo(filename);
            case "audio" -> fileStorageService.resolveAudio(filename);
            default -> fileStorageService.resolveImage(filename);
        };
        if (!Files.exists(localFile)) {
            log.warn("参考素材文件不存在: {}", localFile);
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(localFile);
            if (bytes.length <= 10 * 1024 * 1024) {
                String mime = FileStorageService.contentType(filename);
                return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
            }
            // 大文件：multipart 字节流 → 网关 /v1/files/upload → mm_file://{file_id}
            // （base64 膨胀 ~33% 会爆上游 64MB 请求体限制，必须走平台 file_id 引用）
            MultipartBuilder mp = new MultipartBuilder()
                    .file("file", filename, mimeOf(filename), bytes);
            byte[] bodyBytes = mp.build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/files/upload"))
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("参考素材上传失败: " + resp.statusCode() + " " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String fileId = root.path("file_id").asText("");
            if (fileId.isBlank()) {
                throw new RuntimeException("参考素材上传响应缺少 file_id: " + resp.body());
            }
            return fileId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("参考素材转换失败: " + e.getMessage(), e);
        }
    }

    /** 参考素材 MIME 推断（multipart 上传用） */
    private String mimeOf(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            default -> "audio/mpeg";
        };
    }
}
