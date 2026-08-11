package com.llmgateway.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.service.ChannelService;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelRouteService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连通性测试端点：通道测试直连上游（不落 CallLog）；模型路由测试走完整真实链路（会真实落一条 CallLog，设计接受）。
 * 通道测试使用独立 HttpClient（connectTimeout 5s + 单请求 20s、无重试），
 * 不复用 UpstreamClient.postJson/postGemini（其内部 120s 超时 + 自动重试，不适合探活）。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminTestController {

    private static final Logger log = LoggerFactory.getLogger(AdminTestController.class);

    /** 测试专用 HttpClient：连接超时 5s，单请求超时在 sendTestRequest 里设为 20s，无重试 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ChannelService channelService;
    private final ModelRouteService routeService;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final GatewayRoutingService gatewayRoutingService;
    private final VideoGatewayService videoGatewayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** GET /admin/channels/{id}/models：获取该渠道可测试的模型列表（测试弹窗候选）。
     *  优先从上游拉取（openai_compatible：GET {baseUrl}/models 解析 data[].id），失败/非 openai_compatible
     *  回退本地候选（渠道 models 字段 + 该渠道已配路由模型，去重）；两者合并去重返回。 */
    @GetMapping("/channels/{id}/models")
    public ApiResponse<Map<String, Object>> channelModels(@PathVariable String id) {
        Channel channel = channelService.getById(id);
        Set<String> merged = new LinkedHashSet<>();
        // 本地候选：渠道 models 字段（中英文逗号分隔）+ 该渠道已配路由模型
        if (channel.getModels() != null) {
            for (String m : channel.getModels().split("[,，]")) {
                String t = m.trim();
                if (!t.isEmpty()) merged.add(t);
            }
        }
        routeService.listByChannelId(id)
                .forEach(r -> { if (r.getModelName() != null && !r.getModelName().isBlank()) merged.add(r.getModelName()); });
        // 上游拉取（仅 openai_compatible 有标准 GET /models；gemini/minimax 接口格式不同，不尝试）
        String source = "local";
        if (!"gemini".equals(channel.getType()) && !"minimax".equals(channel.getType())) {
            try {
                String apiKey = keyService.decrypt(channel.getApiKey());
                HttpResponse<String> resp = upstreamClient.get(
                        stripTrailingSlash(channel.getBaseUrl()) + "/models", apiKey);
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    JsonNode data = objectMapper.readTree(resp.body()).path("data");
                    if (data.isArray()) {
                        List<String> upstream = new ArrayList<>();
                        data.forEach(n -> { String mid = n.path("id").asText(""); if (!mid.isEmpty()) upstream.add(mid); });
                        if (!upstream.isEmpty()) {
                            source = "upstream";
                            merged.addAll(upstream);   // 上游优先展示（插入序在前）
                        }
                    }
                } else {
                    log.warn("【模型列表】上游 GET /models 非 2xx: channel={} http={}", channel.getName(), resp.statusCode());
                }
            } catch (Exception e) {
                log.warn("【模型列表】上游拉取失败: channel={} error={}", channel.getName(), e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", new ArrayList<>(merged));
        result.put("source", source);
        return ApiResponse.ok(result);
    }

    /** POST /admin/channels/{id}/test：直连上游最小请求探活，HTTP 2xx 即成功，不落 CallLog。
     *  测试模型优先级：请求体 modelName（前端测试弹窗可填）→ 该渠道已配路由的模型 → 渠道类型默认。
     *  详细日志：发起与完成各一条（含渠道/模型/HTTP 状态/耗时/错误），供服务端排查。 */
    @PostMapping("/channels/{id}/test")
    public ApiResponse<Map<String, Object>> testChannel(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, String> body) {
        Channel channel = channelService.getById(id);
        // 测试模型选择：请求体 modelName 优先，其次该渠道已配路由中的 chat 类模型（跳过 image/video 等生成类，它们不支持 chat 探活），最后渠道类型默认
        String modelName = body == null ? null : body.get("modelName");
        if (modelName == null || modelName.isBlank()) {
            for (ModelRoute r : routeService.listByChannelId(id)) {
                if (isChatModel(r.getModelName())) { modelName = r.getModelName(); break; }
            }
        }
        // 渠道类型默认模型：minimax 用其官方模型名，其余 openai_compatible 用通用小模型
        String typeDefault = "minimax".equals(channel.getType()) ? "MiniMax-Text-01" : "gpt-4o-mini";
        String effectiveModel = (modelName == null || modelName.isBlank()) ? typeDefault : modelName;
        log.info("【通道测试】发起: channel={} type={} model={} baseUrl={}", channel.getName(),
                channel.getType(), effectiveModel, channel.getBaseUrl());
        long start = System.currentTimeMillis();
        try {
            String apiKey = keyService.decrypt(channel.getApiKey());
            // 按渠道类型构造最小请求体（max_tokens=1，仅验证连通不追求正确回复）；gemini 无模型字段
            String bodyJson = switch (channel.getType() == null ? "" : channel.getType()) {
                case "gemini" -> "{\"contents\":[{\"parts\":[{\"text\":\"ping\"}]}]}";
                default -> String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}",
                        effectiveModel);
            };
            HttpResponse<String> resp = sendTestRequest(channel, apiKey, bodyJson);
            long durationMs = System.currentTimeMillis() - start;
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            String error = ok ? null : upstreamClient.extractError(resp.body());
            log.info("【通道测试】完成: channel={} ok={} http={} cost={}ms{}", channel.getName(), ok,
                    resp.statusCode(), durationMs, error == null ? "" : " error=" + error);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", ok);
            result.put("durationMs", durationMs);
            if (!ok) {
                result.put("error", error);
            }
            return ApiResponse.ok(result);
        } catch (Exception e) {
            // 连接异常 / 超时 / 解密失败等：返回失败结果而不是 500
            long durationMs = System.currentTimeMillis() - start;
            log.warn("【通道测试】异常: channel={} model={} cost={}ms error={}", channel.getName(),
                    effectiveModel, durationMs, e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("durationMs", durationMs);
            result.put("error", e.getMessage());
            return ApiResponse.ok(result);
        }
    }

    /** POST /admin/routes/{id}/test：按模型类型走真实网关链路。
     *  text/vision → /chat/completions；image → /images/generations；video → VideoGatewayService.create（创建任务 2xx 即成功，不轮询）。
     *  均与线上调用同路径（视频会落 created 日志，其余落 success/error 日志，可复核）。 */
    @PostMapping("/routes/{id}/test")
    public ApiResponse<Map<String, Object>> testRoute(@PathVariable String id) {
        ModelRoute route = routeService.getById(id);
        String type = route.getType() == null ? "text" : route.getType();
        log.info("【路由测试】发起: model={} type={} channelId={}", route.getModelName(), type, route.getChannelId());
        long start = System.currentTimeMillis();
        try {
            int status;
            String respBody;
            if ("video".equals(type)) {
                // 视频：走 VideoGatewayService 创建任务（与 /v1/videos 同链路，按 model 分发 MiniMax/Laozhang）
                VideoResult vr = videoGatewayService.create(String.format(
                        "{\"model\":\"%s\",\"prompt\":\"a cat walking\"}", route.getModelName()));
                status = vr.status();
                respBody = vr.body();
            } else if ("image".equals(type)) {
                // 生图：走通用路由转发 /images/generations（OpenAI 图片格式）
                RouteResult rr = gatewayRoutingService.route("/images/generations", String.format(
                        "{\"model\":\"%s\",\"prompt\":\"a red apple\",\"size\":\"1024x1024\",\"n\":1}", route.getModelName()));
                status = rr.status();
                respBody = rr.body();
            } else {
                // 文本/理解：走通用路由转发 /chat/completions
                RouteResult rr = gatewayRoutingService.route("/chat/completions", String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}", route.getModelName()));
                status = rr.status();
                respBody = rr.body();
            }
            long durationMs = System.currentTimeMillis() - start;
            // 2xx 判成功（gemini 转换后无 choices 键，不能用 choices 判定；视频创建 2xx 即任务创建成功）
            boolean ok = status >= 200 && status < 300;
            String error = ok ? null : upstreamClient.extractError(respBody);
            log.info("【路由测试】完成: model={} type={} ok={} http={} cost={}ms{}", route.getModelName(), type, ok,
                    status, durationMs, error == null ? "" : " error=" + error);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", ok);
            map.put("status", status);
            map.put("durationMs", durationMs);
            if (!ok) {
                map.put("error", error);
            }
            return ApiResponse.ok(map);
        } catch (Exception e) {
            // route()/videoGatewayService 抛业务异常（无路由/渠道全挂等）也按失败结果返回
            long durationMs = System.currentTimeMillis() - start;
            log.warn("【路由测试】异常: model={} type={} cost={}ms error={}", route.getModelName(), type, durationMs, e.getMessage());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", false);
            map.put("status", 500);
            map.put("durationMs", durationMs);
            map.put("error", e.getMessage());
            return ApiResponse.ok(map);
        }
    }

    /** 直发最小请求：gemini Key 走 query 参数（与 UpstreamClient.postGemini 一致），其余 Bearer */
    private HttpResponse<String> sendTestRequest(Channel channel, String apiKey, String bodyJson) throws Exception {
        String base = stripTrailingSlash(channel.getBaseUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))   // 单请求超时 20s：思考类模型响应可达 10s+，10s 会误报超时
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
        String url;
        if ("gemini".equals(channel.getType())) {
            url = base + "?key=" + apiKey;
        } else {
            String path = "minimax".equals(channel.getType()) ? "/v1/text/chatcompletion_v2" : "/chat/completions";
            url = base + path;
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return httpClient.send(builder.uri(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 判断模型名是否适合 chat 连通性探活：跳过 image/video/veo/edit/audio/embedding 等生成类模型（调 chat 端点会挂起或 400） */
    private boolean isChatModel(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        String lower = modelName.toLowerCase();
        return !(lower.contains("image") || lower.contains("img")
                || lower.contains("veo") || lower.contains("video")
                || lower.contains("edit") || lower.contains("audio")
                || lower.contains("tts") || lower.contains("embedding")
                || lower.contains("dall"));
    }
}
