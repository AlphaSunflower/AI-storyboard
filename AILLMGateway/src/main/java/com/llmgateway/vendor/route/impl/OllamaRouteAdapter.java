package com.llmgateway.vendor.route.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.service.OllamaFormatConverter;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.vendor.route.ChatImageVendorAdapter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ollama 兼容协议适配器（channel.type = "ollama"）。
 * <p>
 * OpenAI chat/completions → Ollama /api/chat（文本/视觉、思考/等级、参考映射、流式 NDJSON→SSE）。
 * 生图/视频/图改图：Ollama 不支持 → 明确 400。
 * 兜底默认（temperature/max_tokens/top_p + ollama_params 思考/num_ctx 等）从 model_params 读取，
 * 客户端显式值优先。编排器（GatewayRoutingServiceImpl）零改动，由 RouteVendorRegistry 自动注册。
 */
@Component
@RequiredArgsConstructor
public class OllamaRouteAdapter implements ChatImageVendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(OllamaRouteAdapter.class);

    private final UpstreamClient upstreamClient;
    private final OllamaFormatConverter converter;
    private final ModelParamsMapper modelParamsMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String channelType() {
        return "ollama";
    }

    @Override
    public RouteResult forward(String path, String requestBody, Channel channel, String apiKey) throws Exception {
        return forward(path, requestBody, channel, apiKey, null);
    }

    @Override
    public RouteResult forward(String path, String requestBody, Channel channel, String apiKey,
                               Integer timeoutMs) throws Exception {
        if (path.contains("images/")) {
            return unsupported(path);
        }
        JsonNode body = objectMapper.readTree(requestBody);
        String model = body.path("model").asText("");
        String type = resolveType(requestBody);
        String ollamaBody = converter.toOllamaChatRequest(requestBody, loadDefaults(model, type), imageResolver());
        HttpResponse<String> resp = timeoutMs == null
                ? upstreamClient.postJson(channel.getBaseUrl(), "/chat", apiKey, ollamaBody)
                : upstreamClient.postJson(channel.getBaseUrl(), "/chat", apiKey, ollamaBody, timeoutMs);
        if (resp.statusCode() == 200) {
            return new RouteResult(200, converter.toOpenAiChatResponse(resp.body(), model));
        }
        return new RouteResult(resp.statusCode(), resp.body());
    }

    @Override
    public void stream(String path, String requestBody, Channel channel, String apiKey,
                       Consumer<byte[]> sink) throws Exception {
        stream(path, requestBody, channel, apiKey, sink, null);
    }

    @Override
    public void stream(String path, String requestBody, Channel channel, String apiKey,
                       Consumer<byte[]> sink, Integer timeoutMs) throws Exception {
        if (path.contains("images/")) {
            throw new BusinessException(40001, "Ollama 不支持该端点: " + path);
        }
        JsonNode body = objectMapper.readTree(requestBody);
        String model = body.path("model").asText("");
        String type = resolveType(requestBody);
        String ollamaBody = converter.toOllamaChatRequest(requestBody, loadDefaults(model, type), imageResolver());
        HttpResponse<InputStream> resp = timeoutMs == null
                ? upstreamClient.postJsonStream(channel.getBaseUrl(), "/chat", apiKey, ollamaBody)
                : upstreamClient.postJsonStream(channel.getBaseUrl(), "/chat", apiKey, ollamaBody, timeoutMs);
        if (resp.statusCode() != 200) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("upstream " + resp.statusCode() + ": " + err);
        }
        String id = converter.newCompletionId();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = objectMapper.readTree(line);
                sink.accept(("data: " + converter.toChunkJson(n, id, model) + "\n\n").getBytes(StandardCharsets.UTF_8));
                if (n.path("done").asBoolean(false)) break;
            }
            sink.accept("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Ollama 不支持生图/图改图：明确 400（4xx 由路由层直接透传，不触发渠道轮换） */
    private RouteResult unsupported(String path) {
        return new RouteResult(400, "{\"error\":{\"message\":\"Ollama 不支持该端点: " + path + "\"}}");
    }

    /** 按 (model, type) 读取 model_params 作为兜底默认：text 默认值 + ollama_params 扩展字段；type 用于区分 text/vision 角色 */
    private Map<String, Object> loadDefaults(String model, String type) {
        Map<String, Object> def = new LinkedHashMap<>();
        if (model == null || model.isBlank()) return def;
        try {
            ModelParams mp = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                    .eq(ModelParams::getModelName, model)
                    .eq(ModelParams::getType, type == null ? "text" : type));
            if (mp == null) return def;
            if (mp.getTemperature() != null && !mp.getTemperature().isBlank()) {
                def.put("temperature", Double.parseDouble(mp.getTemperature()));
            }
            if (mp.getMaxTokens() != null) def.put("max_tokens", mp.getMaxTokens());
            if (mp.getTopP() != null && !mp.getTopP().isBlank()) {
                def.put("top_p", Double.parseDouble(mp.getTopP()));
            }
            if (mp.getOllamaParams() != null && !mp.getOllamaParams().isBlank()) {
                JsonNode o = objectMapper.readTree(mp.getOllamaParams());
                // 只读取非 null 字段：NullNode 的 asText()/asInt() 会返回 "null"/0，不能传给 Ollama
                if (o.hasNonNull("think")) {
                    def.put("think", o.get("think").isBoolean() ? o.get("think").asBoolean() : o.get("think").asText());
                }
                if (o.hasNonNull("think_level")) def.put("think_level", o.get("think_level").asText());
                if (o.hasNonNull("num_ctx")) def.put("num_ctx", o.get("num_ctx").asInt());
                if (o.hasNonNull("seed")) def.put("seed", o.get("seed").asInt());
                if (o.hasNonNull("stop")) def.put("stop", toList(o.get("stop")));
                if (o.hasNonNull("keep_alive")) def.put("keep_alive", o.get("keep_alive").asText());
                if (o.hasNonNull("format")) def.put("format", o.get("format").asText());
            }
        } catch (Exception e) {
            log.warn("读取 model_params 兜底默认失败（忽略，用上游默认）: model={}, error={}", model, e.getMessage());
        }
        return def;
    }

    /** 请求含图片（image_url）视为 vision/理解角色，否则 text——用于取对应 (model,type) 的参数 */
    private String resolveType(String requestBody) {
        try {
            JsonNode body = objectMapper.readTree(requestBody);
            JsonNode messages = body.path("messages");
            if (messages.isArray()) {
                for (JsonNode m : messages) {
                    JsonNode content = m.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode part : content) {
                            if ("image_url".equals(part.path("type").asText(""))) return "vision";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析请求类型失败按 text 处理：{}", e.getMessage());
        }
        return "text";
    }

    /** image_url → base64：data: 去前缀 / http(s) 下载；其它抛错 */
    private OllamaFormatConverter.ImageResolver imageResolver() {
        return url -> {
            if (url == null || url.isBlank()) throw new BusinessException(40001, "image_url 不能为空");
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma < 0) throw new BusinessException(40001, "data URL 缺少 base64 内容");
                return url.substring(comma + 1);
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return Base64.getEncoder().encodeToString(upstreamClient.getBytes(url));
            }
            throw new BusinessException(40001, "不支持的图片地址: " + url);
        };
    }

    private List<String> toList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) list.add(n.asText());
        } else if (node.isTextual() && !node.asText().isBlank()) {
            list.add(node.asText());
        }
        return list;
    }
}
