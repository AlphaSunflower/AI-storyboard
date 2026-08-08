package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.CallLogMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 视频网关：统一 /v1/videos 创建/轮询/下载，按 model 路由到 Laozhang（multipart）
 * 或 MiniMax（JSON content 数组）渠道。下载由网关流式代理（业务只认 /v1/videos/{taskId}/content）。
 */
@Service
public class VideoGatewayService {

    private static final Logger log = LoggerFactory.getLogger(VideoGatewayService.class);

    /** 视频路由结果：上游 HTTP 状态码 + 响应体 */
    public record VideoResult(int status, String body) {}

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final CallLogService callLogService;
    private final CallLogMapper callLogMapper;
    private final GatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public VideoGatewayService(ModelRouteMapper routeMapper, ChannelMapper channelMapper,
                               KeyService keyService, CallLogService callLogService,
                               CallLogMapper callLogMapper, GatewayConfig config) {
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.callLogService = callLogService;
        this.callLogMapper = callLogMapper;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getUpstream().getConnectTimeoutMs()))
                .build();
    }

    /** 按 model 取第一个 enabled 渠道（视频模型：MiniMax-H3→minimax 渠道；veo-*→laozhang 渠道） */
    private Channel resolveChannel(String model) {
        List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .eq(ModelRoute::getModelName, model));
        if (routes == null || routes.isEmpty()) throw new BusinessException(40401, "no route for model: " + model);
        return routes.stream()
                .map(r -> channelMapper.selectById(r.getChannelId()))
                .filter(c -> c != null && Boolean.TRUE.equals(c.getEnabled()))
                .sorted(Comparator.comparingInt(c -> c.getPriority() == null ? 0 : c.getPriority()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(50301, "no available channel for model: " + model));
    }

    /**
     * 创建视频任务。请求体（OpenAI 风格统一格式）：
     * {model, prompt, size?, resolution?, aspectRatio?, duration?, negativePrompt?, seed?, imageUrl?}
     * imageUrl：图生视频首帧（data URI 或 http URL，业务侧已把本地图转 data URI）
     */
    public VideoResult create(String requestBody) {
        long start = System.currentTimeMillis();
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            String model = body.path("model").asText("");
            if (model.isBlank()) throw new BusinessException(40001, "model 不能为空");
            Channel channel = resolveChannel(model);
            String apiKey = keyService.decrypt(channel.getApiKey());

            HttpResponse<String> resp;
            if ("minimax".equals(channel.getType())) {
                resp = createMinimax(channel, apiKey, body);
            } else {
                resp = createLaozhang(channel, apiKey, body);
            }

            int status = resp.statusCode();
            String bodyStr = resp.body();
            if (status == 200) {
                // 从上游响应提取 task_id（minimax: task_id；laozhang: id/taskId）
                JsonNode respJson = objectMapper.readTree(bodyStr);
                String taskId = respJson.path("task_id").asText(
                        respJson.path("id").asText(respJson.path("taskId").asText("")));
                callLogService.log(model, channel.getId(), "created", System.currentTimeMillis() - start, null, null);
                if (taskId.isBlank()) {
                    log.warn("视频创建响应无 task_id: {}", bodyStr.length() > 200 ? bodyStr.substring(0, 200) : bodyStr);
                }
                return new VideoResult(200, bodyStr);
            }
            String error = bodyStr.length() > 300 ? bodyStr.substring(0, 300) : bodyStr;
            callLogService.log(model, channel.getId(), "error", System.currentTimeMillis() - start, error, null);
            return new VideoResult(status, bodyStr);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** MiniMax 创建：POST {base}/v2/video_generation，content 数组 JSON */
    private HttpResponse<String> createMinimax(Channel channel, String apiKey, JsonNode body) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", body.path("model").asText());

        ArrayNode content = payload.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", body.path("prompt").asText(""));

        String imageUrl = body.path("imageUrl").asText("");
        if (!imageUrl.isBlank()) {
            ObjectNode imgPart = content.addObject();
            imgPart.put("type", "image_url");
            imgPart.put("image_url", objectMapper.createObjectNode().put("url", imageUrl));
            imgPart.put("role", "first_frame");
        }

        // 分辨率恒用默认档（省钱；调用方传 720p/1080p/4K/2K 一律忽略）
        payload.put("resolution", config.getVideoDefaultResolution() == null ? "768P" : config.getVideoDefaultResolution());
        int duration = body.path("duration").asInt(8);
        payload.put("duration", Math.max(4, Math.min(15, duration)));   // clamp 4~15
        String ratio = body.path("aspectRatio").asText("");
        if (!ratio.isBlank()) payload.put("ratio", ratio);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(channel.getBaseUrl()) + "/v2/video_generation"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Laozhang 创建：POST {base}/videos，multipart 表单（与业务现状一致） */
    private HttpResponse<String> createLaozhang(Channel channel, String apiKey, JsonNode body) throws Exception {
        String boundary = "----llmgw" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        // multipart 字段
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n").append(body.path("model").asText()).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n").append(body.path("prompt").asText("")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"duration\"\r\n\r\n").append(body.path("duration").asInt(8)).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"size\"\r\n\r\n").append(body.path("size").asText("1280x720")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"resolution\"\r\n\r\n").append(body.path("resolution").asText("720p")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"aspectRatio\"\r\n\r\n").append(body.path("aspectRatio").asText("16:9")).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n")
          .append("{\"durationSeconds\":").append(body.path("duration").asInt(8))
          .append(",\"resolution\":\"").append(body.path("resolution").asText("720p"))
          .append("\",\"aspectRatio\":\"").append(body.path("aspectRatio").asText("16:9")).append("\"}\r\n");
        if (body.hasNonNull("negativePrompt")) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"negativePrompt\"\r\n\r\n").append(body.path("negativePrompt").asText()).append("\r\n");
        }
        if (body.hasNonNull("seed")) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"seed\"\r\n\r\n").append(body.path("seed").asLong()).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(channel.getBaseUrl()) + "/videos"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 轮询视频状态。taskId 反查渠道：查 call_log 最新一条该 model 的记录不够精确，
     *  改为按 taskId 前缀存 channel 标识：简化方案——轮询时遍历该 model 的路由渠道逐个尝试，
     *  命中 200 即返回。 */
    public VideoResult poll(String taskId) {
        long start = System.currentTimeMillis();
        try {
            // 反查渠道：遍历所有 enabled 渠道，尝试查询（Laozhang GET /videos/{id}，MiniMax GET /v2/query/video_generation/{id}）
            // 精确反查需要路由表记录 taskId→channel，第一版用"遍历渠道尝试"简化
            List<Channel> allChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                    .eq(Channel::getEnabled, true)
                    .orderByAsc(Channel::getPriority));
            for (Channel channel : allChannels) {
                try {
                    String apiKey = keyService.decrypt(channel.getApiKey());
                    String url;
                    if ("minimax".equals(channel.getType())) {
                        url = stripTrailingSlash(channel.getBaseUrl()) + "/v2/query/video_generation/" + taskId;
                    } else {
                        url = stripTrailingSlash(channel.getBaseUrl()) + "/videos/" + taskId;
                    }
                    HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Bearer " + apiKey)
                            .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());

                    if (resp.statusCode() == 200) {
                        JsonNode json = objectMapper.readTree(resp.body());
                        // MiniMax succeeded → 暂存 video_url 供下载端点使用（从原始 JsonNode 提取）
                        if ("minimax".equals(channel.getType())) {
                            String status = json.path("status").asText("");
                            String contentUrl = json.path("content").path("url").asText("");
                            if ("succeeded".equals(status) && !contentUrl.isBlank()) {
                                callLogService.log(json.path("model").asText("video"), channel.getId(), "succeeded",
                                        System.currentTimeMillis() - start, null, contentUrl);
                            } else if ("failed".equals(status)) {
                                String err = json.path("error").path("message").asText("video generation failed");
                                callLogService.log(json.path("model").asText("video"), channel.getId(), "failed",
                                        System.currentTimeMillis() - start, err, null);
                            }
                        } else {
                            callLogService.log("video", channel.getId(), "polled",
                                    System.currentTimeMillis() - start, null, null);
                        }
                        // 归一化为统一响应 {taskId, status, progress?, error?}（对齐设计 §5.1）
                        return new VideoResult(200, normalizePoll(json));
                    }
                    // 404 = 该渠道无此任务，尝试下一个
                } catch (Exception e) {
                    log.warn("轮询渠道 {} 异常: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(40401, "video task not found: " + taskId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** 视频下载：流式代理（业务只认本端点，永不接触上游 URL/Key） */
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            download(String taskId) {
        try {
            // 按 priority 升序遍历渠道，优先命中高优先级渠道（与 poll 一致）
            List<Channel> allChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                    .eq(Channel::getEnabled, true)
                    .orderByAsc(Channel::getPriority));
            for (Channel channel : allChannels) {
                try {
                    String apiKey = keyService.decrypt(channel.getApiKey());
                    if ("minimax".equals(channel.getType())) {
                        // 从 call_log 取该渠道最近一条限时直链（按 channelId 过滤，防止跨协议错配命中他渠道记录）
                        com.llmgateway.entity.CallLog latest = callLogMapper.selectOne(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.llmgateway.entity.CallLog>()
                                        .eq(com.llmgateway.entity.CallLog::getChannelId, channel.getId())
                                        .isNotNull(com.llmgateway.entity.CallLog::getVideoUrl)
                                        .orderByDesc(com.llmgateway.entity.CallLog::getCreatedAt)
                                        .last("LIMIT 1"));
                        if (latest == null || latest.getVideoUrl().isBlank()) continue;
                        java.net.URI uri = java.net.URI.create(latest.getVideoUrl());
                        HttpRequest req = HttpRequest.newBuilder().uri(uri)
                                .timeout(Duration.ofSeconds(180)).GET().build();
                        HttpResponse<java.io.InputStream> resp = httpClient.send(req,
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() == 200) {
                            return streamResponse(resp.body());
                        }
                    } else {
                        String url = stripTrailingSlash(channel.getBaseUrl()) + "/videos/" + taskId + "/content";
                        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                                .header("Authorization", "Bearer " + apiKey)
                                .timeout(Duration.ofSeconds(180)).GET().build();
                        HttpResponse<java.io.InputStream> resp = httpClient.send(req,
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() == 200) {
                            return streamResponse(resp.body());
                        }
                    }
                } catch (Exception e) {
                    log.warn("下载渠道 {} 异常: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(40401, "video content not available: " + taskId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** 归一化轮询响应：统一输出 {taskId, status, progress?, error?}（设计 §5.1，兼容 minimax/laozhang 双协议） */
    private String normalizePoll(JsonNode json) {
        ObjectNode out = objectMapper.createObjectNode();
        // taskId 三选一：task_id / id / taskId
        String taskId = json.path("task_id").asText(json.path("id").asText(json.path("taskId").asText("")));
        if (!taskId.isBlank()) out.put("taskId", taskId);
        // status 归一化映射：succeeded/completed→succeeded，failed→failed，queued/running/未知→processing
        String raw = json.path("status").asText("");
        String status;
        switch (raw) {
            case "succeeded":
            case "completed": status = "succeeded"; break;
            case "failed": status = "failed"; break;
            default: status = "processing"; break;
        }
        out.put("status", status);
        // progress 透传上游（无则省略）
        JsonNode progress = json.path("progress");
        if (progress.isNumber() || progress.isTextual()) out.put("progress", progress.asInt());
        // failed 时带 error：minimax 取 error.message；laozhang 取 error/message
        if ("failed".equals(status)) {
            String err = json.path("error").path("message").asText("");
            if (err.isBlank()) err = json.path("error").asText("");
            if (err.isBlank()) err = json.path("message").asText("");
            if (err.isBlank()) err = "video generation failed";
            out.put("error", err);
        }
        return out.toString();
    }

    private org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            streamResponse(java.io.InputStream in) {
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body =
                out -> { try (in) { in.transferTo(out); } };
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment")
                .body(body);
    }
}
