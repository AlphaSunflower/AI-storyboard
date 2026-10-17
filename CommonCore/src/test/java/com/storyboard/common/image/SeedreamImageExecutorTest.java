package com.storyboard.common.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedreamImageExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsArkRequestAndKeepsSuccessfulPartials() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            writeSse(exchange, """
                    data: {"type":"image_generation.partial_succeeded","image_index":0,"url":"https://cdn/0.jpeg","size":"1024x1024"}

                    data: {"type":"image_generation.partial_failed","image_index":1,"error":{"code":"Sensitive","message":"blocked"}}

                    data: {"type":"image_generation.partial_succeeded","image_index":2,"url":"https://cdn/2.jpeg","size":"1024x1024"}

                    data: {"type":"image_generation.completed","usage":{"generated_images":2,"total_tokens":42}}

                    """);
        });
        FakeImageStore store = new FakeImageStore();

        List<String> saved = new SeedreamImageExecutor(store).execute(
                config(), "doubao-seedream-4-0-250828", "融合两张图，生成两张海报",
                "1K", 2, List.of("/api/files/images/ref.png"));

        assertEquals(List.of("/saved/0.jpeg", "/saved/2.jpeg"), saved);
        assertEquals("Bearer gateway-key", authorization.get());
        assertTrue(received.get().path("image").asText().startsWith("data:image/png;base64,"));
        assertEquals(2, received.get().path("sequential_image_generation_options").path("max_images").asInt());
    }

    @Test
    void readsNonStreamingJsonForSingleImageModels() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        startServer(exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            writeJson(exchange, """
                    {"created":1,"model":"doubao-seedream-5-0-flash-260915",
                     "data":[{"url":"https://cdn/0.jpeg","size":"2048x2048"}],
                     "usage":{"generated_images":1}}
                    """);
        });

        List<String> saved = new SeedreamImageExecutor(new FakeImageStore()).execute(
                config(), "doubao-seedream-5-0-flash-260915", "一只橘猫坐在窗边", "4K", 4,
                List.of("/api/files/images/ref.png"));

        assertEquals(List.of("/saved/0.jpeg"), saved);
        assertFalse(received.get().has("stream"));
        assertFalse(received.get().has("sequential_image_generation"));
        assertEquals("2K", received.get().path("size").asText());
    }

    @Test
    void surfacesUpstreamStatusForNonStreamingFailure() throws Exception {
        startServer(exchange -> {
            byte[] bytes = ("""
                    {"error":{"code":"InvalidParameter","message":"the parameter `sequential_image_generation` is not supported"}}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                new SeedreamImageExecutor(new FakeImageStore()).execute(
                        config(), "doubao-seedream-5-0-flash-260915", "p", "2K", 1, List.of()));

        assertTrue(error.getMessage().contains("Ark 返回 400"));
        assertTrue(error.getMessage().contains("not supported"));
    }

    @Test
    void throwsWhenEveryImageFails() throws Exception {
        startServer(exchange -> writeSse(exchange, """
                data: {"type":"image_generation.partial_failed","image_index":0,"error":{"code":"Sensitive","message":"blocked"}}

                data: {"type":"image_generation.completed","usage":{"generated_images":0}}

                """));

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                new SeedreamImageExecutor(new FakeImageStore()).execute(
                        config(), "doubao-seedream-4-0-250828", "p", "1K", 1, List.of()));

        assertTrue(error.getMessage().contains("blocked"));
    }

    @Test
    void throwsTopLevelErrorEvent() throws Exception {
        startServer(exchange -> writeSse(exchange, """
                data: {"error":{"code":"BadRequest","message":"missing prompt"}}

                """));

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                new SeedreamImageExecutor(new FakeImageStore()).execute(
                        config(), "doubao-seedream-4-0-250828", "p", "1K", 1, List.of()));

        assertTrue(error.getMessage().contains("missing prompt"));
    }

    @Test
    void passesRawBase64AsDataUri() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        startServer(exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            writeSse(exchange, """
                    data: {"type":"image_generation.partial_succeeded","image_index":0,"url":"https://cdn/0.jpeg"}

                    data: {"type":"image_generation.completed","usage":{"generated_images":1}}

                    """);
        });
        String rawBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

        new SeedreamImageExecutor(new FakeImageStore()).execute(
                config(), "doubao-seedream-4-0-250828", "p", "1K", 1, List.of(rawBase64));

        assertEquals("data:image/png;base64," + rawBase64, received.get().path("image").asText());
    }

    private SeedreamImageExecutor.Config config() {
        return new SeedreamImageExecutor.Config(
                "http://localhost:" + server.getAddress().getPort(), "gateway-key", Duration.ofSeconds(5));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            }
        });
        server.start();
    }

    private static void writeSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class FakeImageStore implements SeedreamImageExecutor.ImageStore {

        private final List<String> urls = new ArrayList<>();

        @Override
        public String saveImage(String sourceUrl) {
            String saved = "/saved/" + sourceUrl.substring(sourceUrl.lastIndexOf('/') + 1);
            urls.add(saved);
            return saved;
        }

        @Override
        public String saveImageFromBase64(String base64Data) {
            String saved = "/saved/base64-" + (urls.size() + 1) + ".png";
            urls.add(saved);
            return saved;
        }

        @Override
        public byte[] readImage(String filename) {
            return new byte[]{1, 2, 3};
        }
    }
}
