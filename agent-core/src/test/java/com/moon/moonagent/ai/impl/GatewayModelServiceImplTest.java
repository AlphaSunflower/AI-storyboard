package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型列表拉取的回归测试：主后端（AIStoryboardBackend）只显式装配本服务、
 * 不注册 {@code gatewayHttpClient} Bean，字段注入为 null。若直接使用该字段，
 * 每次拉取都会 NPE 并被吞成空列表（模型下拉全空）。这里不加 Spring 容器，
 * 直接验证“没有注入 HttpClient 也能拉到模型”。
 */
class GatewayModelServiceImplTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private GatewayModelServiceImpl service(String baseUrl) {
        AiConfigProperties config = mock(AiConfigProperties.class);
        when(config.getGatewayBaseUrl()).thenReturn(baseUrl);
        when(config.getGatewayApiKey()).thenReturn("lg-test");
        when(config.getGatewayModelTimeout()).thenReturn(10);
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        return new GatewayModelServiceImpl(config, redis);
    }

    private void startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void fetchesModelsWithoutAnInjectedHttpClient() throws Exception {
        startServer(200, """
                {"object":"list","data":[
                  {"id":"gpt-image-2","is_default":true,"params":{"sizes":["1024x1024"]}},
                  {"id":"doubao-seedream-4-0-250828","params":{}}
                ]}""");

        GatewayModelServiceImpl service = service(baseUrl());

        List<Map<String, String>> models = service.fetchModels("image");

        assertThat(models).hasSize(2);
        assertThat(models.getFirst())
                .containsEntry("value", "gpt-image-2")
                .containsEntry("default", "true")
                .containsEntry("params", "{\"sizes\":[\"1024x1024\"]}");
        assertThat(service.lastModelFetchError("image")).isNull();
    }

    @Test
    void recordsTheGatewayRejectionReasonInsteadOfSilentlyReturningEmpty() throws Exception {
        startServer(401, "{\"error\":\"invalid api key\"}");

        GatewayModelServiceImpl service = service(baseUrl());

        assertThat(service.fetchModels("image")).isEmpty();
        assertThat(service.lastModelFetchError("image"))
                .contains("401")
                .contains("invalid api key");
    }

    @Test
    void recordsConnectionFailures() {
        // 未启动的服务端口：连接失败也要留下可读原因，便于界面提示与排障
        GatewayModelServiceImpl service = service("http://localhost:1");

        assertThat(service.fetchModels("vision")).isEmpty();
        assertThat(service.lastModelFetchError("vision")).isNotBlank();
    }
}
