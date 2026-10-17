package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelCatalogCache;
import com.llmgateway.service.ModelParamsSpecNormalizer;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.vendor.route.ChatImageVendorAdapter;
import com.llmgateway.vendor.route.RouteVendorRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 路由核心编排器：解析 model → 查 model_route → 取 enabled channel（按 priority 升序）
 * → AES 解密渠道 Key → 按渠道类型经 {@link ChatImageVendorAdapter} 分发（透传 / Gemini 转换）。
 * <p>
 * 厂商协议差异已下沉路由适配器，新增协议无需改本编排器。
 */
@Service
@RequiredArgsConstructor
public class GatewayRoutingServiceImpl implements GatewayRoutingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutingServiceImpl.class);

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final ModelParamsMapper modelParamsMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final RouteVendorRegistry routeRegistry;
    private final CallLogService callLogService;
    /** 模型列表缓存 / 默认模型 / 版本号的唯一持有者（key 命名与失效逻辑都收敛在它里面） */
    private final ModelCatalogCache catalogCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void streamChat(String path, String requestBody, java.util.function.Consumer<byte[]> sink) throws Exception {
        streamChat(path, requestBody, sink, null);
    }

    @Override
    public void streamChat(String path, String requestBody,
                           java.util.function.Consumer<byte[]> sink, String callId) throws Exception {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            String modelName = body.path("model").asText("");
            model = modelName;
            if (modelName.isBlank()) throw new BusinessException(40001, "model 不能为空");

            // 候选渠道（与 route 同规则：model_route → enabled 渠道按 priority 升序）
            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getModelName, modelName));
            if (routes == null || routes.isEmpty()) {
                throw new BusinessException(40401, "no route for model: " + modelName);
            }
            List<Channel> candidates = resolveCandidates(routes);
            if (candidates.isEmpty()) {
                throw new BusinessException(50301, "no available channel for model: " + modelName);
            }

            // 逐个渠道尝试：各渠道协议由适配器转发；客户端中断不是渠道故障，停止不再换下一个
            Exception lastError = null;
            for (Channel channel : candidates) {
                channelId = channel.getId();
                AtomicBoolean emitted = new AtomicBoolean(false);
                try {
                    String apiKey = keyService.decrypt(channel.getApiKey());
                    routeRegistry.resolve(channel.getType()).stream(path, requestBody, channel, apiKey, bytes -> {
                        if (bytes != null && bytes.length > 0) emitted.set(true);
                        sink.accept(bytes);
                    });
                    long duration = System.currentTimeMillis() - start;
                    callLogService.log(model, channelId, "success", duration, null, null, null, callId);
                    log.info("流式调用完成: model={}, channel={}, duration={}ms, callId={}",
                            model, channelId, duration, callId);
                    return;
                } catch (Exception e) {
                    if (isClientAbort(e)) {
                        long duration = System.currentTimeMillis() - start;
                        callLogService.log(model, channelId, "error", duration,
                                "client aborted", null, null, callId);
                        log.debug("客户端中断流式转发，停止: channel={}, callId={}", channel.getId(), callId);
                        return;
                    }
                    if (emitted.get()) {
                        callLogService.log(model, channelId, "error",
                                System.currentTimeMillis() - start,
                                "stream failed after partial output", null, null, callId);
                        throw e;
                    }
                    lastError = e;
                    log.warn("流式渠道失败，尝试下一个: channel={}, callId={}, error={}",
                            channel.getId(), callId, e.getMessage());
                }
            }
            throw lastError != null ? lastError : new BusinessException(50202, "upstream stream failed");
        } catch (BusinessException be) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                    be.getMessage(), null, null, callId);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                    e.getMessage(), null, null, callId);
            throw e;
        }
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
        return route(path, requestBody, null);
    }

    @Override
    public RouteResult route(String path, String requestBody, String callId) {
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

            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getModelName, model));
            if (routes == null || routes.isEmpty()) {
                throw new BusinessException(40401, "no route for model: " + model);
            }
            List<Channel> candidates = resolveCandidates(routes);
            if (candidates.isEmpty()) {
                throw new BusinessException(50301, "no available channel for model: " + model);
            }

            // 逐个渠道尝试（失败切下一个）
            for (Channel channel : candidates) {
                try {
                    RouteResult fwd = forward(channel, path, requestBody);
                    status = fwd.status();
                    // 图响应归一化（openai_compatible 直传模型配了 response.sync 才处理；gemini 已由 forward 转换不再动）
                    if (fwd.status() == 200 && path.contains("images/generations") && !"gemini".equals(channel.getType())) {
                        ModelParams mp = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                                .eq(ModelParams::getModelName, model));
                        com.fasterxml.jackson.databind.JsonNode ispec = mp != null ? ModelParamsSpecNormalizer.spec(mp.getParamsSchema()) : null;
                        if (ispec != null && ispec.path("response").path("sync").isObject()) {
                            fwd = new RouteResult(200, ModelParamsSpecNormalizer.normalizeImageSync(fwd.body(), ispec));
                        }
                    }
                    channelId = channel.getId();
                    String bodyStr = fwd.body();
                    if (status >= 400) {
                        error = upstreamClient.extractError(bodyStr);
                        log.warn("渠道 {} 返回 {}: {}", channel.getName(), status, error);
                        // 429/5xx 尝试下一个渠道；其余 4xx 业务错误直接透传
                        if (status != 429 && status < 500) {
                            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                                    error, null, null, callId);
                            return new RouteResult(status, bodyStr);
                        }
                        continue;
                    }
                    callLogService.log(model, channelId, "success", System.currentTimeMillis() - start,
                            null, null, null, callId);
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
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                    be.getMessage(), null, null, callId);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                    e.getMessage(), null, null, callId);
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    /** 按渠道类型经适配器转发（openai_compatible 透传 / gemini 转换），协议差异全部下沉适配器 */
    private RouteResult forward(Channel channel, String path, String requestBody) throws Exception {
        String apiKey = keyService.decrypt(channel.getApiKey());
        return routeRegistry.resolve(channel.getType()).forward(path, requestBody, channel, apiKey);
    }

    /** 路由指向的 enabled 渠道，按 is_default 降序 + priority 升序（默认路由优先） */
    private List<Channel> resolveCandidates(List<ModelRoute> routes) {
        return routes.stream()
                .map(r -> {
                    Channel ch = channelMapper.selectById(r.getChannelId());
                    return ch != null ? new Object[]{r, ch} : null;
                })
                .filter(arr -> arr != null && Boolean.TRUE.equals(((Channel) arr[1]).getEnabled()))
                .sorted(Comparator
                        .<Object[], Boolean>comparing(arr -> Boolean.TRUE.equals(((ModelRoute) arr[0]).getIsDefault())).reversed()
                        .thenComparingInt(arr -> ((Channel) arr[1]).getPriority() == null ? 0 : ((Channel) arr[1]).getPriority()))
                .map(arr -> (Channel) arr[1])
                .toList();
    }

    @Override
    public String fetchModels(String type) {
        // 读缓存（miss/Redis 异常一律降级查库）；避免前端每个参数卡片渲染都触发 model_route+channel+model_params 三表全扫
        String cached = catalogCache.readModels(type);
        if (cached != null) return cached;
        List<ModelRoute> routes = routeMapper.selectList(null);
        Set<String> enabledChannels = channelMapper.selectList(null).stream()
                .filter(c -> c.getEnabled() == null || c.getEnabled())
                .map(Channel::getId)
                .collect(Collectors.toSet());
        Map<String, String> modelTypeMap = new LinkedHashMap<>();
        for (ModelRoute r : routes) {
            if (r.getChannelId() == null || !enabledChannels.contains(r.getChannelId())) continue;
            String t = r.getType() == null || r.getType().isBlank() ? "text" : r.getType();
            if (type != null && !type.isBlank() && !type.equals(t)) continue;
            modelTypeMap.putIfAbsent(r.getModelName(), t);
        }
        // 每类型的默认模型：只认「路由默认」（Redis 运行时值优先，其次 model_route.is_default）。
        // 参数表的 is_default 是历史遗留的第二开关，不再决定列表标记——否则两处会各自为政
        // （Redis 按 A 生成、列表却标 B，前端就按 B 预选）。
        Map<String, String> defaultByType = resolveDefaultByType(routes, enabledChannels, modelTypeMap);
        // 按 (model_name|type) 建索引：同一模型可 text/vision 各一套参数
        Map<String, ModelParams> paramsMap = modelParamsMapper.selectList(null).stream()
                .collect(Collectors.toMap(p -> p.getModelName() + "|" + p.getType(), p -> p, (a, b) -> a));
        List<Map<String, Object>> data = new ArrayList<>();
        modelTypeMap.forEach((name, t) -> {
            ModelParams mp = paramsMap.get(name + "|" + t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", name);
            m.put("object", "model");
            m.put("type", t);
            m.put("is_default", name.equals(defaultByType.get(t)));
            if (mp != null && mp.getContextWindow() != null) {
                m.put("contextWindow", mp.getContextWindow());
            }
            m.put("params", buildParams(mp));
            data.add(m);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        // 目录版本号随响应下发：消费端（主后端/MoonAgent/画布的 GatewayModelServiceImpl）
        // 据此判断本地缓存是否过期，前端也能靠它跳过无变化的重渲染
        String version = catalogCache.currentVersion();
        if (version != null) result.put("version", version);
        result.put("data", data);
        try {
            String json = objectMapper.writeValueAsString(result);
            catalogCache.writeModels(type, json);
            return json;
        } catch (Exception e) {
            throw new RuntimeException("模型列表序列化失败", e);
        }
    }

    /**
     * 解析每种类型的默认模型：先取路由表 is_default（含启用渠道），再用 Redis
     * {@code gateway:default_model:<type>} 覆盖——Redis 是运行时真正生效的值，
     * 但只有当它确实出现在本次列表里时才采信（渠道被禁用/路由被删时不误导前端）。
     */
    private Map<String, String> resolveDefaultByType(List<ModelRoute> routes,
                                                     Set<String> enabledChannels,
                                                     Map<String, String> modelTypeMap) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (ModelRoute route : routes) {
            if (route.getChannelId() == null || !enabledChannels.contains(route.getChannelId())) continue;
            if (!Boolean.TRUE.equals(route.getIsDefault())) continue;
            String routeType = route.getType() == null || route.getType().isBlank()
                    ? "text" : route.getType();
            if (!routeType.equals(modelTypeMap.get(route.getModelName()))) continue;
            defaults.putIfAbsent(routeType, route.getModelName());
        }
        for (String modelType : new java.util.LinkedHashSet<>(modelTypeMap.values())) {
            String effective = readDefaultModelFromRedis(modelType);
            if (effective != null && modelType.equals(modelTypeMap.get(effective))) {
                defaults.put(modelType, effective);
            }
        }
        return defaults;
    }

    private String readDefaultModelFromRedis(String type) {
        return catalogCache.readDefault(type);
    }

    /**
     * 按 model_name 组装 params 对象（能力枚举 + 默认值）：
     * text：defaults{temperature,max_tokens,top_p}；image：n{min,max,default} + sizes/sizeDefault + qualities/qualityDefault + styles/styleDefault
     * video：durations(Integer[])/durationDefault + resolutions/resolutionDefault + aspectRatios/aspectRatioDefault
     */
    private Map<String, Object> buildParams(ModelParams mp) {
        if (mp == null) return null;
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (mp.getTemperature() != null) defaults.put("temperature", Double.parseDouble(mp.getTemperature()));
        if (mp.getMaxTokens() != null) defaults.put("max_tokens", mp.getMaxTokens());
        if (mp.getTopP() != null) defaults.put("top_p", Double.parseDouble(mp.getTopP()));
        if (!defaults.isEmpty()) params.put("defaults", defaults);
        if (mp.getNMin() != null || mp.getNMax() != null || mp.getNDefault() != null) {
            Map<String, Object> n = new LinkedHashMap<>();
            if (mp.getNMin() != null) n.put("min", mp.getNMin());
            if (mp.getNMax() != null) n.put("max", mp.getNMax());
            if (mp.getNDefault() != null) n.put("default", mp.getNDefault());
            params.put("n", n);
        }
        putCsvList(params, "sizes", mp.getSizes());
        putDefault(params, "sizeDefault", mp.getSizeDefault());
        putCsvList(params, "qualities", mp.getQualities());
        putDefault(params, "qualityDefault", mp.getQualityDefault());
        putCsvList(params, "styles", mp.getStyles());
        putDefault(params, "styleDefault", mp.getStyleDefault());
        putCsvIntList(params, "durations", mp.getDurations());
        putIntDefault(params, "durationDefault", mp.getDurationDefault());
        putCsvList(params, "resolutions", mp.getResolutions());
        putDefault(params, "resolutionDefault", mp.getResolutionDefault());
        putCsvList(params, "aspectRatios", mp.getAspectRatios());
        putDefault(params, "aspectRatioDefault", mp.getAspectRatioDefault());
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
        // Ollama 兼容渠道扩展参数（思考/等级/num_ctx 等），解析失败忽略，不阻塞 /v1/models
        if (mp.getOllamaParams() != null && !mp.getOllamaParams().isBlank()) {
            try {
                JsonNode o = objectMapper.readTree(mp.getOllamaParams());
                if (o.isObject() && !o.isEmpty()) params.put("ollama", o);
            } catch (Exception e) {
                log.warn("解析 ollama_params 失败，忽略: model={}, error={}", mp.getModelName(), e.getMessage());
            }
        }

        // self-describing spec：把 params_schema 解析后作为 spec 附到 params（供前端/Agent 通用渲染）
        String ps = mp.getParamsSchema();
        if (ps != null && !ps.isBlank()) {
            JsonNode specNode = ModelParamsSpecNormalizer.spec(ps);
            if (specNode != null) params.put("spec", specNode);
        }
        return params.isEmpty() ? null : params;
    }

    private void putCsvList(Map<String, Object> params, String key, String csv) {
        if (csv == null || csv.isBlank()) return;
        List<String> list = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (!list.isEmpty()) params.put(key, list);
    }

    private void putCsvIntList(Map<String, Object> params, String key, String csv) {
        if (csv == null || csv.isBlank()) return;
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                list.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!list.isEmpty()) params.put(key, list);
    }

    private void putDefault(Map<String, Object> params, String key, String val) {
        if (val != null && !val.isBlank()) params.put(key, val.trim());
    }

    private void putIntDefault(Map<String, Object> params, String key, String val) {
        if (val == null || val.isBlank()) return;
        try {
            params.put(key, Integer.parseInt(val.trim()));
        } catch (NumberFormatException e) {
            params.put(key, val.trim());
        }
    }

    private void putRange(Map<String, Object> params, String key, Integer min, Integer max) {
        if (min == null && max == null) return;
        Map<String, Object> r = new LinkedHashMap<>();
        if (min != null) r.put("min", min);
        if (max != null) r.put("max", max);
        params.put(key, r);
    }

    private void putInt(Map<String, Object> params, String key, Integer val) {
        if (val != null) params.put(key, val);
    }
}
