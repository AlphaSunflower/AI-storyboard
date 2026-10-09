package com.moon.moonagent.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 网关模型列表实现：
 * - 默认模型优先从 Redis 读取（网关 admin 切换后实时生效）
 * - Redis 不可用时回退 HTTP 网关 /v1/models，再兜底硬编码常量
 * - 完整模型列表仍走 HTTP（含 params 能力枚举，供前端下拉）
 */
@Service
@RequiredArgsConstructor
public class GatewayModelServiceImpl implements GatewayModelService {

    private static final Logger log = LoggerFactory.getLogger(GatewayModelServiceImpl.class);

    /** Redis key 前缀：与网关 ModelParamsServiceImpl 一致 */
    private static final String REDIS_KEY_PREFIX = "gateway:default_model:";

    /** 上下文窗口缓存 TTL：网关模型列表缓存为 60s，这里取 2 分钟，兼顾实时性与请求量。 */
    private static final long CONTEXT_WINDOW_CACHE_TTL_MS = 120_000L;

    /** 网关不可达时的兜底默认模型（每类型一个） */
    private static final Map<String, String> FALLBACK_DEFAULTS = Map.of(
            "image", "gpt-image-2",
            "video", "MiniMax-H3",
            "vision", "gemini-3-flash-preview");

    private final AiConfigProperties config;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${ai.gateway.base-url:${LLM_GATEWAY_BASE_URL:http://localhost:8083}}")
    private String gatewayBaseUrl;

    /** 模型名 → 上下文窗口 的短 TTL 缓存：modelName → [窗口, 过期时间毫秒] */
    private final Map<String, long[]> contextWindowCache = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, String>> fetchModels(String type) {
        List<Map<String, String>> models = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/models?type=" + type))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(config.getGatewayModelTimeout()))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return models;
            }
            JsonNode data = objectMapper.readTree(resp.body()).path("data");
            if (data.isArray()) {
                for (JsonNode n : data) {
                    String id = n.path("id").asText("");
                    if (!id.isBlank()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("value", id);
                        m.put("label", id);
                        if (n.path("is_default").asBoolean(false)) {
                            m.put("default", "true");
                        }
                        JsonNode paramsNode = n.path("params");
                        if (paramsNode.isObject() && !paramsNode.isEmpty()) {
                            m.put("params", paramsNode.toString());
                        }
                        models.add(m);
                    }
                }
            }
        } catch (Exception e) {
            // 网关不可达/解析失败：返回空列表
        }
        return models;
    }

    @Override
    public String getDefaultImageModel() {
        return getDefaultModel("image");
    }

    @Override
    public String getDefaultVideoModel() {
        return getDefaultModel("video");
    }

    @Override
    public String getDefaultVisionModel() {
        return getDefaultModel("vision");
    }

    @Override
    public String getDefaultTextModel() {
        return getDefaultModel("text");
    }

    @Override
    public Integer resolveContextWindow(String modelName) {
        if (modelName == null || modelName.isBlank()) return null;
        String key = modelName.trim();
        long now = System.currentTimeMillis();
        long[] cached = contextWindowCache.get(key.toLowerCase());
        if (cached != null && cached[1] > now) {
            return cached[0] > 0 ? (int) cached[0] : null;
        }
        Integer resolved = fetchContextWindowFromGateway(key);
        // 命中时缓存正数；未命中缓存 0 作负缓存，避免未知模型每轮重复请求
        contextWindowCache.put(key.toLowerCase(),
                new long[]{resolved == null ? 0L : resolved, now + CONTEXT_WINDOW_CACHE_TTL_MS});
        return resolved;
    }

    /** 直查网关 /v1/models?type=text，匹配模型名取 contextWindow。 */
    private Integer fetchContextWindowFromGateway(String modelName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayBaseUrl + "/v1/models?type=text"))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(config.getGatewayModelTimeout()))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            JsonNode data = objectMapper.readTree(resp.body()).path("data");
            if (data.isArray()) {
                for (JsonNode n : data) {
                    if (!modelName.equals(n.path("id").asText(""))) continue;
                    return n.path("contextWindow").isNumber() ? n.path("contextWindow").asInt() : null;
                }
            }
        } catch (Exception e) {
            log.debug("拉取模型上下文窗口失败: model={}, error={}", modelName, e.getMessage());
        }
        return null;
    }

    /** Redis 优先 → HTTP 兜底 → 硬编码兜底 */
    private String getDefaultModel(String type) {
        // 1) Redis（网关 admin 切换后实时生效）；Redis 未配置/不可达时静默降级 HTTP
        try {
            StringRedisTemplate r = redisTemplate.getIfAvailable();
            String cached = r != null ? r.opsForValue().get(REDIS_KEY_PREFIX + type) : null;
            if (cached != null && !cached.isBlank()) return cached;
        } catch (Exception e) {
            log.debug("Redis 读取默认模型失败: type={}, error={}", type, e.getMessage());
        }
        // 2) HTTP 网关
        String http = fetchDefaultModelFromHttp(type);
        if (http != null) return http;
        // 3) 硬编码兜底
        return fallbackFor(type);
    }

    private String fallbackFor(String type) {
        if ("text".equals(type)) return config.getGateway().getFallbackTextModel();
        return FALLBACK_DEFAULTS.getOrDefault(type, "");
    }

    /** 从网关 HTTP /v1/models 列表中找 is_default=true 的模型名（无则 null） */
    private String fetchDefaultModelFromHttp(String type) {
        try {
            for (Map<String, String> m : fetchModels(type)) {
                if ("true".equals(m.get("default"))) return m.get("value");
            }
        } catch (Exception e) {
            log.debug("HTTP 拉取默认模型失败: type={}, error={}", type, e.getMessage());
        }
        return null;
    }
}
