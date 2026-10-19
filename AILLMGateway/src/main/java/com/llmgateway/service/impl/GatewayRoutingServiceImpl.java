package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelFallback;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelFallbackMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.ChannelKeyService;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelCatalogCache;
import com.llmgateway.service.ModelParamsSpecNormalizer;
import com.llmgateway.service.ParamOverrideSupport;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.RouteResolver;
import com.llmgateway.service.SpecRequestMapping;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.service.UsageExtractor;
import com.llmgateway.vendor.route.ChatImageVendorAdapter;
import com.llmgateway.vendor.route.RouteVendorRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 路由核心编排器 v2。
 *
 * <p>一次请求的选路：{@link RouteResolver} 按「路由 priority → 渠道 priority → 同层权重随机」
 * 给出候选；每个候选应用「上游模型名 + 参数覆盖」后发给上游；429/5xx/超时换候选（可重试），
 * 业务 4xx 直接透传；全部失败后按 {@code model_fallback} 跨模型回退（最多 2 层）。
 *
 * <p>Key 选择：优先渠道 Key 池（加权 + 失败冷却），池为空回退 channel.api_key。
 * 协议差异仍在 {@link ChatImageVendorAdapter}，本类只做编排。
 */
@Service
@RequiredArgsConstructor
public class GatewayRoutingServiceImpl implements GatewayRoutingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutingServiceImpl.class);
    private static final int MAX_FALLBACK_DEPTH = 2;

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final ModelParamsMapper modelParamsMapper;
    private final KeyService keyService;
    private final UpstreamClient upstreamClient;
    private final RouteVendorRegistry routeRegistry;
    private final CallLogService callLogService;
    private final ModelCatalogCache catalogCache;
    private final RouteResolver routeResolver;
    private final ChannelKeyService channelKeyService;
    private final ModelFallbackMapper modelFallbackMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================================================================
    // 流式
    // ==================================================================

    @Override
    public void streamChat(String path, String requestBody, java.util.function.Consumer<byte[]> sink) throws Exception {
        streamChat(path, requestBody, sink, null);
    }

    @Override
    public void streamChat(String path, String requestBody,
                           java.util.function.Consumer<byte[]> sink, String callId) throws Exception {
        streamInternal(path, requestBody, sink, callId, null, 0, new LinkedHashSet<>());
    }

    private void streamInternal(String path, String requestBody,
                                java.util.function.Consumer<byte[]> sink, String callId,
                                String forcedChannelId, int depth, Set<String> visited) throws Exception {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            String modelName = body.path("model").asText("");
            model = modelName;
            if (modelName.isBlank()) throw new BusinessException(40001, "model 不能为空");
            if (!visited.add(modelName)) {
                throw new BusinessException(40001, "fallback loop detected: " + modelName);
            }

            List<RouteResolver.RouteCandidate> candidates = routeResolver.resolve(modelName, forcedChannelId);
            if (candidates.isEmpty()) {
                if (forcedChannelId == null && tryStreamFallback(path, requestBody, modelName,
                        resolveType(modelName), sink, callId, depth, visited)) {
                    return;
                }
                throw new BusinessException(40401, "no route for model: " + modelName);
            }

            int attempt = 0;
            String lastError = null;
            Map<String, String> specCache = new HashMap<>();
            for (RouteResolver.RouteCandidate candidate : candidates) {
                int maxAttempts = candidate.maxAttempts();
                for (int i = 0; i < maxAttempts; i++) {
                    attempt++;
                    Channel channel = candidate.channel();
                    channelId = channel.getId();
                    ChannelKeyService.KeySelection key = channelKeyService.select(channel);
                    String mapped = SpecRequestMapping.apply(requestBody, specFor(candidate, specCache),
                            "openai_compatible".equals(channel.getType()));
                    String forwarded = ParamOverrideSupport.apply(
                            mapped, candidate.route().getParamOverrides(), candidate.upstreamModel(),
                            "openai_compatible".equals(channel.getType()));
                    AtomicBoolean emitted = new AtomicBoolean(false);
                    try {
                        ChatImageVendorAdapter adapter = routeRegistry.resolve(channel.getType());
                        adapter.stream(path, forwarded, channel, key.plainKey(), bytes -> {
                            if (bytes != null && bytes.length > 0) emitted.set(true);
                            sink.accept(bytes);
                        }, candidate.timeoutMs());
                        long duration = System.currentTimeMillis() - start;
                        callLogService.log(model, channelId, "success", duration, null, null, null, callId,
                                candidate.upstreamModel(), attempt, null, null);
                        channelKeyService.markSuccess(key.keyId());
                        return;
                    } catch (Exception e) {
                        if (isClientAbort(e)) {
                            callLogService.log(model, channelId, "error",
                                    System.currentTimeMillis() - start, "client aborted", null, null, callId,
                                    candidate.upstreamModel(), attempt, null, null);
                            log.debug("客户端中断流式转发，停止: channel={}, callId={}", channelId, callId);
                            return;
                        }
                        lastError = e.getMessage();
                        callLogService.log(model, channelId, "error",
                                System.currentTimeMillis() - start, lastError, null, null, callId,
                                candidate.upstreamModel(), attempt, null, null);
                        channelKeyService.markFailure(key.keyId(), 500);
                        if (emitted.get()) {
                            throw e;   // 已经吐字：无法安全重放，直接失败
                        }
                        log.warn("流式渠道失败，尝试下一个: channel={}, callId={}, error={}",
                                channelId, callId, lastError);
                    }
                }
            }

            if (forcedChannelId == null && tryStreamFallback(path, requestBody, modelName,
                    resolveType(modelName, candidates), sink, callId, depth, visited)) {
                return;
            }
            throw new BusinessException(50301, "all channels failed for model: " + model
                    + (lastError == null ? "" : " (" + lastError + ")"));
        } catch (BusinessException be) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                    be.getMessage(), null, null, callId, null, null, null, null);
            throw be;
        } catch (Exception e) {
            callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                    e.getMessage(), null, null, callId, null, null, null, null);
            throw e;
        }
    }

    /** 流式回退：仅在尚未向上游写出任何字节时调用 */
    private boolean tryStreamFallback(String path, String requestBody, String model, String type,
                                      java.util.function.Consumer<byte[]> sink, String callId,
                                      int depth, Set<String> visited) {
        if (depth >= MAX_FALLBACK_DEPTH) return false;
        for (String fallback : fallbackModels(model, type)) {
            if (visited.contains(fallback)) continue;
            try {
                ObjectNode body = (ObjectNode) objectMapper.readTree(requestBody);
                body.put("model", fallback);
                log.info("模型 {} 全部渠道失败，流式回退到 {}", model, fallback);
                streamInternal(path, objectMapper.writeValueAsString(body), sink, callId, null, depth + 1, visited);
                return true;
            } catch (Exception e) {
                log.warn("流式回退 {} 失败: {}", fallback, e.getMessage());
            }
        }
        return false;
    }

    // ==================================================================
    // 同步
    // ==================================================================

    @Override
    public RouteResult route(String path, String requestBody) {
        return route(path, requestBody, null, null);
    }

    @Override
    public RouteResult route(String path, String requestBody, String callId) {
        return route(path, requestBody, callId, null);
    }

    @Override
    public RouteResult route(String path, String requestBody, String callId, String forcedChannelId) {
        return routeInternal(path, requestBody, callId, forcedChannelId, 0, new LinkedHashSet<>());
    }

    private RouteResult routeInternal(String path, String requestBody, String callId,
                                      String forcedChannelId, int depth, Set<String> visited) {
        long start = System.currentTimeMillis();
        String model = null;
        String channelId = null;
        boolean anyAttempt = false;
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            String modelName = body.path("model").asText("");
            model = modelName;
            if (modelName.isBlank()) throw new BusinessException(40001, "model 不能为空");
            if (!visited.add(modelName)) {
                throw new BusinessException(40001, "fallback loop detected: " + modelName);
            }

            List<RouteResolver.RouteCandidate> candidates = routeResolver.resolve(modelName, forcedChannelId);
            if (candidates.isEmpty()) {
                if (forcedChannelId == null) {
                    RouteResult fallback = tryFallback(path, requestBody, modelName,
                            resolveType(modelName), callId, depth, visited);
                    if (fallback != null) return fallback;
                }
                throw new BusinessException(40401, "no route for model: " + modelName);
            }

            int attempt = 0;
            String lastError = null;
            Map<String, String> specCache = new HashMap<>();
            for (RouteResolver.RouteCandidate candidate : candidates) {
                int maxAttempts = candidate.maxAttempts();
                for (int i = 0; i < maxAttempts; i++) {
                    attempt++;
                    anyAttempt = true;
                    Channel channel = candidate.channel();
                    channelId = channel.getId();
                    ChannelKeyService.KeySelection key = channelKeyService.select(channel);
                    String mapped = SpecRequestMapping.apply(requestBody, specFor(candidate, specCache),
                            "openai_compatible".equals(channel.getType()));
                    String forwarded = ParamOverrideSupport.apply(
                            mapped, candidate.route().getParamOverrides(), candidate.upstreamModel(),
                            "openai_compatible".equals(channel.getType()));
                    try {
                        RouteResult fwd = forward(channel, path, forwarded, key.plainKey(), candidate.timeoutMs());
                        int status = fwd.status();
                        if (status >= 200 && status < 300) {
                            fwd = normalizeImageResponse(path, channel, candidate, fwd);
                            UsageExtractor.Usage usage = UsageExtractor.fromBody(fwd.body());
                            callLogService.log(model, channelId, "success",
                                    System.currentTimeMillis() - start, null, null, null, callId,
                                    candidate.upstreamModel(), attempt,
                                    usage == null ? null : usage.promptTokens(),
                                    usage == null ? null : usage.completionTokens());
                            channelKeyService.markSuccess(key.keyId());
                            return fwd;
                        }
                        String error = upstreamClient.extractError(fwd.body());
                        lastError = error;
                        callLogService.log(model, channelId, "error",
                                System.currentTimeMillis() - start, error, null, null, callId,
                                candidate.upstreamModel(), attempt, null, null);
                        if (shouldCoolKey(status)) channelKeyService.markFailure(key.keyId(), status);
                        log.warn("渠道 {} 返回 {}: {}", channel.getName(), status, error);                        // 429/5xx 继续换渠道/重试；其余 4xx 是业务错误，直接透传
                        if (status != 429 && status < 500) {
                            return fwd;
                        }
                    } catch (BusinessException be) {
                        throw be;
                    } catch (Exception e) {
                        lastError = e.getMessage();
                        anyAttempt = true;
                        callLogService.log(model, channelId, "error",
                                System.currentTimeMillis() - start, lastError, null, null, callId,
                                candidate.upstreamModel(), attempt, null, null);
                        channelKeyService.markFailure(key.keyId(), 500);
                        log.warn("渠道 {} 调用异常: {}", channel.getName(), lastError);
                    }
                }
            }

            if (forcedChannelId == null) {
                RouteResult fallback = tryFallback(path, requestBody, modelName,
                        resolveType(modelName, candidates), callId, depth, visited);
                if (fallback != null) return fallback;
            }
            throw new BusinessException(50301, "all channels failed for model: " + model
                    + (lastError == null ? "" : " (" + lastError + ")"));
        } catch (BusinessException be) {
            if (!anyAttempt) {
                callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                        be.getMessage(), null, null, callId, null, null, null, null);
            }
            throw be;
        } catch (Exception e) {
            if (!anyAttempt) {
                callLogService.log(model, channelId, "error", System.currentTimeMillis() - start,
                        e.getMessage(), null, null, callId, null, null, null, null);
            }
            throw new BusinessException(50001, e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    private RouteResult tryFallback(String path, String requestBody, String model, String type,
                                    String callId, int depth, Set<String> visited) {
        if (depth >= MAX_FALLBACK_DEPTH) return null;
        for (String fallback : fallbackModels(model, type)) {
            if (visited.contains(fallback)) continue;
            try {
                ObjectNode body = (ObjectNode) objectMapper.readTree(requestBody);
                body.put("model", fallback);
                log.info("模型 {} 全部渠道失败，回退到 {}", model, fallback);
                return routeInternal(path, objectMapper.writeValueAsString(body), callId, null, depth + 1, visited);
            } catch (Exception e) {
                log.warn("回退模型 {} 调用失败: {}", fallback, e.getMessage());
            }
        }
        return null;
    }

    private List<String> fallbackModels(String model, String type) {
        try {
            return modelFallbackMapper.selectList(new LambdaQueryWrapper<ModelFallback>()
                            .eq(ModelFallback::getModelName, model)
                            .eq(ModelFallback::getType, type == null || type.isBlank() ? "text" : type)
                            .orderByAsc(ModelFallback::getSortOrder))
                    .stream().map(ModelFallback::getFallbackModel)
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("读取回退链失败: model={}, error={}", model, e.getMessage());
            return List.of();
        }
    }

    private RouteResult forward(Channel channel, String path, String body, String apiKey, Integer timeoutMs)
            throws Exception {
        ChatImageVendorAdapter adapter = routeRegistry.resolve(channel.getType());
        return adapter.forward(path, body, channel, apiKey, timeoutMs);
    }

    /**
     * 按 (模型, 类型) 取一次 Spec，供本次请求做值映射；同类型只查一次库。
     * 空字符串代表「没有 Spec」，避免每次尝试都回落查库。
     */
    private String specFor(RouteResolver.RouteCandidate candidate, Map<String, String> cache) {
        String type = candidate.type();
        if (cache.containsKey(type)) return cache.get(type);
        String schema = null;
        try {
            ModelParams mp = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                    .eq(ModelParams::getModelName, candidate.modelName())
                    .eq(ModelParams::getType, type)
                    .last("LIMIT 1"));
            schema = mp == null ? null : mp.getParamsSchema();
        } catch (Exception e) {
            log.warn("读取模型 Spec 失败，跳过值映射: model={}, type={}, error={}",
                    candidate.modelName(), type, e.getMessage());
        }
        cache.put(type, schema == null ? "" : schema);
        return schema;
    }

    /** 图片同步响应归一化（仅 openai_compatible 且配置了 response.sync 时） */
    private RouteResult normalizeImageResponse(String path, Channel channel,
                                               RouteResolver.RouteCandidate candidate, RouteResult fwd) {
        if (!path.contains("images/generations") || "gemini".equals(channel.getType())) return fwd;
        try {
            ModelParams mp = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                    .eq(ModelParams::getModelName, candidate.modelName())
                    .eq(ModelParams::getType, candidate.type())
                    .last("LIMIT 1"));
            JsonNode spec = mp == null ? null : ModelParamsSpecNormalizer.spec(mp.getParamsSchema());
            if (spec != null && spec.path("response").path("sync").isObject()) {
                return new RouteResult(200, ModelParamsSpecNormalizer.normalizeImageSync(fwd.body(), spec));
            }
        } catch (Exception e) {
            log.warn("图片响应归一化失败（按原样透传）: {}", e.getMessage());
        }
        return fwd;
    }

    private String resolveType(String model) {
        try {
            List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                    .eq(ModelRoute::getModelName, model));
            for (ModelRoute route : routes) {
                if (route.getType() != null && !route.getType().isBlank()) return route.getType();
            }
            ModelParams params = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                    .eq(ModelParams::getModelName, model).last("LIMIT 1"));
            return params == null ? "text" : params.getType();
        } catch (Exception e) {
            return "text";
        }
    }

    private String resolveType(String model, List<RouteResolver.RouteCandidate> candidates) {
        if (candidates != null && !candidates.isEmpty()) return candidates.get(0).type();
        return resolveType(model);
    }

    /** 只有限流/鉴权/服务端错误才让 Key 冷却；内容审核等业务 4xx 不该拖累 Key */
    private static boolean shouldCoolKey(int status) {
        return status == 429 || status == 401 || status == 403 || status >= 500;
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

    // ==================================================================
    // 模型目录（保持原有行为）
    // ==================================================================

    @Override
    public String fetchModels(String type) {
        String cached = catalogCache.readModels(type);
        if (cached != null) return cached;
        List<ModelRoute> routes = routeMapper.selectList(null);
        Set<String> enabledChannels = channelMapper.selectList(null).stream()
                .filter(c -> c.getEnabled() == null || c.getEnabled())
                .map(Channel::getId)
                .collect(Collectors.toSet());
        Map<String, String> modelTypeMap = new LinkedHashMap<>();
        for (ModelRoute r : routes) {
            if (Boolean.FALSE.equals(r.getEnabled())) continue;
            if (r.getChannelId() == null || !enabledChannels.contains(r.getChannelId())) continue;
            String t = r.getType() == null || r.getType().isBlank() ? "text" : r.getType();
            if (type != null && !type.isBlank() && !type.equals(t)) continue;
            modelTypeMap.putIfAbsent(r.getModelName(), t);
        }
        Map<String, String> defaultByType = resolveDefaultByType(routes, enabledChannels, modelTypeMap);
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

    private Map<String, String> resolveDefaultByType(List<ModelRoute> routes,
                                                     Set<String> enabledChannels,
                                                     Map<String, String> modelTypeMap) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (ModelRoute route : routes) {
            if (Boolean.FALSE.equals(route.getEnabled())) continue;
            if (route.getChannelId() == null || !enabledChannels.contains(route.getChannelId())) continue;
            if (!Boolean.TRUE.equals(route.getIsDefault())) continue;
            String routeType = route.getType() == null || route.getType().isBlank()
                    ? "text" : route.getType();
            if (!routeType.equals(modelTypeMap.get(route.getModelName()))) continue;
            defaults.putIfAbsent(routeType, route.getModelName());
        }
        for (String modelType : new LinkedHashSet<>(modelTypeMap.values())) {
            String effective = catalogCache.readDefault(modelType);
            if (effective != null && modelType.equals(modelTypeMap.get(effective))) {
                defaults.put(modelType, effective);
            }
        }
        return defaults;
    }

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
        if (mp.getOllamaParams() != null && !mp.getOllamaParams().isBlank()) {
            try {
                JsonNode o = objectMapper.readTree(mp.getOllamaParams());
                if (o.isObject() && !o.isEmpty()) params.put("ollama", o);
            } catch (Exception e) {
                log.warn("解析 ollama_params 失败，忽略: model={}, error={}", mp.getModelName(), e.getMessage());
            }
        }
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
                .toList();
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
