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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MiniMax 视频生成服务 —— Video Generation V2（MiniMax-H3）。
 *
 * 与 Laozhang 通道并存（"拓展不删"）：由 {@link VideoGenerationService} 按
 * {@code ai.video-provider} 配置分发，本类只负责 MiniMax V2 链路：
 *   POST /v2/video_generation                      创建任务（JSON，多模态 content 数组）
 *   GET  /v2/query/video_generation/{task_id}      轮询状态（queued/running/succeeded/failed）
 *   成功后 task.content.url 为限时下载链接 → 立即转存本地 uploads/videos
 *
 * 关键适配（相对 Laozhang）：
 * - 认证：Bearer MiniMax API Key（.env MINIMAX_API_KEY，不提交）；
 * - 图生视频：本地图转 data URI base64 内联（无需上传接口，请求体 ≤64MB 内安全）；
 * - 分辨率：统一最低档 768P（用户要求默认最低分辨率；档位由配置 minimaxVideoResolution 决定，768P | 2K）；
 * - 宽高比：文生视频 ratio 必填且不可 adaptive；图生视频恒为 adaptive；
 * - 时长：整数 4~15 秒（clamp）；
 * - 错误结构：OAI 风格 {@code {error:{type,message,http_code}}}，取 error.message 透传前端。
 */
@Service
public class MinimaxVideoService {

    private static final Logger log = LoggerFactory.getLogger(MinimaxVideoService.class);

    private static final String MODEL = "MiniMax-H3";
    /** MiniMax 支持的宽高比（文生视频必填、不可 adaptive） */
    private static final Set<String> RATIOS = Set.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    private static final int DURATION_MIN = 4;
    private static final int DURATION_MAX = 15;

    private final AiConfigProperties config;
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public MinimaxVideoService(AiConfigProperties config, SceneMapper sceneMapper,
                               AgentAssetMapper agentAssetMapper,
                               FileStorageService fileStorageService) {
        this.config = config;
        this.sceneMapper = sceneMapper;
        this.agentAssetMapper = agentAssetMapper;
        this.fileStorageService = fileStorageService;
    }

    /**
     * 创建视频生成任务（MiniMax V2）。
     * 入参签名与 Laozhang 通道一致（门面透传）；MiniMax 忽略 alias/negativePrompt/seed，
     * 模型固定 MiniMax-H3。返回 task_id 并落库 scene.videoTaskId（sceneId 非空时）。
     */
    public String createVideoTask(String sceneId, String prompt, String alias,
                                  String resolution, String size, String aspectRatio,
                                  Integer duration, String negativePrompt, Long seed,
                                  List<String> referenceImages, String generatedImageUrl) {
        Scene scene = sceneId != null ? sceneMapper.selectById(sceneId) : null;
        if (sceneId != null && scene == null) throw new RuntimeException("分镜不存在: " + sceneId);
        if (prompt == null || prompt.isBlank()) {
            throw new RuntimeException("视频生成 prompt 不能为空（Dify 变量可能未正确设置）");
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getMinimaxVideoModel() != null ? config.getMinimaxVideoModel() : MODEL);

            // ── content 多模态数组：text 必填 + 首帧图（图生视频）──
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", prompt));

            // 首帧图：优先已生成图片（本地文件 → data URI），其次参考图（base64 → data URI）
            String firstFrameDataUri = resolveFirstFrame(generatedImageUrl, referenceImages);
            if (firstFrameDataUri != null) {
                Map<String, Object> img = new HashMap<>();
                img.put("type", "image_url");
                Map<String, String> imageUrl = new HashMap<>();
                imageUrl.put("url", firstFrameDataUri);
                img.put("image_url", imageUrl);
                img.put("role", "first_frame");
                content.add(img);
            }
            body.put("content", content);

            // ── 分辨率：统一使用配置默认档（默认 768P = 最低档）──
            // 2026-08-06 用户要求"默认使用最低分辨率"：不再透传调用方显式 2K，
            // 无论调用方传什么（720p/1080p/4k/2K）一律按 minimaxVideoResolution 生成，
            // 省钱且生成更快。如需切换档位改配置即可（768P | 2K）。
            String effResolution = config.getMinimaxVideoResolution();
            body.put("resolution", effResolution);

            // ── 时长：clamp 4~15，默认 8 ──
            int effDuration = duration != null ? duration : 8;
            effDuration = Math.max(DURATION_MIN, Math.min(DURATION_MAX, effDuration));
            body.put("duration", effDuration);

            // ── 宽高比：图生视频恒 adaptive；文生视频必填具体比例（非法降级 16:9）──
            if (firstFrameDataUri != null) {
                body.put("ratio", "adaptive");
            } else {
                String effRatio = aspectRatio != null && RATIOS.contains(aspectRatio)
                        ? aspectRatio : "16:9";
                body.put("ratio", effRatio);
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getMinimaxBaseUrl() + "/v2/video_generation"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getMinimaxApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            // 轻量重试：仅 429 限流 / 5xx 服务端错误重试 3 次（MiniMax 稳定通道，无需 Laozhang 的 10 次换池）
            HttpResponse<String> resp = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 429 && resp.statusCode() < 500) break;
                log.warn("MiniMax 视频任务创建返回 {}，第 {}/3 次重试, body={}",
                        resp.statusCode(), attempt, truncate(resp.body()));
                Thread.sleep(1000L * attempt);
            }
            if (resp.statusCode() != 200) {
                throw new RuntimeException("MiniMax 视频创建失败: " + extractError(resp.statusCode(), resp.body()));
            }

