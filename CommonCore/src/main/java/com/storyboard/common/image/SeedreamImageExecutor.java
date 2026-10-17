package com.storyboard.common.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Doubao Seedream 图片生成执行器：发送 Ark 原生请求、消费 SSE 并逐张持久化。
 */
public class SeedreamImageExecutor {

    private static final Logger log = LoggerFactory.getLogger(SeedreamImageExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_LOCAL_IMAGE_BYTES = 30L * 1024 * 1024;

    private final ImageStore imageStore;
    private final HttpClient httpClient;

    public SeedreamImageExecutor(ImageStore imageStore) {
        this(imageStore, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    }

    SeedreamImageExecutor(ImageStore imageStore, HttpClient httpClient) {
        this.imageStore = imageStore;
        this.httpClient = httpClient;
    }

    /** 调用 Ark 并返回已持久化图片的相对 URL 列表。 */
    public List<String> execute(Config config, String model, String prompt, String size,
                                Integer requestedCount, List<String> orderedInputImages) {
        if (config == null || config.gatewayBaseUrl() == null || config.gatewayBaseUrl().isBlank()) {
            throw new IllegalArgumentException("网关地址未配置");
        }
        // 5.0 pro/flash 不支持流式输出，请求体不带 stream，响应是一次性 JSON
        boolean streaming = SeedreamImageProtocol.supportsStreaming(model);
        int count = requestedCount != null ? Math.max(0, requestedCount) : 1;
        List<String> normalizedInputs = normalizeInputs(orderedInputImages);
        SeedreamImageProtocol.validateLimits(model, normalizedInputs.size(), count);

        ObjectNode requestBody = SeedreamImageProtocol.buildRequest(model, prompt, normalizedInputs, size, count);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(config.gatewayBaseUrl()) + "/v1/images/generations"))
                .header("Content-Type", "application/json")
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8));
        if (config.gatewayApiKey() != null && !config.gatewayApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.gatewayApiKey());
        }

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String body;
                try (InputStream in = response.body()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new RuntimeException("Ark 返回 " + response.statusCode() + ": " + errorMessage(body));
            }
            return streaming ? consumeSse(response.body()) : consumeSync(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Seedream 图片生成被中断: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Seedream 图片生成失败: " + e.getMessage(), e);
        }
    }

    private List<String> consumeSse(InputStream stream) throws Exception {
        List<String> saved = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean[] completed = {false};

        SeedreamImageProtocol.readEvents(stream, event ->
                applyEvent(event, saved, failures, completed, "Ark 流式错误: "));

        return finish(saved, failures, completed[0]);
    }

    /** 非流式模型：读取一次性返回的 {@code {data:[{url|b64_json}]}} JSON。 */
    private List<String> consumeSync(InputStream stream) throws Exception {
        String body;
        try (InputStream in = stream) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> saved = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean[] completed = {false};

        for (SeedreamImageProtocol.Event event : SeedreamImageProtocol.parseSyncResponse(body)) {
            applyEvent(event, saved, failures, completed, "Ark 图片生成错误: ");
        }

        return finish(saved, failures, completed[0]);
    }

    private void applyEvent(SeedreamImageProtocol.Event event, List<String> saved,
                            List<String> failures, boolean[] completed, String errorPrefix) {
        switch (event.type()) {
            case PARTIAL_SUCCEEDED -> {
                try {
                    String persisted = saveEventImage(event);
                    if (persisted != null && !persisted.isBlank()) saved.add(persisted);
                } catch (Exception e) {
                    failures.add("image_index=" + event.imageIndex() + " 保存失败: " + e.getMessage());
                }
            }
            case PARTIAL_FAILED -> failures.add(formatFailure(event));
            case ERROR -> throw new RuntimeException(errorPrefix + formatFailure(event));
            case COMPLETED -> {
                completed[0] = true;
                log.info("Seedream 生成完成: usage={}", event.usage() == null ? "{}" : event.usage());
            }
            case UNKNOWN -> {
            }
        }
    }

    private List<String> finish(List<String> saved, List<String> failures, boolean completed) {
        if (!completed && !saved.isEmpty()) {
            log.warn("Seedream 响应未收到 completed，保留已成功落库的 {} 张图片", saved.size());
        }
        if (saved.isEmpty()) {
            String detail = failures.isEmpty() ? "未收到成功图片" : String.join("; ", failures);
            throw new RuntimeException("Seedream 图片生成失败: " + detail);
        }
        if (!failures.isEmpty()) {
            log.warn("Seedream 部分图片失败: {}", String.join("; ", failures));
        }
        return saved;
    }

    private String saveEventImage(SeedreamImageProtocol.Event event) {
        if (event.url() != null && !event.url().isBlank()) {
            return imageStore.saveImage(event.url());
        }
        if (event.b64Json() != null && !event.b64Json().isBlank()) {
            return imageStore.saveImageFromBase64(event.b64Json());
        }
        throw new IllegalArgumentException("成功事件缺少 url/b64_json");
    }

    private List<String> normalizeInputs(List<String> orderedInputImages) {
        if (orderedInputImages == null || orderedInputImages.isEmpty()) return List.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : orderedInputImages) {
            if (raw == null || raw.isBlank()) continue;
            String value = raw.trim();
            if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
                normalized.add(value);
                continue;
            }
            byte[] rawBase64 = decodeRawBase64(value);
            if (rawBase64 != null) {
                normalized.add("data:" + detectMimeType(rawBase64) + ";base64," + value);
                continue;
            }
            String filename = extractFilename(value);
            byte[] bytes = imageStore.readImage(filename);
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("参考图不存在: " + filename);
            }
            if (bytes.length > MAX_LOCAL_IMAGE_BYTES) {
                throw new IllegalArgumentException("参考图超过 30MB: " + filename);
            }
            normalized.add("data:" + mimeType(filename) + ";base64," + Base64.getEncoder().encodeToString(bytes));
        }
        return List.copyOf(normalized);
    }

    private static byte[] decodeRawBase64(String value) {
        if (value.length() < 32 || value.length() % 4 != 0
                || !value.matches("[A-Za-z0-9+/]+={0,2}")) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String detectMimeType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) return "image/jpeg";
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "image/webp";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        return "image/png";
    }

    private static String formatFailure(SeedreamImageProtocol.Event event) {
        String code = event.code() == null ? "" : event.code();
        String message = event.message() == null ? "unknown error" : event.message();
        String index = event.imageIndex() >= 0 ? "image_index=" + event.imageIndex() + " " : "";
        return index + (code.isBlank() ? message : code + ": " + message);
    }

    private static String errorMessage(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            String direct = node.path("error").path("message").asText("");
            if (!direct.isBlank()) return direct;
            String nested = node.path("message").asText("");
            if (!nested.isBlank()) return nested;
        } catch (Exception ignored) {
        }
        return body == null || body.isBlank() ? "unknown upstream error" : body;
    }

    private static String extractFilename(String value) {
        String path = value;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String mimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) return "image/tiff";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        return "image/png";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public interface ImageStore {
        String saveImage(String sourceUrl);

        String saveImageFromBase64(String base64Data);

        byte[] readImage(String filename);
    }

    public record Config(String gatewayBaseUrl, String gatewayApiKey, Duration timeout) {
    }
}
