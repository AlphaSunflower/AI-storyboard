package com.storyboard.asset.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AssetClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsIdempotencyAndCorrelationHeadersForMutations() throws IOException {
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> correlation = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/library/assets", exchange -> {
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            respond(exchange, 200, """
                    {"code":0,"message":"ok","data":{"id":"a1","name":"demo","type":"IMAGE","scope":"PROJECT","status":"ACTIVE","version":1,"tags":[]}}
                    """);
        });
        server.start();

        AssetClient client = AssetClient.create(new AssetClientProperties(
                "http://localhost:" + server.getAddress().getPort(), "secret", 30, 10));
        client.createAsset(
                new AssetContracts.CreateAssetRequest("u1", "p1", "IMAGE", null, "demo", null, "upload"),
                "idem-1",
                "corr-1");

        assertThat(idempotency.get()).isEqualTo("idem-1");
        assertThat(correlation.get()).isEqualTo("corr-1");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
