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
 * 网关不可达时回退硬编码兜底常量（与前端静态兜底列表同义，仅离线引导用）。
 */
@Service
@RequiredArgsConstructor
public class GatewayModelServiceImpl implements GatewayModelService {

    /** 网关不可达时的兜底默认模型（每类型一个；权威源在网关 model_params.is_default） */
    private static final Map<String, String> FALLBACK_DEFAULTS = Map.of(
            "image", "gpt-image-2",
            "video", "MiniMax-H3",
            "vision", "gemini-3-flash-preview");

    private final AiConfigProperties config;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 默认模型缓存：type → 默认模型名（启动预热 + 首次使用时惰性回填） */
    private final Map<String, String> defaultCache = new ConcurrentHashMap<>();
    /** 网关不可达冷却：到期前不再尝试 HTTP 拉取，直接走兜底 */
    private volatile long gatewayUnavailableUntil = 0;

    @Override
    public List<Map<String, String>> fetchModels(String type) {
        List<Map<String, String>> models = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/models?type=" + type))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(10))
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
        for (String type : FALLBACK_DEFAULTS.keySet()) {
            String d = fetchDefaultModel(type);
            if (d != null) defaultCache.put(type, d);
        }
        // text 类型单独预热（兜底值从配置文件读取，不在 FALLBACK_DEFAULTS 中）
        String textDefault = fetchDefaultModel("text");
        if (textDefault != null) defaultCache.put("text", textDefault);
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

    /** 缓存优先取默认模型：computeIfAbsent 保证并发单线程回填（防踩踏） */
    private String getDefaultModel(String type) {
        return defaultCache.computeIfAbsent(type, t -> {
            // 网关冷却期内直接走兜底
            if (System.currentTimeMillis() < gatewayUnavailableUntil) {
                return fallbackFor(t);
            }
            String fetched = fetchDefaultModel(t);
            if (fetched != null) return fetched;
            // 网关不可达：标记冷却 5 分钟
            gatewayUnavailableUntil = System.currentTimeMillis() + 300_000;
            return fallbackFor(t);
        });
    }

    private String fallbackFor(String type) {
        if ("text".equals(type)) return config.getGateway().getFallbackTextModel();
        return FALLBACK_DEFAULTS.getOrDefault(type, "");
    }

    /** 从网关 /v1/models 列表中找 is_default=true 的模型名（无则 null） */
    private String fetchDefaultModel(String type) {
        for (Map<String, String> m : fetchModels(type)) {
            if ("true".equals(m.get("default"))) return m.get("value");
        }
        return null;
    }
}
