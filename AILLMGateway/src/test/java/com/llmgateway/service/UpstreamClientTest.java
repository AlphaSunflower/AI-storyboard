package com.llmgateway.service;

import com.llmgateway.config.GatewayConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamClientTest {

    @Test
    void postJson_timeoutDoesNotRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch requestStarted = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            requests.incrementAndGet();
            requestStarted.countDown();
            sleep(600);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            UpstreamClient client = new UpstreamClient(config(150, 1));
            assertThatThrownBy(() -> client.postJson(baseUrl(server), "/slow", "test-key", "{}"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("上游请求失败");

            assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void postJson_retriesRetryableStatus(int firstStatus) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/retry", exchange -> {
            int attempt = requests.incrementAndGet();
            byte[] body = ("attempt-" + attempt).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(attempt == 1 ? firstStatus : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            UpstreamClient client = new UpstreamClient(config(2_000, 1));
            var response = client.postJson(baseUrl(server), "/retry", "test-key", "{}");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("attempt-2");
            assertThat(requests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    private static GatewayConfig config(long requestTimeoutMs, int retryCount) {
        GatewayConfig config = new GatewayConfig();
        config.getUpstream().setConnectTimeoutMs(1_000);
        config.getUpstream().setRequestTimeoutMs(requestTimeoutMs);
        config.getUpstream().setRetryCount(retryCount);
        return config;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