            JsonNode root = objectMapper.readTree(resp.body());
            String taskId = root.path("task_id").asText(
                    root.path("task").path("id").asText(""));
            if (taskId.isBlank()) {
                throw new RuntimeException("MiniMax 视频创建响应缺少 task_id: " + truncate(resp.body()));
            }

            if (scene != null) {
                scene.setVideoTaskId(taskId);
                scene.setVideoStatus("generating");
                sceneMapper.updateById(scene);
            }
            log.info("MiniMax 视频任务已创建: taskId={}, resolution={}, duration={}",
                    taskId, effResolution, effDuration);
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
     * 轮询视频任务状态，成功后下载并转存本地。
     * 状态映射：succeeded→completed（下载 content.url 到 uploads/videos）；
     * failed→failed（透传 error.message）；queued/running→processing。
     */
    public Map<String, String> pollVideoTask(String taskId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getMinimaxBaseUrl() + "/v2/query/video_generation/" + taskId))
                .header("Authorization", "Bearer " + config.getMinimaxApiKey())
                .timeout(Duration.ofSeconds(120))
                .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of("status", "failed",
                        "error", "MiniMax 任务查询失败: " + extractError(resp.statusCode(), resp.body()));
            }

            JsonNode task = objectMapper.readTree(resp.body()).path("task");
            String status = task.path("status").asText("");

            Map<String, String> result = new HashMap<>();
            result.put("taskId", taskId);

            if ("succeeded".equals(status)) {
                String videoUrl = task.path("content").path("url").asText("");
                // 限时下载链接：立即转存本地，避免过期 403
                String localPath = downloadVideo(videoUrl);
                boolean giveUp = false;
                if (localPath != null) {
                    result.put("status", "completed");
                    result.put("videoUrl", localPath);
                } else {
                    // 下载失败不立即判死：以 updated_at 为基准 4 分钟内持续返回 processing
                    // 让调用方继续轮询（每次轮询重新尝试下载）
                    long updatedAtSec = task.path("updated_at").asLong(0);
                    giveUp = updatedAtSec > 0
                            && (System.currentTimeMillis() / 1000 - updatedAtSec) > 240;
                    if (giveUp) {
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
                String err = task.path("error").path("message").asText(
                        task.path("error").path("code").asText(""));
                result.put("error", err.isBlank() ? "视频生成失败" : err);
                updateTaskOwner(taskId, null, "failed", result.get("error"));
            } else {
                // queued / running / cancelled / 未知 → 继续轮询
                result.put("status", "processing");
                result.put("progress", "running".equals(status) ? "50" : "");
            }
            return result;
        } catch (Exception e) {
            return Map.of("status", "failed", "error", e.getMessage());
        }
    }

    /** 下载限时 URL 到本地 uploads/videos；先无鉴权直下，403 时带 Bearer 重试（签名 URL 可能要求鉴权） */
    private String downloadVideo(String url) {
        if (url == null || url.isBlank()) return null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(180))
                    .GET();
                HttpResponse<InputStream> resp = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                // 403 可能要求鉴权：带 Bearer 重试一次
                if (resp.statusCode() == 403 && attempt == 1) {
                    closeQuietly(resp.body());
                    resp = httpClient.send(builder.header("Authorization",
                            "Bearer " + config.getMinimaxApiKey()).build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                }
                if (resp.statusCode() == 200) {
                    String filename = UUID.randomUUID() + config.getVideoFileExtension();
                    Path target = Paths.get(config.getVideoUploadDir()).resolve(filename);
                    Files.createDirectories(target.getParent());
                    try (InputStream in = resp.body()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.info("MiniMax 视频已转存本地: {} (attempt {}/{})", target, attempt, 3);
                    return config.getVideoUrlPrefix() + filename;
                }
                // 先读取错误体再关闭流（顺序颠倒会读到空串）
                String errBody = readBody(resp);
                closeQuietly(resp.body());
                log.warn("MiniMax 视频下载返回 {} (attempt {}/{}): {}",
                        resp.statusCode(), attempt, 3, truncate(errBody));
            } catch (Exception e) {
                log.warn("MiniMax 视频下载失败 (attempt {}/{}): {}", attempt, 3, e.getMessage());
            }
            if (attempt < 3) {
                try { Thread.sleep(15_000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        log.error("MiniMax 视频下载失败(3 次尝试): {}", url);
        return null;
    }

    /** 终态更新 scene / agent_asset（双通道反查，与 Laozhang 通道同语义） */
    private void updateTaskOwner(String taskId, String videoUrl, String status, String error) {
        var scenes = sceneMapper.selectList(new LambdaQueryWrapper<Scene>()
                .eq(Scene::getVideoTaskId, taskId));
        if (!scenes.isEmpty()) {
            Scene scene = scenes.get(0);
            scene.setVideoUrl(videoUrl);
            scene.setVideoStatus(status);
            sceneMapper.updateById(scene);
            return;
        }
        var assets = agentAssetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getTaskId, taskId));
        if (!assets.isEmpty()) {
            AgentAsset asset = assets.get(0);
            asset.setUrl(videoUrl);
            asset.setStatus(status);
            asset.setError(error);
            agentAssetMapper.updateById(asset);
        }
    }

    /**
     * 解析首帧图 data URI：
     * - generatedImageUrl（本地 /api/files/images/xxx.png）→ 读文件转 base64 data URI；
     * - 否则 referenceImages[0]（已是 data URI 原样返回，裸 base64 补齐前缀）。
     */
    private String resolveFirstFrame(String generatedImageUrl, List<String> referenceImages) {
        if (generatedImageUrl != null && !generatedImageUrl.isBlank()) {
            try {
                String filename = generatedImageUrl.substring(generatedImageUrl.lastIndexOf('/') + 1);
                Path localFile = fileStorageService.resolveImage(filename);
                if (Files.exists(localFile)) {
                    byte[] bytes = Files.readAllBytes(localFile);
                    String mime = fileStorageService.contentType(filename);
                    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
                }
                log.warn("MiniMax 首帧图本地文件不存在: {}", localFile);
            } catch (Exception e) {
                log.warn("MiniMax 首帧图读取失败: {}", e.getMessage());
            }
            return null;
        }
        if (referenceImages != null && !referenceImages.isEmpty()) {
            String base64 = referenceImages.get(0);
            if (base64 == null || base64.isBlank()) return null;
            // 已是 data URI 原样返回；裸 base64 补齐前缀
            if (base64.startsWith("data:")) return base64;
            if (base64.contains(",") && base64.contains("base64")) {
                return base64.substring(base64.indexOf("data:"));
            }
            return "data:image/png;base64," + base64;
        }
        return null;
    }

    /** 提取 OAI 风格错误信息（{error:{message}}），取不到则回退 HTTP 码 + body 截断 */
    private String extractError(int statusCode, String body) {
        try {
            JsonNode err = objectMapper.readTree(body).path("error");
            String msg = err.path("message").asText("");
            if (!msg.isBlank()) return msg + " (HTTP " + statusCode + ")";
        } catch (Exception ignored) {
            // 非 JSON 错误体，走下方兜底
        }
        return "HTTP " + statusCode + (body != null && !body.isBlank() ? ": " + truncate(body) : "");
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try { in.close(); } catch (Exception ignored) { }
    }

    private static String readBody(HttpResponse<InputStream> resp) {
        try { return new String(resp.body().readAllBytes()); } catch (Exception e) { return ""; }
    }
}
