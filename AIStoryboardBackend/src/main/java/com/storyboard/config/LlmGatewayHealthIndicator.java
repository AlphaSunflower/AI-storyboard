package com.storyboard.config;

import com.storyboard.ai.AiConfigProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM 网关健康检测：GET {gateway}/v1/models（Bearer auth），200=UP，其余/DOWN。
 * 挂在 actuator/health 响应的 llmGateway 组件下。
 */
@Component("llmGateway")
@RequiredArgsConstructor
public class LlmGatewayHealthIndicator implements HealthIndicator {

    private final AiConfigProperties config;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public Health health() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/models"))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return Health.up().withDetail("gateway", config.getGatewayBaseUrl()).build();
            }
            return Health.down().withDetail("statusCode", resp.statusCode()).build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }
}
