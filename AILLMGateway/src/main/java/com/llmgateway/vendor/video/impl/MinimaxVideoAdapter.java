package com.llmgateway.vendor.video.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.config.GatewayConfig;
import com.llmgateway.entity.Channel;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.vendor.video.dto.MinimaxVideoResult;
import com.llmgateway.vendor.video.dto.VendorCreateInput;
import com.llmgateway.vendor.video.dto.VideoVendorResult;
import com.llmgateway.vendor.video.spi.VideoVendorAdapter;
import com.llmgateway.vendor.video.model.ReferenceImage;
import com.llmgateway.vendor.video.model.ReferenceImageRole;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MiniMax 视频协议适配器（channel.type = "minimax"）。
 * <p>
 * 自含：请求载荷（JSON content 数组，多模态参考素材 / 首帧）、创建/轮询响应解析、重试策略。
 * 与 Laozhang 协议的差异全部封闭在此，调用方零感知。
 */
@Component
@RequiredArgsConstructor
public class MinimaxVideoAdapter implements VideoVendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(MinimaxVideoAdapter.class);

    /** MiniMax ratio 可用值白名单（t2v 纯文本场景；i2v 恒 adaptive） */
    private static final Set<String> RATIOS = Set.of("16:9", "4:3", "1:1", "3:4", "9:16", "21:9");

    private final GatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** file-retrieve 二次请求用（下载直链），独立于编排器的创建/轮询发送 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public String channelType() {
        return "minimax";
    }

    @Override
    public int maxCreateAttempts() {
        return 3;
    }

    @Override
    public boolean isRetryableCreate(int status, String body) {
        // 429 限流 / 5xx 服务端错误可重试；其余（400 参数错/401 密钥错）直接返回，避免重复建任务/白扣费
        return status == 429 || status >= 500;
    }

    @Override
    public long createBackoffMs(int attempt) {
        return 500L * attempt;
    }

    @Override
    public HttpRequest buildCreateRequest(VendorCreateInput in, Channel channel, String apiKey) {
        // 旧版接口：将 referenceImages 转为 ReferenceImage 列表（默认 REFERENCE role），委托新版
        List<ReferenceImage> refImages = new ArrayList<>();
        if (in.referenceImages() != null) {
            for (String url : in.referenceImages()) {
                refImages.add(ReferenceImage.ofReference(url));
            }
        }
        return buildCreateRequest(in, channel, apiKey, refImages);
    }

    /**
     * 构建创建请求（新版：支持带 role 的参考图列表）。
     * <p>
     * MiniMax role 映射：
     * <ul>
     *   <li>FIRST_FRAME → "first_frame"（首帧，锁定起始画面）</li>
     *   <li>LAST_FRAME → "last_frame"（尾帧，锁定结束画面）</li>
     *   <li>REFERENCE → "reference_image"（参考图，模型自由演绎）</li>
     * </ul>
     */
    @Override
    public HttpRequest buildCreateRequest(VendorCreateInput in, Channel channel, String apiKey,
                                          List<ReferenceImage> referenceImages) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", in.model());

        ArrayNode content = payload.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", in.prompt());

        // 多模态参考素材（r2va）：referenceImages / referenceVideos / referenceAudios；与首帧 imageUrl 互斥
        List<String> refVideos = nvl(in.referenceVideos());
        List<String> refAudios = nvl(in.referenceAudios());
        boolean hasRefs = (referenceImages != null && !referenceImages.isEmpty())
                || !refVideos.isEmpty() || !refAudios.isEmpty();
        String imageUrl = in.imageUrl() == null ? "" : in.imageUrl();
        if (hasRefs && !imageUrl.isBlank()) {
            throw new BusinessException(40001, "多模态参考素材与首帧图生视频互斥，不能同时提交");
        }

        // 参考图（带 role 映射）
        if (referenceImages != null) {
            for (ReferenceImage ref : referenceImages) {
                if (ref.url() == null || ref.url().isBlank()) continue;
                ObjectNode img = content.addObject();
                img.put("type", "image_url");
                img.put("image_url", objectMapper.createObjectNode().put("url", ref.url()));
                // 根据 role 映射到 MiniMax 特定的 role 值
                String minimaxRole = mapRoleToMinimax(ref.role());
                img.put("role", minimaxRole);
                log.debug("[MiniMax] 参考图 role 映射: {} -> {}", ref.role(), minimaxRole);
            }
        }

        // 参考视频
        for (String ref : refVideos) {
            ObjectNode vid = content.addObject();
            vid.put("type", "video_url");
            vid.put("video_url", objectMapper.createObjectNode().put("url", ref));
            vid.put("role", "reference_video");
        }
        // 参考音频
        for (String ref : refAudios) {
            ObjectNode aud = content.addObject();
            aud.put("type", "audio_url");
            aud.put("audio_url", objectMapper.createObjectNode().put("url", ref));
            aud.put("role", "reference_audio");
        }
        // 首帧（旧版 imageUrl 兼容）
        if (!imageUrl.isBlank()) {
            ObjectNode img = content.addObject();
            img.put("type", "image_url");
            img.put("image_url", objectMapper.createObjectNode().put("url", imageUrl));
            img.put("role", "first_frame");
        }

        // 分辨率：显式传值则透传，未传回落默认档（默认 768P）
        String resolution = in.resolution() == null ? "" : in.resolution();
        payload.put("resolution", resolution.isBlank()
                ? (config.getVideoDefaultResolution() == null ? "768P" : config.getVideoDefaultResolution())
                : resolution);
        int duration = in.duration() == null ? 8 : in.duration();
        payload.put("duration", Math.max(4, Math.min(15, duration)));

        // ratio 语义：i2v 恒 "adaptive"；t2v 必须显式指定可用值，缺省/非法回落 16:9
        boolean isImageToVideo = !imageUrl.isBlank();
        if (isImageToVideo) {
            payload.put("ratio", "adaptive");
        } else {
            String ratio = in.aspectRatio() == null ? "" : in.aspectRatio();
            payload.put("ratio", RATIOS.contains(ratio) ? ratio : "16:9");
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(50201, "MiniMax 请求体序列化失败: " + e.getMessage());
        }
        log.info("[MiniMax] 构建视频创建请求: model={}, refImageCount={}, hasFirstFrame={}",
                in.model(), referenceImages != null ? referenceImages.size() : 0, !imageUrl.isBlank());
        return HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(channel.getBaseUrl()) + "/v2/video_generation"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * 将通用 ReferenceImageRole 映射为 MiniMax 特定的 role 字符串。
     */
    private String mapRoleToMinimax(ReferenceImageRole role) {
        if (role == null) return "reference_image";
        return switch (role) {
            case FIRST_FRAME -> "first_frame";
            case LAST_FRAME -> "last_frame";
            case REFERENCE -> "reference_image";
        };
    }

    @Override
    public VideoVendorResult parseCreate(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String taskId = root.path("task_id").asText("");
            return new MinimaxVideoResult("minimax", taskId, "", null, null, null, null, null);
        } catch (Exception e) {
            throw new BusinessException(50201, "MiniMax 创建响应解析失败: " + e.getMessage());
        }
    }

    @Override
    public VideoVendorResult parsePoll(String rawBody, String taskId, Channel channel, String apiKey) {
        try {
            JsonNode json = objectMapper.readTree(rawBody);
            // MiniMax 响应为 {"task":{...}} 包裹结构，取 task 子节点作为实际状态载体
            JsonNode task = json.has("task") ? json.path("task") : json;
            String rawStatus = task.path("status").asText("");
            String status = normalizeStatus(rawStatus);
            String error = null;
            if ("failed".equals(status)) {
                error = task.path("error").path("message").asText("");
                if (error.isBlank()) error = task.path("base_resp").path("status_msg").asText("video generation failed");
            }
            String progress = task.path("progress").isMissingNode() ? null : task.path("progress").asText();
            String contentUrl = task.path("content").path("url").asText("");
            String fileId = task.path("file_id").asText("");
            if (contentUrl.isBlank() && "succeeded".equals(status) && !fileId.isBlank()) {
                contentUrl = fetchMiniMaxDownloadUrl(channel, apiKey, fileId);
            }
            return new MinimaxVideoResult("minimax", taskId, status, progress, contentUrl, error, fileId, contentUrl);
        } catch (Exception e) {
            throw new BusinessException(50201, "MiniMax 轮询响应解析失败: " + e.getMessage());
        }
    }

    /** MiniMax 状态归一：Success/success + succeeded/completed/done → succeeded；failed/fail → failed；其余 → processing */
    private String normalizeStatus(String status) {
        switch (status == null ? "" : status.toLowerCase()) {
            case "succeeded", "success", "completed", "done": return "succeeded";
            case "failed", "fail": return "failed";
            default: return "processing";
        }
    }

    /** 新版 H3 契约：任务查询只回 file_id，需再调 /v1/files/retrieve?file_id= 取限时 download_url（失败空串，下轮轮询重试） */
    private String fetchMiniMaxDownloadUrl(Channel channel, String apiKey, String fileId) {
        try {
            String url = stripTrailingSlash(channel.getBaseUrl()) + "/v1/files/retrieve?file_id="
                    + URLEncoder.encode(fileId, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(180)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return objectMapper.readTree(resp.body()).path("file").path("download_url").asText("");
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> nvl(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}


