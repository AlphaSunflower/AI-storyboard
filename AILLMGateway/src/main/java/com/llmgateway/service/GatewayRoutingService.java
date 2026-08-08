package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.List;

/**
 * 路由核心：解析 model → 查 model_route → 取 enabled channel（按 priority 升序）
 * → AES 解密渠道 Key → 按渠道类型转发（透传 / Gemini 转换）。
 * 返回上游状态码 + 响应体（透传，不做二次包装）。
 */
@Service
public class GatewayRoutingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutingService.class);

    /** 路由结果：上游 HTTP 状态码 + 响应体 */
    public record RouteResult(int status, String body) {}

    /** 渠道转发结果：上游 HTTP 状态码 + 响应体（forward 内部用，避免匿名 HttpResponse 实现） */
    private record ForwardResult(int status, String body) {}

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final GeminiFormatConverter geminiConverter;
    private final CallLogService callLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewayRoutingService(ModelRouteMapper routeMapper, ChannelMapper channelMapper,
                                 KeyService keyService, UpstreamClient upstreamClient,
                                 GeminiFormatConverter geminiConverter, CallLogService callLogService) {
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
        this.keyService = keyService;
        this.upstreamClient = upstreamClient;
        this.geminiConverter = geminiConverter;
        this.callLogService = callLogService;
    }

    /** 处理 OpenAI 兼容 chat/images 请求（path 为 /chat/completions 或 /images/generations） */
    public RouteResult route(String path, String requestBody) {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        int status = 500;
        String error = null;
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            String modelName = body.path("model").asText("");
            model = modelName;
            if (modelName.isBlank()) throw new BusinessException(40001, "model 不能为空");

            // 1. 查该模型的所有路由（一个模型可指向多个渠道，按 priority 轮换）
            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getModelName, model));
            if (routes == null || routes.isEmpty()) {
                throw new BusinessException(40401, "no route for model: " + model);
            }

            // 2. 候选渠道（路由指向的 enabled 渠道，按 priority 升序）
            List<Channel> candidates = routes.stream()
                    .map(r -> channelMapper.selectById(r.getChannelId()))
                    .filter(c -> c != null && Boolean.TRUE.equals(c.getEnabled()))
                    .sorted(Comparator.comparingInt(c -> c.getPriority() == null ? 0 : c.getPriority()))
                    .toList();
            if (candidates.isEmpty()) {
                throw new BusinessException(50301, "no available channel for model: " + model);
            }

            // 3. 逐个渠道尝试（失败切下一个）
            for (Channel channel : candidates) {
                try {
                    ForwardResult fwd = forward(channel, path, requestBody);
                    status = fwd.status();
                    channelId = channel.getId();
                    String bodyStr = fwd.body();
                    if (status >= 400) {
                        error = upstreamClient.extractError(bodyStr);
                        log.warn("渠道 {} 返回 {}: {}", channel.getName(), status, error);
                        // 429/5xx 尝试下一个渠道；4xx 业务错误直接透传
                        if (status < 500) {
                            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, error, null);
                            return new RouteResult(status, bodyStr);
                        }
                        continue;
                    }
                    callLogService.log(model, channelId, "success", System.currentTimeMillis() - start, null, null);
                    return new RouteResult(status, bodyStr);
                } catch (BusinessException be) {
                    throw be;
                } catch (Exception e) {
                    error = e.getMessage();
                    log.warn("渠道 {} 调用异常: {}", channel.getName(), error);
                }
            }
            throw new BusinessException(50301, "all channels failed for model: " + model);
        } catch (BusinessException be) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, be.getMessage(), null);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, e.getMessage(), null);
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** 按渠道类型转发：openai_compatible 透传 / gemini 转换 */
    private ForwardResult forward(Channel channel, String path, String requestBody) throws Exception {
        String apiKey = keyService.decrypt(channel.getApiKey());
        if ("gemini".equals(channel.getType())) {
            String geminiBody = geminiConverter.toGeminiRequest(requestBody);
            HttpResponse<String> resp = upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody);
            if (resp.statusCode() == 200) {
                // Gemini 200：转回 OpenAI 格式返回
                return new ForwardResult(200, geminiConverter.toOpenAiResponse(resp.body()));
            }
            return new ForwardResult(resp.statusCode(), resp.body());
        }
        // openai_compatible：原路径透传，Bearer 换渠道 Key
        HttpResponse<String> resp = upstreamClient.postJson(channel.getBaseUrl(), path, apiKey, requestBody);
        return new ForwardResult(resp.statusCode(), resp.body());
    }
}
