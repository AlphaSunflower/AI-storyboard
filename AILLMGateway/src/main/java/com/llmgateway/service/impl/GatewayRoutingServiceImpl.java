package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.GeminiFormatConverter;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 路由核心实现：解析 model → 查 model_route → 取 enabled channel（按 priority 升序）
 * → AES 解密渠道 Key → 按渠道类型转发（透传 / Gemini 转换）。
 */
@Service
@RequiredArgsConstructor
public class GatewayRoutingServiceImpl implements GatewayRoutingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutingServiceImpl.class);

    /** 渠道转发结果：上游 HTTP 状态码 + 响应体（forward 内部用，避免匿名 HttpResponse 实现） */
    private record ForwardResult(int status, String body) {}

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final ModelParamsMapper modelParamsMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final GeminiFormatConverter geminiConverter;
    private final CallLogService callLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void streamChat(String path, String requestBody, java.util.function.Consumer<byte[]> sink) throws Exception {
        JsonNode body = objectMapper.readTree(requestBody);
        String modelName = body.path("model").asText("");
        if (modelName.isBlank()) throw new BusinessException(40001, "model 不能为空");

        // 候选渠道（与 route 同规则：model_route → enabled 渠道按 priority 升序）
        List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .eq(ModelRoute::getModelName, modelName));
        if (routes == null || routes.isEmpty()) throw new BusinessException(40401, "no route for model: " + modelName);
        List<Channel> candidates = routes.stream()
                .map(r -> channelMapper.selectById(r.getChannelId()))
                .filter(c -> c != null && Boolean.TRUE.equals(c.getEnabled()))
                .sorted(Comparator.comparingInt(c -> c.getPriority() == null ? 0 : c.getPriority()))
                .toList();
        if (candidates.isEmpty()) throw new BusinessException(50301, "no available channel for model: " + modelName);

        // 逐个渠道尝试：openai_compatible 原生 SSE 透传；gemini 降级非流式（转换后回写）
        Exception lastError = null;
        for (Channel channel : candidates) {
            try {
                String apiKey = keyService.decrypt(channel.getApiKey());
                if ("gemini".equals(channel.getType())) {
                    // gemini 无 SSE 透传路径：降级非流式（上游不支持 stream 透传时兼容）
                    String geminiBody = geminiConverter.toGeminiRequest(requestBody);
                    HttpResponse<String> resp = upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody);
                    byte[] out = (resp.statusCode() == 200
                            ? geminiConverter.toOpenAiResponse(resp.body())
                            : resp.body()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    sink.accept(out);
                    return;
                }
                // openai_compatible：流式透传上游 SSE
                HttpResponse<java.io.InputStream> resp = upstreamClient.postJsonStream(
                        channel.getBaseUrl(), path, apiKey, requestBody);
                if (resp.statusCode() != 200) {
                    String err = new String(resp.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    throw new RuntimeException("upstream " + resp.statusCode() + ": " + err);
                }
                try (java.io.InputStream in = resp.body()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        sink.accept(java.util.Arrays.copyOf(buf, n));
                    }
                }
                return;
            } catch (Exception e) {
                // 客户端断开（前端刷新/取消/超时）：不是渠道故障，继续尝试下一个渠道只会重演同一错误
                if (isClientAbort(e)) {
                    log.debug("客户端中断流式转发，停止: channel={}", channel.getId());
                    return;
                }
                lastError = e;
                log.warn("流式渠道失败，尝试下一个: channel={}, error={}", channel.getId(), e.getMessage());
            }
        }
        throw lastError != null ? lastError : new BusinessException(50202, "upstream stream failed");
    }

    /** 判断异常链是否为客户端断开（ClientAbortException / AsyncRequestNotUsableException） */
    private static boolean isClientAbort(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof org.apache.catalina.connector.ClientAbortException
                    || cur instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException) {
                return true;
            }
        }
        return false;
    }

    @Override
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
                        // 429/5xx 尝试下一个渠道；其余 4xx 业务错误直接透传
                        if (status != 429 && status < 500) {
                            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, error, null, null);
                            return new RouteResult(status, bodyStr);
                        }
                        continue;
                    }
                    callLogService.log(model, channelId, "success", System.currentTimeMillis() - start, null, null, null);
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
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, be.getMessage(), null, null);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start, e.getMessage(), null, null);
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** 按渠道类型转发：openai_compatible 透传 / gemini 转换 */
    private ForwardResult forward(Channel channel, String path, String requestBody) throws Exception {
        String apiKey = keyService.decrypt(channel.getApiKey());
        if ("gemini".equals(channel.getType())) {
            // Gemini 图片生成：单次 generateContent 只回一张图；n>1 时循环 N 次再合并 data[]
            int imageN = path.contains("images/generations") ? readImageN(requestBody) : 1;
            if (imageN > 1) {
                return forwardGeminiImageMulti(channel, apiKey, requestBody, imageN);
            }
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

    /**
     * Gemini 图片多图：循环调用 N 次 generateContent，逐次转 OpenAI 格式并合并 data[]。
     * 单张失败跳过（不拖垮整批），全部失败回首个错误状态码/响应体。
     */
    private ForwardResult forwardGeminiImageMulti(Channel channel, String apiKey, String requestBody, int n) throws Exception {
        String geminiBody = geminiConverter.toGeminiRequest(requestBody);
        ArrayNode merged = objectMapper.createArrayNode();
        int firstErrorStatus = 0;
        String firstErrorBody = null;
        for (int i = 0; i < n; i++) {
            HttpResponse<String> resp = upstreamClient.postGemini(channel.getBaseUrl(), apiKey, geminiBody);
            if (resp.statusCode() == 200) {
                JsonNode oai = objectMapper.readTree(geminiConverter.toOpenAiResponse(resp.body()));
                JsonNode data = oai.path("data");
                if (data.isArray()) {
                    for (JsonNode item : data) merged.add(item);
                }
            } else if (firstErrorStatus == 0) {
                firstErrorStatus = resp.statusCode();
                firstErrorBody = resp.body();
            }
        }
        if (merged.isEmpty()) {
            return new ForwardResult(firstErrorStatus != 0 ? firstErrorStatus : 502,
                    firstErrorBody != null ? firstErrorBody : "{\"error\":{\"message\":\"Gemini image generation returned no images\"}}");
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.put("created", System.currentTimeMillis() / 1000);
        out.set("data", merged);
        return new ForwardResult(200, objectMapper.writeValueAsString(out));
    }

    /** 解析图片请求的 n（缺失/非法默认 1） */
    private int readImageN(String requestBody) {
        try {
            int n = objectMapper.readTree(requestBody).path("n").asInt(1);
            return n > 0 ? n : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    @Override
    public String fetchModels(String type) {
        // 从 model_route 返回可用模型列表（OpenAI 风格 {data:[{id,type}]}），供调用方（如 AI 分镜前端）动态获取生图/生视频模型
        // type 过滤（image/video/text/vision）；渠道须启用；同一模型多渠道轮换时去重保留首个
        List<ModelRoute> routes = routeMapper.selectList(null);
        Set<String> enabledChannels = channelMapper.selectList(null).stream()
                .filter(c -> c.getEnabled() == null || c.getEnabled())
                .map(Channel::getId)
                .collect(Collectors.toSet());
        Map<String, String> modelTypeMap = new LinkedHashMap<>();   // modelName -> type
        for (ModelRoute r : routes) {
            if (r.getChannelId() == null || !enabledChannels.contains(r.getChannelId())) continue;
            String t = r.getType() == null || r.getType().isBlank() ? "text" : r.getType();
            if (type != null && !type.isBlank() && !type.equals(t)) continue;
            modelTypeMap.putIfAbsent(r.getModelName(), t);
        }
        // 查全量模型参数表，按 modelName 建立索引（组装 data[] 时逐模型取参）
        Map<String, ModelParams> paramsMap = modelParamsMapper.selectList(null).stream()
                .collect(Collectors.toMap(ModelParams::getModelName, p -> p, (a, b) -> a));
        // 组装 OpenAI 风格响应：{"object":"list","data":[{"id":..,"object":"model","type":..,"params":..}]}
        List<Map<String, Object>> data = new ArrayList<>();
        modelTypeMap.forEach((name, t) -> {
            ModelParams mp = paramsMap.get(name);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", name);
            m.put("object", "model");
            m.put("type", t);
            // 默认模型标记（每类型至多一个，后端拉取作为兜底默认模型）
            m.put("is_default", mp != null && Boolean.TRUE.equals(mp.getIsDefault()));
            // 按 model_name 组装 params（能力+默认值；未配置 → null）
            m.put("params", buildParams(mp));
            data.add(m);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("模型列表序列化失败", e);
        }
    }

    /**
     * 按 model_name 组装 params 对象（能力枚举 + 默认值）：
     * - 无配置记录 → null
     * - 各字段非空才放入（全空 → null）
     * text：defaults{temperature,max_tokens,top_p}；image：n{min,max,default} + sizes/sizeDefault + qualities/qualityDefault + styles/styleDefault
     * video：durations(Integer[])/durationDefault + resolutions/resolutionDefault + aspectRatios/aspectRatioDefault
     */
    private Map<String, Object> buildParams(ModelParams mp) {
        if (mp == null) return null;
        Map<String, Object> params = new LinkedHashMap<>();
        // text 默认值对象（任一非空才放；temperature/top_p 存 TEXT，转 double）
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (mp.getTemperature() != null) defaults.put("temperature", Double.parseDouble(mp.getTemperature()));
        if (mp.getMaxTokens() != null) defaults.put("max_tokens", mp.getMaxTokens());
        if (mp.getTopP() != null) defaults.put("top_p", Double.parseDouble(mp.getTopP()));
        if (!defaults.isEmpty()) params.put("defaults", defaults);
        // image：n 范围 + 默认（各自非空才放）
        if (mp.getNMin() != null || mp.getNMax() != null || mp.getNDefault() != null) {
            Map<String, Object> n = new LinkedHashMap<>();
            if (mp.getNMin() != null) n.put("min", mp.getNMin());
            if (mp.getNMax() != null) n.put("max", mp.getNMax());
            if (mp.getNDefault() != null) n.put("default", mp.getNDefault());
            params.put("n", n);
        }
        // image：枚举 + 默认（逗号分隔 → 数组；空跳过）
        putCsvList(params, "sizes", mp.getSizes());
        putDefault(params, "sizeDefault", mp.getSizeDefault());
        putCsvList(params, "qualities", mp.getQualities());
        putDefault(params, "qualityDefault", mp.getQualityDefault());
        putCsvList(params, "styles", mp.getStyles());
        putDefault(params, "styleDefault", mp.getStyleDefault());
        // video：时长（Integer 数组）+ 默认、分辨率/画幅枚举 + 默认
        putCsvIntList(params, "durations", mp.getDurations());
        putIntDefault(params, "durationDefault", mp.getDurationDefault());
        putCsvList(params, "resolutions", mp.getResolutions());
        putDefault(params, "resolutionDefault", mp.getResolutionDefault());
        putCsvList(params, "aspectRatios", mp.getAspectRatios());
        putDefault(params, "aspectRatioDefault", mp.getAspectRatioDefault());
        // video：输入约束（范围类嵌套 {min,max}，单值类平铺数字）
        putRange(params, "refImages", mp.getRefImagesMin(), mp.getRefImagesMax());
        putRange(params, "refVideos", mp.getRefVideosMin(), mp.getRefVideosMax());
        putRange(params, "audioCount", mp.getAudioCountMin(), mp.getAudioCountMax());
        putRange(params, "audioSegmentDuration", mp.getAudioSegmentDurationMin(), mp.getAudioSegmentDurationMax());
        putRange(params, "videoSegmentDuration", mp.getVideoSegmentDurationMin(), mp.getVideoSegmentDurationMax());
        putInt(params, "maxTotalDuration", mp.getMaxTotalDuration());
        putInt(params, "maxTotalFiles", mp.getMaxTotalFiles());
        putInt(params, "maxVideoSizeMB", mp.getMaxVideoSizeMb());
        putInt(params, "maxImageSizeMB", mp.getMaxImageSizeMb());
        putInt(params, "maxAudioSizeMB", mp.getMaxAudioSizeMb());
        putInt(params, "maxRequestBodyMB", mp.getMaxRequestBodyMb());
        putInt(params, "maxPromptChars", mp.getMaxPromptChars());
        return params.isEmpty() ? null : params;
    }

    /** 逗号分隔字符串 → 字符串数组（去空白；空串跳过；全部为空/空值 → 不放） */
    private void putCsvList(Map<String, Object> params, String key, String csv) {
        if (csv == null || csv.isBlank()) return;
        List<String> list = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (!list.isEmpty()) params.put(key, list);
    }

    /** 逗号分隔数字字符串 → Integer 数组（解析失败跳过；空 → 不放） */
    private void putCsvIntList(Map<String, Object> params, String key, String csv) {
        if (csv == null || csv.isBlank()) return;
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                list.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // 单个非法值跳过，不影响整体
            }
        }
        if (!list.isEmpty()) params.put(key, list);
    }

    /** 默认值非空才放（空串/空白 → 不放） */
    private void putDefault(Map<String, Object> params, String key, String val) {
        if (val != null && !val.isBlank()) params.put(key, val.trim());
    }

    /** 数字默认值（如时长默认秒数）：可解析为 Integer 则放数字（契约 durationDefault 为 number），否则原样字符串 */
    private void putIntDefault(Map<String, Object> params, String key, String val) {
        if (val == null || val.isBlank()) return;
        try {
            params.put(key, Integer.parseInt(val.trim()));
        } catch (NumberFormatException e) {
            params.put(key, val.trim());
        }
    }

    /** 范围对象 {min,max}：任一端非空才放 */
    private void putRange(Map<String, Object> params, String key, Integer min, Integer max) {
        if (min == null && max == null) return;
        Map<String, Object> r = new LinkedHashMap<>();
        if (min != null) r.put("min", min);
        if (max != null) r.put("max", max);
        params.put(key, r);
    }

    /** Integer 直放（null 跳过） */
    private void putInt(Map<String, Object> params, String key, Integer val) {
        if (val != null) params.put(key, val);
    }
}
