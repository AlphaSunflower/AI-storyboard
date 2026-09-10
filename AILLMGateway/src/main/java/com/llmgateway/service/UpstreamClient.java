package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** 上游调用客户端：透传请求体、替换 Bearer、轻量重试（429/5xx 重试 2 次） */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    /** 远程图片下载后允许的最大字节数（20MB，防止超大 base64 撑爆请求体） */
    private static final long IMAGE_MAX_BYTES = 20L * 1024 * 1024;

    private final HttpClient httpClient;
    private final GatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UpstreamClient(GatewayConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getUpstream().getConnectTimeoutMs()))
                .build();
    }

    /** POST JSON 到 openai_compatible 渠道（base_url + path，Bearer 渠道 Key） */
    public HttpResponse<String> postJson(String baseUrl, String path, String apiKey, String bodyJson) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
        addAuthorization(builder, apiKey);
        HttpRequest request = builder.build();
        return sendWithRetry(request);
    }

    /**
     * POST JSON 流式（SSE）：上游 stream=true 响应以 InputStream 返回，调用方逐块转发。
     * 超时用流式专用长超时（SSE 长连接，token 间隔可数秒；复用 requestTimeoutMs 会中断长生成）。
     * 不重试——SSE 流一旦建立无法安全重放，重试语义交给调用方（渠道轮换）。
     */
    public HttpResponse<java.io.InputStream> postJsonStream(String baseUrl, String path, String apiKey, String bodyJson) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.getUpstream().getStreamTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
        addAuthorization(builder, apiKey);
        HttpRequest request = builder.build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            log.warn("上游流式请求失败: path={}, error={}", path, e.getMessage());
            throw new RuntimeException("upstream stream request failed: " + e.getMessage(), e);
        }
    }

    /** POST Gemini 原生格式（Key 走 query 参数） */
    public HttpResponse<String> postGemini(String baseUrl, String apiKey, String bodyJson) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
        // Gemini 走 query 参数，不附加 Bearer（Key 由 ?key= 传递）
        HttpRequest request = builder.build();
        return sendWithRetry(request);
    }

    /** POST multipart 到 openai_compatible 渠道（图改图 edits：原样透传 multipart 字节流） */
    public HttpResponse<String> postMultipart(String baseUrl, String path, String apiKey,
                                              String contentType, byte[] bodyBytes) {
        return postMultipart(baseUrl, path, apiKey, contentType, bodyBytes,
                config.getUpstream().getRequestTimeoutMs());
    }

    /** POST multipart（上传大文件用长超时，如 MiniMax 文件上传最大 50 MB） */
    public HttpResponse<String> postMultipart(String baseUrl, String path, String apiKey,
                                              String contentType, byte[] bodyBytes, long timeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", contentType)          // 透传上游 Content-Type（含 boundary）
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        addAuthorization(builder, apiKey);
        HttpRequest request = builder.build();
        return sendWithRetry(request);
    }

    /** GET 上游（轮询视频状态等） */
    public HttpResponse<String> get(String url, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .GET();
        addAuthorization(builder, apiKey);
        HttpRequest request = builder.build();
        return sendWithRetry(request);
    }

    /** 按 http(s) URL 下载字节（Ollama 视觉图：远程 image_url 拉取后 base64）。带超时 + 解码后大小上限。 */
    public byte[] getBytes(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.min(config.getUpstream().getRequestTimeoutMs(), 30_000L)))
                .GET().build();
        try {
            HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("上游 " + resp.statusCode() + ": " + url);
            }
            byte[] body = resp.body();
            if (body.length > IMAGE_MAX_BYTES) {
                throw new RuntimeException("图片过大: " + body.length + " bytes（上限 " + IMAGE_MAX_BYTES + "）");
            }
            return body;
        } catch (Exception e) {
            log.warn("图片下载失败: url={}, error={}", url, e.getMessage());
            throw new RuntimeException("图片下载失败: " + e.getMessage(), e);
        }
    }

    /** 渠道 Key 非空才附加 Bearer（Ollama 本地无鉴权；非空渠道行为不变） */
    private void addAuthorization(HttpRequest.Builder builder, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    /** 带重试的 send：仅 429/5xx 重试 retryCount 次（指数退避）；连接/超时异常不重试 */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        int retries = config.getUpstream().getRetryCount();
        HttpResponse<String> resp = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException("上游请求失败: " + e.getMessage(), e);
            }
            int code = resp.statusCode();
            if (code != 429 && code < 500) return resp;
            if (attempt == retries) return resp;
            log.warn("上游返回 {}，第 {}/{} 次重试", code, attempt + 1, retries);
            sleep((long)(1000L * (1L << attempt)) + ThreadLocalRandom.current().nextInt(500));
        }
        return resp;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 提取上游错误体中的 message（OAI 风格 {error:{message}}，兼容多层嵌套） */
    public String extractError(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode msg = node.path("error").path("message");
            if (!msg.isMissingNode()) return msg.asText("upstream error");
            JsonNode direct = node.path("message");
            if (!direct.isMissingNode()) return direct.asText("upstream error");
        } catch (Exception ignored) { }
        String t = body == null ? "" : body.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
