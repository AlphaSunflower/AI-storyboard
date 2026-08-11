package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.config.GatewayConfig;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.CallLogMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import lombok.RequiredArgsConstructor;
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
import java.util.Set;
import java.util.UUID;

/**
 * 视频网关实现：统一 /v1/videos 创建/轮询/下载，按 model 路由到 Laozhang（multipart）
 * 或 MiniMax（JSON content 数组）渠道。下载由网关流式代理（业务只认 /v1/videos/{taskId}/content）。
 */
@Service
@RequiredArgsConstructor
public class VideoGatewayServiceImpl implements VideoGatewayService {

    private static final Logger log = LoggerFactory.getLogger(VideoGatewayServiceImpl.class);

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final CallLogService callLogService;
    private final CallLogMapper callLogMapper;
    private final GatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** HttpClient 懒加载（connectTimeout 依赖 config，构造期不构建） */
    private volatile HttpClient httpClient;

    private HttpClient httpClient() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                c = httpClient;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(config.getUpstream().getConnectTimeoutMs()))
                            .build();
                    httpClient = c;
                }
            }
        }
        return c;
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
    @Override
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
                // Laozhang 创建按池路由、成功率低（实测约 1/8）：按 isRetryableVideoCreate 语义
                // 换池重试最多 10 次（退避 500ms*attempt），对齐旧 Backend 逻辑（设计 §7）；
                // minimax 分支保持单发（其错误不可重试换池）
                resp = createLaozhangWithRetry(channel, apiKey, body);
            }

            int status = resp.statusCode();
            String bodyStr = resp.body();
            if (status == 200) {
                // 从上游响应提取 task_id（minimax: task_id；laozhang: id/taskId）
                JsonNode respJson = objectMapper.readTree(bodyStr);
                String taskId = respJson.path("task_id").asText(
                        respJson.path("id").asText(respJson.path("taskId").asText("")));
                callLogService.log(model, channel.getId(), "created", System.currentTimeMillis() - start, null, null, taskId);
                if (taskId.isBlank()) {
                    log.warn("视频创建响应无 task_id: {}", bodyStr.length() > 200 ? bodyStr.substring(0, 200) : bodyStr);
                }
                return new VideoResult(200, bodyStr);
            }
            String error = bodyStr.length() > 300 ? bodyStr.substring(0, 300) : bodyStr;
            callLogService.log(model, channel.getId(), "error", System.currentTimeMillis() - start, error, null, null);
            return new VideoResult(status, bodyStr);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** MiniMax ratio 可用值白名单（t2v 纯文本场景；i2v 恒 adaptive） */
    private static final Set<String> RATIOS = Set.of("16:9", "4:3", "1:1", "3:4", "9:16", "21:9");

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

        // 分辨率：调用方显式传值则透传（人工选择生效，如卡片选 2K），未传回落默认档（省钱，默认 768P）
        String resolution = body.path("resolution").asText("");
        payload.put("resolution", resolution.isBlank()
                ? (config.getVideoDefaultResolution() == null ? "768P" : config.getVideoDefaultResolution())
                : resolution);
        int duration = body.path("duration").asInt(8);
        payload.put("duration", Math.max(4, Math.min(15, duration)));   // clamp 4~15
        String ratio = body.path("aspectRatio").asText("");
        // MiniMax ratio 语义（实测 2026-08-08 联调踩坑）：
        //   图生视频（i2v）→ 必须显式 "adaptive"；
        //   纯文本（t2v）→ 必须显式指定可用值（16:9/4:3/1:1/3:4/9:16/21:9），
        //     缺省/非法值一律回落 16:9（不能不带 ratio——上游 400 invalid params 2013）
        boolean isImageToVideo = !imageUrl.isBlank();
        if (isImageToVideo) {
            payload.put("ratio", "adaptive");
        } else {
            String effRatio = RATIOS.contains(ratio) ? ratio : "16:9";
            payload.put("ratio", effRatio);
        }

        // 轻量重试（设计 §7）：429 限流/5xx 服务端错误最多重试 2 次（共 3 次请求），退避 500ms*attempt；
        // 其余状态码（400 参数错/401 密钥错等）直接返回，避免重复创建任务/白扣费
        HttpResponse<String> resp = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(channel.getBaseUrl()) + "/v2/video_generation"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            resp = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if ((code != 429 && code < 500) || attempt == 3) {
                break;
            }
            log.warn("MiniMax 创建返回 {}（限流/服务端错误），第 {}/3 次重试, body={}",
                    code, attempt, resp.body().length() > 150 ? resp.body().substring(0, 150) : resp.body());
            Thread.sleep(500L * attempt);
        }
        return resp;
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
        return httpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Laozhang 创建 + 换池重试（设计 §7）：老张网关按池路由到多个上游 Google 项目，
     *  未开通 veo 模型的池返回 404 fail_to_fetch_task（实测 2026-08-04 成功率仅约 1/8），
     *  失效服务账号返回 500（body 含 do_request_failed/invalid_grant，发生在请求发往 Google
     *  之前，任务必然未创建）——此类错误可安全重试换池，最多 10 次，退避 500ms*attempt。 */
    private HttpResponse<String> createLaozhangWithRetry(Channel channel, String apiKey, JsonNode body) throws Exception {
        int maxAttempts = 10;
        HttpResponse<String> resp = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            resp = createLaozhang(channel, apiKey, body);
            int code = resp.statusCode();
            String respBody = resp.body();
            if (!isRetryableVideoCreate(code, respBody) || attempt == maxAttempts) {
                break;
            }
            log.warn("视频任务创建返回 {}(上游项目池故障), 第 {}/{} 次重试换池, body={}",
                    code, attempt, maxAttempts,
                    respBody.length() > 150 ? respBody.substring(0, 150) : respBody);
            Thread.sleep(500L * attempt);
        }
        return resp;
    }

    /** 视频任务创建响应是否可安全重试（对齐旧 Backend isRetryableVideoCreate 语义）：
     *  404 = 上游池无模型权限（fail_to_fetch_task）；5xx 且 body 含
     *  fail_to_fetch_task / do_request_failed / invalid_grant（凭据故障，任务未创建）→ 重试换池。
     *  其余（400 参数错、401 密钥错等）不重试，避免重复创建任务/白扣费。 */
    private static boolean isRetryableVideoCreate(int code, String body) {
        if (code == 404) return true;
        if (code < 500 || body == null) return false;
        return body.contains("fail_to_fetch_task") || body.contains("do_request_failed")
                || body.contains("invalid_grant");
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 轮询视频状态。taskId 反查渠道：查 call_log 最新一条该 model 的记录不够精确，
     *  改为按 taskId 前缀存 channel 标识：简化方案——轮询时遍历该 model 的路由渠道逐个尝试，
     *  命中 200 即返回。 */
    @Override
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
                    HttpResponse<String> resp = httpClient().send(HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Bearer " + apiKey)
                            .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (!"minimax".equals(channel.getType())
                            && (resp.statusCode() == 404 || resp.body().isBlank())) {
                        // Laozhang fallback 端点（设计 §6.2）：主端点 404/空响应时再试 /video/generations/{taskId}
                        String fbUrl = stripTrailingSlash(channel.getBaseUrl()) + "/video/generations/" + taskId;
                        HttpResponse<String> fbResp = httpClient().send(HttpRequest.newBuilder()
                                .uri(URI.create(fbUrl))
                                .header("Authorization", "Bearer " + apiKey)
                                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                                .GET().build(), HttpResponse.BodyHandlers.ofString());
                        if (fbResp.statusCode() == 200) {
                            resp = fbResp;
                        }
                    }

                    if (resp.statusCode() == 200) {
                        JsonNode json = objectMapper.readTree(resp.body());
                        // MiniMax 响应为 {"task":{...}} 包裹结构（实测 2026-08-08 联调踩坑），
                        // 取 task 子节点作为实际状态载体；Laozhang 无包裹层，直接取顶层
                        JsonNode task = "minimax".equals(channel.getType()) ? json.path("task") : json;
                        // MiniMax succeeded → 暂存 video_url 供下载端点使用（从原始 JsonNode 提取）
                        if ("minimax".equals(channel.getType())) {
                            String status = task.path("status").asText("");
                            String contentUrl = task.path("content").path("url").asText("");
                            if ("succeeded".equals(status) && !contentUrl.isBlank()) {
                                callLogService.log(task.path("model").asText("video"), channel.getId(), "succeeded",
                                        System.currentTimeMillis() - start, null, contentUrl, taskId);
                            } else if ("failed".equals(status)) {
                                String err = task.path("error").path("message").asText("video generation failed");
                                callLogService.log(task.path("model").asText("video"), channel.getId(), "failed",
                                        System.currentTimeMillis() - start, err, null, taskId);
                            }
                        } else {
                            callLogService.log("video", channel.getId(), "polled",
                                    System.currentTimeMillis() - start, null, null, taskId);
                        }
                        // 归一化为统一响应 {taskId, status, progress?, error?}（对齐设计 §5.1）
                        return new VideoResult(200, normalizePoll(task));
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
    @Override
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
                        // 从 call_log 取该渠道该 taskId 最近一条限时直链
                        // （taskId+channelId 双条件过滤，同渠道并发任务不串号）
                        com.llmgateway.entity.CallLog latest = callLogMapper.selectOne(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.llmgateway.entity.CallLog>()
                                        .eq(com.llmgateway.entity.CallLog::getChannelId, channel.getId())
                                        .eq(com.llmgateway.entity.CallLog::getTaskId, taskId)
                                        .isNotNull(com.llmgateway.entity.CallLog::getVideoUrl)
                                        .orderByDesc(com.llmgateway.entity.CallLog::getCreatedAt)
                                        .last("LIMIT 1"));
                        if (latest == null || latest.getVideoUrl().isBlank()) continue;
                        java.net.URI uri = java.net.URI.create(latest.getVideoUrl());
                        HttpRequest req = HttpRequest.newBuilder().uri(uri)
                                .timeout(Duration.ofSeconds(180)).GET().build();
                        HttpResponse<java.io.InputStream> resp = httpClient().send(req,
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() == 200) {
                            return streamResponse(resp.body());
                        }
                    } else {
                        String url = stripTrailingSlash(channel.getBaseUrl()) + "/videos/" + taskId + "/content";
                        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                                .header("Authorization", "Bearer " + apiKey)
                                .timeout(Duration.ofSeconds(180)).GET().build();
                        HttpResponse<java.io.InputStream> resp = httpClient().send(req,
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
