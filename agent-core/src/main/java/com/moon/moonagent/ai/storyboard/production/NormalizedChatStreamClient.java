package com.moon.moonagent.ai.storyboard.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moon.moonagent.ai.AiConfigProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Reads the gateway's normalized NDJSON stream and preserves both content and
 * provider reasoning deltas that Spring AI drops from streamed chunks.
 */
@Component
public class NormalizedChatStreamClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiConfigProperties config;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public NormalizedChatStreamClient(AiConfigProperties config) {
        this.config = config;
    }

    public StreamResult stream(String model,
                               String system,
                               String user,
                               int timeoutSeconds,
                               Consumer<String> onContent,
                               Consumer<String> onReasoning)
            throws IOException, InterruptedException {
        String baseUrl = gatewayBaseUrl();
        URI uri = URI.create(baseUrl + "/v1/chat/completions/stream-events");
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model == null ? "" : model);
        body.put("stream", true);
        ArrayNode messages = body.putArray("messages");
        messages.add(message("system", system));
        messages.add(message("user", user));

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        String apiKey = trim(config.getGatewayApiKey());
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);

        HttpResponse<InputStream> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("normalized stream failed (" + response.statusCode() + "): " + detail);
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        boolean completed = false;
        String finishReason = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("normalized stream interrupted");
                }
                if (line.isBlank()) continue;
                JsonNode event = JSON.readTree(line);
                String type = event.path("type").asText("");
                if ("reasoning_delta".equals(type)) {
                    String delta = event.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        reasoning.append(delta);
                        if (onReasoning != null) onReasoning.accept(delta);
                    }
                } else if ("content_delta".equals(type)) {
                    String delta = event.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        content.append(delta);
                        if (onContent != null) onContent.accept(delta);
                    }
                } else if ("done".equals(type)) {
                    completed = true;
                    finishReason = event.path("finishReason").asText("stop");
                    break;
                } else if ("error".equals(type)) {
                    throw new IOException(event.path("message").asText("normalized stream failed"));
                }
            }
        }
        return new StreamResult(content.toString(), reasoning.toString(), completed, finishReason);
    }

    private String gatewayBaseUrl() {
        String base = trim(config.getGatewayBaseUrl());
        if (base.isBlank()) base = "http://localhost:8083";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return base;
    }

    private static ObjectNode message(String role, String content) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record StreamResult(
            String content,
            String reasoning,
            boolean completed,
            String finishReason
    ) {
    }
}
