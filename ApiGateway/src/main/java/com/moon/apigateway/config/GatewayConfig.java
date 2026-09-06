package com.moon.apigateway.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 自定义 Gateway 代理超时 — 覆盖默认 ProxyExchange bean。
 * Agent /form/submit SSE 编排可达 2-3 分钟，默认超时太短。
 */
@Configuration
public class GatewayConfig {

    @Bean
    public RestClientProxyExchange proxyExchange(GatewayMvcProperties properties) {
        // 连接超时 5s，socket 读超时 900s（15 分钟）。
        // 覆盖 aisplit 分镜方案编排（素材绑定 + 方案设计 + JSON 转换）最坏 ~9 分钟，
        // 避免网关在读超时窗口内切断 SSE，导致 HITL 卡片只落库、刷新后才可见。
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(900))
                .build();

        var cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(200);
        cm.setDefaultMaxPerRoute(50);

        var httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .build();

        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .build();

        return new RestClientProxyExchange(restClient, properties);
    }
}
