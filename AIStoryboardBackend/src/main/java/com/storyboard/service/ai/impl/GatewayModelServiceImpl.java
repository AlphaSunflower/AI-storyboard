package com.storyboard.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
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
 * LLM 网关模型列表实现：JDK HttpClient 直连网关 /v1/models?type=X 拉取模型列表。
 * （原 AIController.fetchGatewayModels 业务逻辑下沉至此，Controller 不再持有 HttpClient。）
 */
@Service
@RequiredArgsConstructor
public class GatewayModelServiceImpl implements GatewayModelService {

    private final AiConfigProperties config;

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
}
