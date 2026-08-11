package com.llmgateway.controller;

import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /**
     * OpenAI 兼容 chat completions：stream=true 走 SSE 流式透传（text/event-stream），
     * 否则同步缓冲透传（application/json）。
     */
    @PostMapping(value = "/chat/completions", produces = {
            MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public void chatCompletions(@RequestBody String body,
                                jakarta.servlet.http.HttpServletResponse response) throws Exception {
        boolean stream = body != null && body.contains("\"stream\":true");
        if (!stream) {
            RouteResult result = routingService.route("/chat/completions", body);
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
        });
    }

    @PostMapping(value = "/images/generations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> imageGenerations(@RequestBody String body) {
        RouteResult result = routingService.route("/images/generations", body);
        return ResponseEntity.status(result.status()).body(result.body());
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

    /** 视频下载：网关流式代理（Laozhang 转发原生端点；MiniMax 用 call_log 暂存的限时直链） */
    @GetMapping(value = "/videos/{taskId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            videoContent(@PathVariable String taskId) {
        return videoGatewayService.download(taskId);
    }
}
