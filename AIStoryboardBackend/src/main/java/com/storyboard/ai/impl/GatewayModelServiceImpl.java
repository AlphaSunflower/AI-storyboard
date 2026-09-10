package com.storyboard.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.ai.AiConfigProperties;
import com.storyboard.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
 * LLM 网关模型列表实现：JDK HttpClient 直连网关 /v1/models?type=X 拉取模型列表，
 * 并缓存「默认模型」（网关 model_params.is_default=true 标记，单一权威源）。
 * 网关不可达时回退配置驱动的兜底模型名（ai.gateway.fallback-*，仅离线引导用）。
 */
@Service
@RequiredArgsConstructor
public class GatewayModelServiceImpl implements GatewayModelService {

    /** 网关不可达时逐个预热的模型类型（各类型兜底模型名由 ai.gateway.fallback-* 配置驱动） */
    private static final List<String> FALLBACK_TYPES = List.of("image", "video", "vision");

    /** 默认模型缓存 TTL：5 分钟后自动刷新，确保网关切换默认模型后能及时生效 */
    private static final long DEFAULT_MODEL_CACHE_TTL_MS = 5 * 60 * 1000L;

    private final AiConfigProperties config;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))  // ponytail: connect timeout 极少调，保常量
            .build();

    /** 默认模型缓存：type → 默认模型名（启动预热 + 首次使用时惰性回填） */
    private final Map<String, String> defaultCache = new ConcurrentHashMap<>();
    /** 缓存时间戳：type → 上次更新时间 */
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    /** 网关不可达冷却：到期前不再尝试 HTTP 拉取，直接走兜底 */
    private volatile long gatewayUnavailableUntil = 0;

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
                        // 默认模型标记（网关 model_params.is_default）
                        if (n.path("is_default").asBoolean(false)) {
                            m.put("default", "true");
                        }
                        // 透传网关下发的模型参数（能力枚举+默认值）：非 null 非空对象时以 JSON 字符串下发，前端 store 解析为对象
                        JsonNode paramsNode = n.path("params");
                        if (paramsNode.isObject() && !paramsNode.isEmpty()) {
                            m.put("params", paramsNode.toString());
                        }
                        models.add(m);
                    }
                }
            }
        } catch (Exception e) {
            // 网关不可达/解析失败：返回空列表，前端用默认模型继续工作
        }
        return models;
    }

    /** 启动预热：提前拉取各类型默认模型进缓存（网关未就绪时静默跳过，首次使用再惰性回填） */
    @EventListener(ApplicationReadyEvent.class)
    public void warmDefaultModels() {
        for (String type : FALLBACK_TYPES) {
            String d = fetchDefaultModel(type);
            if (d != null) {
                defaultCache.put(type, d);
                cacheTimestamps.put(type, System.currentTimeMillis());
            }
        }
        // text 类型单独预热（兜底值从配置文件读取，不在 FALLBACK_DEFAULTS 中）
        String textDefault = fetchDefaultModel("text");
        if (textDefault != null) {
            defaultCache.put("text", textDefault);
            cacheTimestamps.put("text", System.currentTimeMillis());
        }
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

    /**
     * 带 TTL 的默认模型获取：缓存过期后自动从网关刷新。
     * computeIfAbsent 保证并发安全，TTL 检查保证数据新鲜度。
     */
    private String getDefaultModel(String type) {
        long now = System.currentTimeMillis();
        Long timestamp = cacheTimestamps.get(type);
        
        // 缓存存在且未过期，直接返回
        if (timestamp != null && (now - timestamp) < DEFAULT_MODEL_CACHE_TTL_MS) {
            return defaultCache.get(type);
        }
        
        // 缓存不存在或已过期，尝试从网关刷新
        if (now >= gatewayUnavailableUntil) {
            String fetched = fetchDefaultModel(type);
            if (fetched != null) {
                defaultCache.put(type, fetched);
                cacheTimestamps.put(type, now);
                return fetched;
            }
            // 网关不可达：标记冷却
            gatewayUnavailableUntil = now + config.getGatewayCooldownMs();
        }
        
        // 网关不可达或刷新失败，返回缓存值（即使过期）或兜底值
        return defaultCache.getOrDefault(type, fallbackFor(type));
    }

    /** 清除默认模型缓存（供外部调用，如接收到网关通知时） */
    public void clearDefaultCache() {
        defaultCache.clear();
        cacheTimestamps.clear();
        gatewayUnavailableUntil = 0;
    }

    /** 清除指定类型的默认模型缓存 */
    public void clearDefaultCache(String type) {
        defaultCache.remove(type);
        cacheTimestamps.remove(type);
    }

    private String fallbackFor(String type) {
        return switch (type) {
            case "text" -> config.getGateway().getFallbackTextModel();
            case "image" -> config.getGateway().getFallbackImageModel();
            case "video" -> config.getGateway().getFallbackVideoModel();
            case "vision" -> config.getGateway().getFallbackVisionModel();
            default -> "";
        };
    }

    /** 从网关 /v1/models 列表中找 is_default=true 的模型名（无则 null） */
    private String fetchDefaultModel(String type) {
        for (Map<String, String> m : fetchModels(type)) {
            if ("true".equals(m.get("default"))) return m.get("value");
        }
        return null;
    }
}
