package com.moon.moonagent.ai.agent.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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

    public SandboxShellResult shell(String conversationId, String command, int timeoutSeconds) {
        return post(conversationId, "/shell",
                Map.of("command", command == null ? "" : command,
                        "timeoutSeconds", timeoutSeconds),
                SandboxShellResult.class);
    }

    /**
     * 把镜像里预置的只读技能包按需复制到会话工作区 {@code skills/<skill>}。
     * 幂等；未引用技能的会话不会产生任何技能目录。
     */
    public SandboxSkillResult materializeSkill(String conversationId, String skillName) {
        return post(conversationId,
                "/skills/" + urlEncode(skillName) + "/materialize",
                Map.of(), SandboxSkillResult.class);
    }

    public void upload(String conversationId, String path, byte[] content) {        String uri = base(conversationId) + "/files/upload?path=" + urlEncode(path == null ? "" : path);
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(uri))
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Internal-Secret", properties.getInternalSecret())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            content == null ? new byte[0] : content))
                    .build());
            requireSuccess(response);
        } catch (MoonSandboxException error) {
            throw error;
        } catch (Exception error) {
            throw new MoonSandboxException("Sandbox upload failed", error);
        }
    }

    public byte[] exportArchive(String conversationId) {
        String uri = base(conversationId) + "/archive";
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(uri))
                    .timeout(properties.getReadTimeout())
                    .header("X-Internal-Secret", properties.getInternalSecret())
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MoonSandboxException(
                        "Sandbox archive export failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (MoonSandboxException error) {
            throw error;
        } catch (Exception error) {
            throw new MoonSandboxException("Sandbox archive export failed", error);
        }
    }

    public void importArchive(String conversationId, byte[] archive) {
        String uri = base(conversationId) + "/archive";
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(uri))
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Internal-Secret", properties.getInternalSecret())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            archive == null ? new byte[0] : archive))
                    .build());
            requireSuccess(response);
        } catch (MoonSandboxException error) {
            throw error;
        } catch (Exception error) {
            throw new MoonSandboxException("Sandbox archive import failed", error);
        }
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
