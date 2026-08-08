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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连通性测试端点：通道测试直连上游（不落 CallLog）；模型路由测试走完整真实链路（会真实落一条 CallLog，设计接受）。
 * 通道测试使用独立 HttpClient（connectTimeout 5s + 单请求 10s、无重试），
 * 不复用 UpstreamClient.postJson/postGemini（其内部 120s 超时 + 自动重试，不适合探活）。
 */
@RestController
@RequestMapping("/admin")
public class AdminTestController {

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

    /** POST /admin/channels/{id}/test：直连上游最小请求探活，HTTP 2xx 即成功，不落 CallLog */
    @PostMapping("/channels/{id}/test")
    public ApiResponse<Map<String, Object>> testChannel(@PathVariable String id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) throw new BusinessException(40401, "渠道不存在");
        long start = System.currentTimeMillis();
        try {
            String apiKey = keyService.decrypt(channel.getApiKey());
            // 按渠道类型构造最小请求体（max_tokens=1，仅验证连通不追求正确回复）
            String bodyJson = switch (channel.getType() == null ? "" : channel.getType()) {
                case "gemini" -> "{\"contents\":[{\"parts\":[{\"text\":\"ping\"}]}]}";
                case "minimax" -> "{\"model\":\"MiniMax-Text-01\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}";
                default -> "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}";
            };
            HttpResponse<String> resp = sendTestRequest(channel, apiKey, bodyJson);
            long durationMs = System.currentTimeMillis() - start;
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", ok);
            result.put("durationMs", durationMs);
            if (!ok) {
                result.put("error", upstreamClient.extractError(resp.body()));
            }
            return ApiResponse.ok(result);
        } catch (Exception e) {
            // 连接异常 / 超时 / 解密失败等：返回失败结果而不是 500
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("durationMs", System.currentTimeMillis() - start);
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
        long start = System.currentTimeMillis();
        try {
            GatewayRoutingService.RouteResult result = gatewayRoutingService.route("/chat/completions", body);
            long durationMs = System.currentTimeMillis() - start;
            // gemini 渠道经 GeminiFormatConverter.toOpenAiResponse 转换后无 choices 键，改用 2xx 状态码判定成功（与 testChannel 一致）
            boolean ok = result.status() >= 200 && result.status() < 300;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", ok);
            map.put("status", result.status());
            map.put("durationMs", durationMs);
            if (!ok) {
                map.put("error", upstreamClient.extractError(result.body()));
            }
            return ApiResponse.ok(map);
        } catch (Exception e) {
            // route() 抛业务异常（无路由/渠道全挂等）也按失败结果返回
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", false);
            map.put("status", 500);
            map.put("durationMs", System.currentTimeMillis() - start);
            map.put("error", e.getMessage());
            return ApiResponse.ok(map);
        }
    }

    /** 直发最小请求：gemini Key 走 query 参数（与 UpstreamClient.postGemini 一致），其余 Bearer */
    private HttpResponse<String> sendTestRequest(Channel channel, String apiKey, String bodyJson) throws Exception {
        String base = stripTrailingSlash(channel.getBaseUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))   // 单请求超时 10s，探活不等 120s
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
}
