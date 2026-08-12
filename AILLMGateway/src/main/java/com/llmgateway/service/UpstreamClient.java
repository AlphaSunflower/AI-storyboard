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

/** 上游调用客户端：透传请求体、替换 Bearer、轻量重试（429/5xx 重试 2 次） */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        return sendWithRetry(request);
    }

    /**
     * POST JSON 流式（SSE）：上游 stream=true 响应以 InputStream 返回，调用方逐块转发。
     * 超时用流式专用长超时（SSE 长连接，token 间隔可数秒；复用 requestTimeoutMs 会中断长生成）。
     * 不重试——SSE 流一旦建立无法安全重放，重试语义交给调用方（渠道轮换）。
     */
    public HttpResponse<java.io.InputStream> postJsonStream(String baseUrl, String path, String apiKey, String bodyJson) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(Math.max(config.getUpstream().getRequestTimeoutMs(), 300_000L)))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            log.warn("上游流式请求失败: path={}, error={}", path, e.getMessage());
            throw new RuntimeException("upstream stream request failed: " + e.getMessage(), e);
        }
    }

    /** POST Gemini 原生格式（Key 走 query 参数） */
    public HttpResponse<String> postGemini(String baseUrl, String apiKey, String bodyJson) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + "?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(baseUrl) + path))
                .header("Content-Type", contentType)          // 透传上游 Content-Type（含 boundary）
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();
        return sendWithRetry(request);
    }

    /** GET 上游（轮询视频状态等） */
    public HttpResponse<String> get(String url, String apiKey) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .GET().build();
        return sendWithRetry(request);
    }

    /** 带重试的 send：429/5xx 重试 retryCount 次（指数退避） */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        int retries = config.getUpstream().getRetryCount();
        HttpResponse<String> resp = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                if (attempt == retries) throw new RuntimeException("上游请求失败: " + e.getMessage(), e);
                sleep(500L * (attempt + 1));
                continue;
            }
            int code = resp.statusCode();
            if (code != 429 && code < 500) return resp;
            if (attempt == retries) return resp;
            log.warn("上游返回 {}，第 {}/{} 次重试", code, attempt + 1, retries);
            sleep(500L * (attempt + 1));
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
