package com.llmgateway.vendor.video.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.GatewayConfig;
import com.llmgateway.entity.Channel;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.vendor.video.dto.LaozhangVideoResult;
import com.llmgateway.vendor.video.dto.VendorCreateInput;
import com.llmgateway.vendor.video.dto.VideoVendorResult;
import com.llmgateway.vendor.video.spi.VideoVendorAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.UUID;

/**
 * Laozhang 视频协议适配器（channel.type = "openai_compatible"）。
 * <p>
 * 自含：请求载荷（multipart 表单 /videos）、创建/轮询响应解析（id/taskId）、换池重试策略。
 * 任何走 openai_compatible 渠道的多媒体视频协议（含 laozhang）复用本适配器——同协议复用。
 */
@Component
@RequiredArgsConstructor
public class LaozhangVideoAdapter implements VideoVendorAdapter {

    private final GatewayConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String channelType() {
        return "openai_compatible";
    }

    @Override
    public int maxCreateAttempts() {
        return 10;
    }

    @Override
    public boolean isRetryableCreate(int status, String body) {
        // 老张网关按池路由到多个上游 Google 项目：未开通 veo 的池返回 404 fail_to_fetch_task；
        // 失效服务账号返回 500（body 含 do_request_failed/invalid_grant，任务必然未创建）→ 可安全换池重试
        if (status == 404) return true;
        if (status < 500 || body == null) return false;
        return body.contains("fail_to_fetch_task") || body.contains("do_request_failed")
                || body.contains("invalid_grant");
    }

    @Override
    public long createBackoffMs(int attempt) {
        return 500L * attempt;
    }

    @Override
    public HttpRequest buildCreateRequest(VendorCreateInput in, Channel channel, String apiKey) {
        String boundary = "----llmgw" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        int duration = in.duration() == null ? 8 : in.duration();
        String size = in.size() == null ? "1280x720" : in.size();
        String resolution = in.resolution() == null ? "720p" : in.resolution();
        String aspectRatio = in.aspectRatio() == null ? "16:9" : in.aspectRatio();

        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n").append(in.model()).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n").append(in.prompt()).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"duration\"\r\n\r\n").append(duration).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"size\"\r\n\r\n").append(size).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"resolution\"\r\n\r\n").append(resolution).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"aspectRatio\"\r\n\r\n").append(aspectRatio).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"metadata\"\r\n\r\n")
          .append("{\"durationSeconds\":").append(duration)
          .append(",\"resolution\":\"").append(resolution)
          .append("\",\"aspectRatio\":\"").append(aspectRatio).append("\"}\r\n");
        if (in.negativePrompt() != null && !in.negativePrompt().isEmpty()) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"negativePrompt\"\r\n\r\n").append(in.negativePrompt()).append("\r\n");
        }
        if (in.seed() != null) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"seed\"\r\n\r\n").append(in.seed()).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");

        return HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(channel.getBaseUrl()) + "/videos"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMillis(config.getUpstream().getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();
    }

    @Override
    public VideoVendorResult parseCreate(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String taskId = root.path("id").asText(root.path("taskId").asText(""));
            return new LaozhangVideoResult("openai_compatible", taskId, "", null, null, null, null);
        } catch (Exception e) {
            throw new BusinessException(50201, "Laozhang 创建响应解析失败: " + e.getMessage());
        }
    }

    @Override
    public VideoVendorResult parsePoll(String rawBody, String taskId, Channel channel, String apiKey) {
        try {
            JsonNode json = objectMapper.readTree(rawBody);
            String status = normalizeStatus(json.path("status").asText(""));
            String error = null;
            if ("failed".equals(status)) {
                error = json.path("error").path("message").asText("");
                if (error.isBlank()) error = json.path("error").asText("");
                if (error.isBlank()) error = json.path("message").asText("");
                if (error.isBlank()) error = "video generation failed";
            }
            String progress = json.path("progress").isMissingNode() ? null : json.path("progress").asText();
            return new LaozhangVideoResult("openai_compatible", taskId, status, progress, null, error, null);
        } catch (Exception e) {
            throw new BusinessException(50201, "Laozhang 轮询响应解析失败: " + e.getMessage());
        }
    }

    /** 状态归一：succeeded/completed/done → succeeded；failed/fail → failed；其余 → processing */
    private String normalizeStatus(String status) {
        switch (status == null ? "" : status.toLowerCase()) {
            case "succeeded", "success", "completed", "done": return "succeeded";
            case "failed", "fail": return "failed";
            default: return "processing";
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
