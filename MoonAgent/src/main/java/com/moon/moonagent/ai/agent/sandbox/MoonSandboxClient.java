package com.moon.moonagent.ai.agent.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class MoonSandboxClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MoonSandboxClientProperties properties;
    private final HttpClient http;

    public MoonSandboxClient(MoonSandboxClientProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public SandboxWriteResult write(String conversationId, String path,
                                    String content, String mode) {
        return post(conversationId, "/files/write",
                Map.of("path", path, "content", content == null ? "" : content,
                        "mode", mode == null ? "OVERWRITE" : mode),
                SandboxWriteResult.class);
    }

    public SandboxReadResult read(String conversationId, String path,
                                  int offset, int maxChars) {
        return post(conversationId, "/files/read",
                Map.of("path", path, "offset", offset, "maxChars", maxChars),
                SandboxReadResult.class);
    }

    public List<SandboxFileEntry> list(String conversationId, String path) {
        String uri = base(conversationId) + "/files?path=" + urlEncode(path == null ? "" : path);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(uri))
                .timeout(properties.getReadTimeout())
                .header("X-Internal-Secret", properties.getInternalSecret())
                .GET().build());
        requireSuccess(response);
        try {
            return JSON.readValue(response.body(), new TypeReference<>() {});
        } catch (Exception error) {
            throw new MoonSandboxException("Unable to parse sandbox file list", error);
        }
    }

    public SandboxExecutionResult execute(String conversationId, String script,
                                          List<String> args, int timeoutSeconds) {
        return post(conversationId, "/execute",
                Map.of("script", script == null ? "" : script,
                        "args", args == null ? List.of() : args,
                        "timeoutSeconds", timeoutSeconds),
                SandboxExecutionResult.class);
    }

    public byte[] download(String conversationId, String path) {
        String uri = base(conversationId) + "/files/download?path=" + urlEncode(path);
        HttpResponse<byte[]> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(uri))
                    .timeout(properties.getReadTimeout())
                    .header("X-Internal-Secret", properties.getInternalSecret())
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception error) {
            throw new MoonSandboxException("Sandbox download failed", error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MoonSandboxException(
                    "Sandbox download failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    public void cleanup(String conversationId) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base(conversationId)))
                .timeout(properties.getReadTimeout())
                .header("X-Internal-Secret", properties.getInternalSecret())
                .DELETE().build());
        requireSuccess(response);
    }

    private <T> T post(String conversationId, String suffix, Object body, Class<T> type) {
        try {
            String json = JSON.writeValueAsString(body);
            HttpResponse<String> response = send(HttpRequest.newBuilder(
                            URI.create(base(conversationId) + suffix))
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", properties.getInternalSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build());
            requireSuccess(response);
            return JSON.readValue(response.body(), type);
        } catch (MoonSandboxException error) {
            throw error;
        } catch (Exception error) {
            throw new MoonSandboxException("Sandbox request failed", error);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new MoonSandboxException("Sandbox is unavailable", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new MoonSandboxException("Sandbox request interrupted", error);
        }
    }

    private static void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MoonSandboxException(
                    "Sandbox returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private String base(String conversationId) {
        return properties.getBaseUrl() + "/internal/sandbox/" + conversationId;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
