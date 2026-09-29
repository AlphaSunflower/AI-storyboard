package com.llmgateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.service.FileUploadService;
import com.llmgateway.service.ChatStreamEventNormalizer;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容对外入口（静态 Key 鉴权由 StaticApiKeyFilter 完成）。
 * 仅收参 → 调 Service → 透传上游响应，不持有 Mapper 与业务逻辑。
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class OpenAiCompatController {

    private final GatewayRoutingService routingService;
    private final VideoGatewayService videoGatewayService;
    private final ImageEditService imageEditService;
    private final FileUploadService fileUploadService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * OpenAI 兼容 chat completions：stream=true 走 SSE 流式透传（text/event-stream），
     * 否则同步缓冲透传（application/json）。
     */
    @PostMapping(value = "/chat/completions", produces = {
            MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public void chatCompletions(@RequestBody String body,
                                @RequestHeader(value = "X-Call-Id", required = false) String callId,
                                jakarta.servlet.http.HttpServletResponse response) throws Exception {
        boolean stream = body != null && body.contains("\"stream\":true");
        if (!stream) {
            RouteResult result = routingService.route("/chat/completions", body, callId);
            response.setStatus(result.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(result.body());
            return;
        }
        // SSE 流式透传：上游逐块转发，边写边 flush（不缓冲）
        response.setStatus(200);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        jakarta.servlet.ServletOutputStream out = response.getOutputStream();
        routingService.streamChat("/chat/completions", body, bytes -> {
            try {
                out.write(bytes);
                out.flush();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e); // 客户端断开：中断转发
            }
        }, callId);
    }

    /**
     * Normalized NDJSON stream for internal agents. Unlike the raw SSE endpoint,
     * this preserves non-standard reasoning_content deltas emitted by gateways.
     */
    @PostMapping(value = "/chat/completions/stream-events", produces = "application/x-ndjson")
    public void chatCompletionsEvents(@RequestBody String body,
                                      @RequestHeader(value = "X-Call-Id", required = false) String callId,
                                      jakarta.servlet.http.HttpServletResponse response) throws Exception {
        response.setStatus(200);
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        jakarta.servlet.ServletOutputStream out = response.getOutputStream();
        ChatStreamEventNormalizer normalizer = new ChatStreamEventNormalizer(line -> {
            try {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
        try {
            routingService.streamChat("/chat/completions", withStreamTrue(body),
                    normalizer::accept, callId);
            normalizer.complete();
        } catch (Exception exception) {
            normalizer.error(exception.getMessage());
        }
    }

    @PostMapping(value = "/images/generations", produces = {
            MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public void imageGenerations(@RequestBody String body,
                                 jakarta.servlet.http.HttpServletResponse response) throws Exception {
        if (!isStream(body)) {
            RouteResult result = routingService.route("/images/generations", body);
            response.setStatus(result.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(result.body());
            return;
        }

        response.setStatus(200);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        jakarta.servlet.ServletOutputStream out = response.getOutputStream();
        AtomicBoolean wrote = new AtomicBoolean(false);
        try {
            routingService.streamChat("/images/generations", body, bytes -> {
                try {
                    wrote.set(true);
                    out.write(bytes);
                    out.flush();
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            jakarta.servlet.ServletOutputStream errorOut = out;
            if (!wrote.get() && !response.isCommitted()) {
                response.reset();
                response.setStatus(200);
                response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                errorOut = response.getOutputStream();
            }
            errorOut.write(sseError(e.getMessage()));
            errorOut.flush();
        }
    }

    /** JSON body 中 stream=true 时走图片 SSE；解析失败保持同步透传的旧行为。 */
    private boolean isStream(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.path("stream").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] sseError(String message) throws Exception {
        ObjectNode error = objectMapper.createObjectNode();
        ObjectNode payload = error.putObject("error");
        payload.put("code", "GatewayStreamError");
        payload.put("message", message == null || message.isBlank() ? "upstream stream failed" : message);
        return ("data: " + objectMapper.writeValueAsString(error) + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String withStreamTrue(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("chat request body must be a JSON object");
        }
        object.put("stream", true);
        return objectMapper.writeValueAsString(object);
    }

    @PostMapping(value = "/images/edits", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> imageEdits(@RequestBody byte[] body,
                                             @RequestHeader("Content-Type") String contentType) {
        // 图改图：原始 multipart 字节流 + Content-Type（含 boundary）原样透传
        // 透传上游真实状态码（4xx 错误体不再被 200 包装，对齐 chat/images 端点语义）
        RouteResult result = imageEditService.edit(body, contentType);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public String models(@RequestParam(required = false) String type) {
        // 模型列表组装（路由/渠道/参数）已在 Service 层完成，此处直接透传 JSON 字符串
        return routingService.fetchModels(type);
    }

    /** 创建视频任务：按 model 路由（MiniMax-H3→minimax / veo-*→laozhang），上游响应透传 */
    @PostMapping(value = "/videos", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createVideo(@RequestBody String body) {
        VideoResult result = videoGatewayService.create(body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 轮询视频任务状态：遍历 enabled 渠道反查（第一版简化方案），上游响应透传 */
    @GetMapping(value = "/videos/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pollVideo(@PathVariable String taskId) {
        VideoResult result = videoGatewayService.poll(taskId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * 取消/删除视频任务：按厂商能力适配（MiniMax v2: DELETE /v2/video_generation/{taskId}）。
     * queued -> cancelled；succeeded/failed -> deleted；running/cancelled -> 错误。
     */
    @DeleteMapping(value = "/videos/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> cancelVideo(@PathVariable String taskId) {
        VideoResult result = videoGatewayService.cancel(taskId);
        return new ResponseEntity<>(result.body(), HttpStatus.valueOf(result.status()));
    }

    /** 视频下载：网关流式代理（Laozhang 转发原生端点；MiniMax 用 call_log 暂存的限时直链） */
    @GetMapping(value = "/videos/{taskId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            videoContent(@PathVariable String taskId) {
        return videoGatewayService.download(taskId);
    }

    /** 文件上传代理：原始 multipart 字节流 + Content-Type（含 boundary）→ MiniMax，返回 {"file_id":"mm_file://..."} */
    @PostMapping(value = "/files/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> fileUpload(@RequestBody byte[] body,
                                            @RequestHeader("Content-Type") String contentType) {
        String fileId = fileUploadService.upload(contentType, body);
        return ResponseEntity.ok("{\"file_id\":\"" + fileId + "\"}");
    }
}
