package com.moon.moonagent.config;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class GatewayMcpClientConfig {

    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> gatewayMcpAuthorization(
            @Value("${ai.gateway.base-url:http://localhost:8083}") String gatewayBaseUrl,
            @Value("${ai.gateway.api-key:${LLM_GATEWAY_API_KEY:}}") String gatewayApiKey) {
        URI gateway = URI.create(gatewayBaseUrl);
        return (connectionName, builder) -> builder.httpRequestCustomizer((request, method, uri, body, context) -> {
            if (uri == null || uri.getHost() == null || gateway.getHost() == null) return;
            if (!uri.getHost().equalsIgnoreCase(gateway.getHost())) return;
            if (gateway.getPort() >= 0 && uri.getPort() >= 0 && gateway.getPort() != uri.getPort()) return;
            if (gatewayApiKey != null && !gatewayApiKey.isBlank() && !uri.getPath().startsWith("/mcp")) return;
            if (gatewayApiKey != null && !gatewayApiKey.isBlank()) {
                request.header("Authorization", "Bearer " + gatewayApiKey);
            }
        });
    }
}
