package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.UpstreamClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连通性测试端点：通道测试直连上游（不落 CallLog）；模型路由测试走完整真实链路（会真实落一条 CallLog，设计接受）。
 * 通道测试使用独立 HttpClient（connectTimeout 5s + 单请求 20s、无重试），
 * 不复用 UpstreamClient.postJson/postGemini（其内部 120s 超时 + 自动重试，不适合探活）。
 */
@RestController
@RequestMapping("/admin")
public class AdminTestController {

    private static final Logger log = LoggerFactory.getLogger(AdminTestController.class);

    /** 测试专用 HttpClient：连接超时 5s，单请求超时在 sendTestRequest 里设为 10s，无重试 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ChannelMapper channelMapper;
    private final ModelRouteMapper routeMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final GatewayRoutingService gatewayRoutingService;

    public AdminTestController(ChannelMapper channelMapper, ModelRouteMapper routeMapper,
                               KeyService keyService, UpstreamClient upstreamClient,
                               GatewayRoutingService gatewayRoutingService) {
        this.channelMapper = channelMapper;
        this.routeMapper = routeMapper;
        this.keyService = keyService;
        this.upstreamClient = upstreamClient;
        this.gatewayRoutingService = gatewayRoutingService;
    }

    /** POST /admin/channels/{id}/test：直连上游最小请求探活，HTTP 2xx 即成功，不落 CallLog。
     *  测试模型优先级：请求体 modelName（前端测试弹窗可填）→ 该渠道已配路由的模型 → 渠道类型默认。
     *  详细日志：发起与完成各一条（含渠道/模型/HTTP 状态/耗时/错误），供服务端排查。 */
    @PostMapping("/channels/{id}/test")
    public ApiResponse<Map<String, Object>> testChannel(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, String> body) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) throw new BusinessException(40401, "渠道不存在");
        // 测试模型选择：请求体 modelName 优先，其次该渠道已配路由中的 chat 类模型（跳过 image/video 等生成类，它们不支持 chat 探活），最后渠道类型默认
        String modelName = body == null ? null : body.get("modelName");
        if (modelName == null || modelName.isBlank()) {
            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getChannelId, id).orderByAsc(ModelRoute::getModelName));
            for (ModelRoute r : routes) {
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

    /** POST /admin/routes/{id}/test：走真实网关链路（渠道选择/格式转换/转发/落 CallLog），2xx 判成功（gemini 转换后无 choices 键，不能用 choices 判定） */
    @PostMapping("/routes/{id}/test")
    public ApiResponse<Map<String, Object>> testRoute(@PathVariable String id) {
        ModelRoute route = routeMapper.selectById(id);
        if (route == null) throw new BusinessException(40401, "路由不存在");
        String body = "{\"model\":\"" + route.getModelName()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}";
        log.info("【路由测试】发起: model={} channelId={}", route.getModelName(), route.getChannelId());
        long start = System.currentTimeMillis();
        try {
            GatewayRoutingService.RouteResult result = gatewayRoutingService.route("/chat/completions", body);
            long durationMs = System.currentTimeMillis() - start;
            // gemini 渠道经 GeminiFormatConverter.toOpenAiResponse 转换后无 choices 键，改用 2xx 状态码判定成功（与 testChannel 一致）
            boolean ok = result.status() >= 200 && result.status() < 300;
            String error = ok ? null : upstreamClient.extractError(result.body());
            log.info("【路由测试】完成: model={} ok={} http={} cost={}ms{}", route.getModelName(), ok,
                    result.status(), durationMs, error == null ? "" : " error=" + error);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", ok);
            map.put("status", result.status());
            map.put("durationMs", durationMs);
            if (!ok) {
                map.put("error", error);
            }
            return ApiResponse.ok(map);
        } catch (Exception e) {
            // route() 抛业务异常（无路由/渠道全挂等）也按失败结果返回
            long durationMs = System.currentTimeMillis() - start;
            log.warn("【路由测试】异常: model={} cost={}ms error={}", route.getModelName(), durationMs, e.getMessage());
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
