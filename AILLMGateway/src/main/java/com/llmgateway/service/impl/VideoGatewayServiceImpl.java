package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.config.GatewayConfig;
import com.llmgateway.entity.CallLog;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.CallLogMapper;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelParamsService;
import com.llmgateway.service.ModelParamsSpecNormalizer;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import com.llmgateway.vendor.video.dto.VendorCreateInput;
import com.llmgateway.vendor.video.model.ReferenceImage;
import com.llmgateway.vendor.video.model.ReferenceImageRole;
import com.llmgateway.vendor.video.dto.VideoVendorResult;
import com.llmgateway.vendor.video.spi.VideoVendorAdapter;
import com.llmgateway.vendor.video.spi.VideoVendorRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 视频网关编排器 —— 统一 /v1/videos 创建/轮询/下载的「编排」，不含任何厂商协议细节。
 * <p>
 * 协议（载荷构建 / 创建+轮询响应解析 / 重试策略）全部下沉到 {@link VideoVendorAdapter}，
 * 按 channel.type 经 {@link VideoVendorRegistry} 分发；新增协议 = 新增适配器 + 注册渠道类型，此处零改动。
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
    private final ModelParamsService modelParamsService;
    private final GatewayConfig config;
    private final VideoVendorRegistry registry;
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
     * 创建视频任务。OpenAI 风格统一请求体 → 按 model 反查渠道 → 经适配器构建/发送/解析。
     * 响应为带 vendor 的归一契约 {vendor, taskId, status, ...}（数据不挤成一坨，厂商可识别）。
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
            VideoVendorAdapter adapter = registry.resolve(channel.getType());
            VendorCreateInput in = toCreateInput(body);

            // 泛型重试循环：重试次数 / 可重试判定 / 退避均来自适配器（该协议自身策略）
            HttpResponse<String> resp = null;
            for (int attempt = 1; attempt <= adapter.maxCreateAttempts(); attempt++) {
                HttpRequest req = adapter.buildCreateRequest(in, channel, apiKey, in.referenceImages());
                resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (!adapter.isRetryableCreate(resp.statusCode(), resp.body())
                        || attempt == adapter.maxCreateAttempts()) {
                    break;
                }
                log.warn("视频创建返回 {}（可重试），第 {}/{} 次重试, body={}",
                        resp.statusCode(), attempt, adapter.maxCreateAttempts(),
                        resp.body().length() > 150 ? resp.body().substring(0, 150) : resp.body());
                Thread.sleep(adapter.createBackoffMs(attempt));
            }

            int status = resp.statusCode();
            String bodyStr = resp.body();
            if (status == 200) {
                VideoVendorResult result = adapter.parseCreate(bodyStr);
                callLogService.log(model, channel.getId(), "created",
                        System.currentTimeMillis() - start, null, null, result.taskId());
                return new VideoResult(200, toJson(result));
            }
            String error = bodyStr.length() > 300 ? bodyStr.substring(0, 300) : bodyStr;
            callLogService.log(model, channel.getId(), "error",
                    System.currentTimeMillis() - start, error, null, null);
            return new VideoResult(status, bodyStr);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /**
     * 轮询视频状态。taskId 反查渠道：优先用 call_log 的「created」记录定位实际创建渠道，只查该渠道，
     * 避免被其他渠道类型误中（Priority 更高的 laozhang 渠道抢先返回 200 抢占信号）；无 created 记录才回退遍历。
     */
    @Override
    public VideoResult poll(String taskId) {
        long start = System.currentTimeMillis();
        try {
            Channel taskChannel = resolveChannelByTaskId(taskId);
            if (taskChannel != null) {
                VideoResult r = pollChannel(taskChannel, taskId, start);
                if (r != null) return r;
            }
            List<Channel> allChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                    .eq(Channel::getEnabled, true)
                    .orderByAsc(Channel::getPriority));
            for (Channel channel : allChannels) {
                try {
                    VideoResult r = pollChannel(channel, taskId, start);
                    if (r != null) return r;
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

    /** 单渠道轮询：命中 200 返回归一结果，否则返回 null（该渠道无此任务） */
    private VideoResult pollChannel(Channel channel, String taskId, long start) throws Exception {
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

        // ponytail: laozhang 历史端点兜底（其他 openai_compatible 渠道沿用；新协议若无需此兜底可后续下沉适配器）
        if (!"minimax".equals(channel.getType())
                && (resp.statusCode() == 404 || resp.body().isBlank())) {
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

        if (resp.statusCode() != 200) return null;

        VideoVendorAdapter adapter = registry.resolve(channel.getType());
        VideoVendorResult result = adapter.parsePoll(resp.body(), taskId, channel, apiKey);
        JsonNode spec = resolveSpecByTaskId(taskId);
        if (spec != null && spec.path("response").path("async").isObject()) {
            ObjectNode normalized = (ObjectNode) objectMapper.readTree(
                    ModelParamsSpecNormalizer.normalizeVideoPoll(resp.body(), spec, taskId));
            String mediaUrl = normalized.path("_mediaUrl").asText("");
            normalized.remove("_mediaUrl");
            normalized.put("vendor", result.vendor());
            String status = normalized.path("status").asText(result.status());
            if ("succeeded".equals(status)) {
                callLogService.log(result.vendor(), channel.getId(), "succeeded",
                        System.currentTimeMillis() - start, null,
                        mediaUrl.isBlank() ? result.url() : mediaUrl, taskId);
            } else if ("failed".equals(status)) {
                callLogService.log(result.vendor(), channel.getId(), "failed",
                        System.currentTimeMillis() - start,
                        normalized.path("error").asText(result.error()), null, taskId);
            } else {
                callLogService.log(result.vendor(), channel.getId(), "polled",
                        System.currentTimeMillis() - start, null, null, taskId);
            }
            return new VideoResult(200, normalized.toString());
        }
        // 统一按归一状态记 call_log；succeeded 时带媒体直链（minimax 供下载端点复用）
        if ("succeeded".equals(result.status())) {
            callLogService.log(result.vendor(), channel.getId(), "succeeded",
                    System.currentTimeMillis() - start, null, result.url(), taskId);
        } else if ("failed".equals(result.status())) {
            callLogService.log(result.vendor(), channel.getId(), "failed",
                    System.currentTimeMillis() - start, result.error(), null, taskId);
        } else {
            callLogService.log(result.vendor(), channel.getId(), "polled",
                    System.currentTimeMillis() - start, null, null, taskId);
        }
        return new VideoResult(200, toJson(result));
    }

    /** 按 taskId 找到模型配置，并解析可选的 response.async 归一化 Schema。 */
    private JsonNode resolveSpecByTaskId(String taskId) {
        try {
            CallLog latest = callLogMapper.selectOne(new LambdaQueryWrapper<CallLog>()
                    .eq(CallLog::getTaskId, taskId)
                    .isNotNull(CallLog::getModel)
                    .orderByDesc(CallLog::getCreatedAt)
                    .last("LIMIT 1"));
            if (latest == null || latest.getModel() == null || latest.getModel().isBlank()) return null;
            ModelParams params = modelParamsService.getByModelName(latest.getModel());
            return params == null ? null : ModelParamsSpecNormalizer.spec(params.getParamsSchema());
        } catch (Exception e) {
            log.warn("解析模型 params_schema 失败，回退适配器结果: taskId={}, error={}", taskId, e.getMessage());
            return null;
        }
    }

    /** 按 taskId 反查创建渠道：call_log「created」记录优先，无则取最近一条；返回 null 表示无法定位 */
    @Override
    public VideoResult cancel(String taskId) {
        long start = System.currentTimeMillis();
        try {
            Channel taskChannel = resolveChannelByTaskId(taskId);
            if (taskChannel != null) {
                VideoResult r = cancelChannel(taskChannel, taskId, start);
                if (r != null) return r;
            }
            // 经 call_log 反查不到渠道：遍历全部 enabled 候选渠道（优先级），任一支持取消即可
            List<Channel> allChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                    .eq(Channel::getEnabled, true)
                    .orderByAsc(Channel::getPriority));
            for (Channel channel : allChannels) {
                try {
                    VideoResult r = cancelChannel(channel, taskId, start);
                    if (r != null) return r;
                } catch (Exception e) {
                    log.warn("cancel via channel {} failed: {}", channel.getName(), e.getMessage());
                }
            }
            throw new BusinessException(40401, "video task not found: " + taskId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** 尝试在指定渠道上取消任务；上游不支持（buildCancelRequest 返回 null）时返回 null 交给调用方继续。 */
    private VideoResult cancelChannel(Channel channel, String taskId, long start) throws Exception {
        String apiKey = keyService.decrypt(channel.getApiKey());
        VideoVendorAdapter adapter = registry.resolve(channel.getType());
        HttpRequest req = adapter.buildCancelRequest(taskId, channel, apiKey);
        if (req == null) {
            log.info("vendor {} has no cancel capability, skip channel {}", channel.getType(), channel.getName());
            return null;
        }
        HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        String body = resp.body();
        if (status != 200) {
            String err = body.length() > 300 ? body.substring(0, 300) : body;
            callLogService.log(channel.getType(), channel.getId(), "cancel-error",
                    System.currentTimeMillis() - start, err, null, taskId);
            throw new BusinessException(status >= 500 ? 50201 : 40001, "cancel failed: " + err);
        }
        VideoVendorResult result = adapter.parseCancel(body, taskId, channel, apiKey);
        callLogService.log(result.vendor(), channel.getId(), "cancelled",
                System.currentTimeMillis() - start, null, null, taskId);
        return new VideoResult(200, toJson(result));
    }

    private Channel resolveChannelByTaskId(String taskId) {
        try {
            CallLog created = callLogMapper.selectOne(new LambdaQueryWrapper<CallLog>()
                    .eq(CallLog::getTaskId, taskId)
                    .eq(CallLog::getStatus, "created")
                    .orderByAsc(CallLog::getCreatedAt)
                    .last("LIMIT 1"));
            if (created != null && created.getChannelId() != null && !created.getChannelId().isBlank()) {
                return channelMapper.selectById(created.getChannelId());
            }
            CallLog latest = callLogMapper.selectOne(new LambdaQueryWrapper<CallLog>()
                    .eq(CallLog::getTaskId, taskId)
                    .orderByDesc(CallLog::getCreatedAt)
                    .last("LIMIT 1"));
            if (latest != null && latest.getChannelId() != null && !latest.getChannelId().isBlank()) {
                return channelMapper.selectById(latest.getChannelId());
            }
            return null;
        } catch (Exception e) {
            log.warn("反查任务渠道失败: taskId={}, error={}", taskId, e.getMessage());
            return null;
        }
    }

    /** 视频下载：流式代理（业务只认本端点，永不接触上游 URL/Key） */
    @Override
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            download(String taskId) {
        try {
            Channel taskChannel = resolveChannelByTaskId(taskId);
            if (taskChannel != null) {
                org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> r =
                        downloadChannel(taskChannel, taskId);
                if (r != null) return r;
            }
            List<Channel> allChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                    .eq(Channel::getEnabled, true)
                    .orderByAsc(Channel::getPriority));
            for (Channel channel : allChannels) {
                try {
                    org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> r =
                            downloadChannel(channel, taskId);
                    if (r != null) return r;
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

    /** 单渠道下载：命中 200 返回流式响应，否则返回 null（该渠道无此内容） */
    private org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            downloadChannel(Channel channel, String taskId) throws Exception {
        String apiKey = keyService.decrypt(channel.getApiKey());
        if ("minimax".equals(channel.getType())) {
            CallLog latest = callLogMapper.selectOne(new LambdaQueryWrapper<CallLog>()
                    .eq(CallLog::getChannelId, channel.getId())
                    .eq(CallLog::getTaskId, taskId)
                    .isNotNull(CallLog::getVideoUrl)
                    .orderByDesc(CallLog::getCreatedAt)
                    .last("LIMIT 1"));
            if (latest == null || latest.getVideoUrl().isBlank()) return null;
            URI uri = URI.create(latest.getVideoUrl());
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
        return null;
    }

    /**
     * 把 OpenAI 风格请求体转成统一入参。参考素材/首帧互斥与参数默认全部下沉到适配器，
     * 这里只做字段搬运，不做任何协议判断。
     */
    static VendorCreateInput toCreateInput(JsonNode body) {
        List<ReferenceImage> refImages = new ArrayList<>();
        for (JsonNode n : body.path("referenceImages")) {
            ReferenceImage ref = parseReferenceImage(n);
            if (ref != null) refImages.add(ref);
        }
        List<String> refVideos = new ArrayList<>();
        for (JsonNode n : body.path("referenceVideos")) refVideos.add(n.asText(""));
        List<String> refAudios = new ArrayList<>();
        for (JsonNode n : body.path("referenceAudios")) refAudios.add(n.asText(""));
        return new VendorCreateInput(
                body.path("model").asText(""),
                body.path("prompt").asText(""),
                textOrNull(body, "size"),
                textOrNull(body, "resolution"),
                textOrNull(body, "aspectRatio"),
                body.has("duration") ? body.path("duration").asInt() : null,
                textOrNull(body, "negativePrompt"),
                body.has("seed") ? body.path("seed").asLong() : null,
                textOrNull(body, "imageUrl"),
                refImages, refVideos, refAudios);
    }

    /** 兼容两种入参形状：字符串数组 → 默认 REFERENCE；{url, role} 对象数组 → 逐项解析 role（缺失/非法默认 REFERENCE） */
    static ReferenceImage parseReferenceImage(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isTextual()) {
            String url = n.asText("");
            return url.isBlank() ? null : ReferenceImage.ofReference(url);
        }
        if (n.isObject()) {
            String url = n.path("url").asText("");
            if (url.isBlank()) return null;
            ReferenceImageRole r = parseReferenceImageRole(n.path("role").asText(""));
            return r == null ? ReferenceImage.ofReference(url) : new ReferenceImage(url, r);
        }
        return null;
    }

    /** 解析参考图 role；未知/缺失返回 null（调用方回退 REFERENCE） */
    static ReferenceImageRole parseReferenceImageRole(String role) {
        if (role == null || role.isBlank()) return null;
        try {
            return ReferenceImageRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 归一结果 → 带 vendor 标识的 JSON（数据不挤成一坨：各厂商专用字段由适配器决定是否透出） */
    private String toJson(VideoVendorResult r) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("vendor", r.vendor());
        out.put("taskId", r.taskId() == null ? "" : r.taskId());
        if (r.status() != null && !r.status().isBlank()) out.put("status", r.status());
        if (r.progress() != null && !r.progress().isBlank()) out.put("progress", r.progress());
        if (r.url() != null && !r.url().isBlank()) out.put("url", r.url());
        if (r.error() != null && !r.error().isBlank()) out.put("error", r.error());
        return out.toString();
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode n = body.path(field);
        return (n.isMissingNode() || n.isNull()) ? null
                : (n.isTextual() && n.asText().isBlank() ? null : n.asText());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
