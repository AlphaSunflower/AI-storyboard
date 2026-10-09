package com.moon.moonagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared JDK HttpClients by trust boundary. Per-request timeouts remain operation-specific.
 */
@Configuration
public class OutboundHttpClientConfiguration {

    @Bean("gatewayHttpClient")
    HttpClient gatewayHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean("internalHttpClient")
    HttpClient internalHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Bean("attachmentHttpClient")
    HttpClient attachmentHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
