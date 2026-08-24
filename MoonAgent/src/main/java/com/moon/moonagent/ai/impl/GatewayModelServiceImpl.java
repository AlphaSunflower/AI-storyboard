package com.moon.moonagent.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** 网关不可达时的兜底默认模型（每类型一个） */
    private static final Map<String, String> FALLBACK_DEFAULTS = Map.of(
            "image", "gpt-image-2",
            "video", "MiniMax-H3",
            "vision", "gemini-3-flash-preview");

    private final AiConfigProperties config;
    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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

    /** Redis 优先 → HTTP 兜底 → 硬编码兜底 */
    private String getDefaultModel(String type) {
        // 1) Redis（网关 admin 切换后实时生效）
        try {
            String cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + type);
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
