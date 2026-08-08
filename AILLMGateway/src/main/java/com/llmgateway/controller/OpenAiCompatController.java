package com.llmgateway.controller;

import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.VideoGatewayService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** OpenAI 兼容对外入口（静态 Key 鉴权由 StaticApiKeyFilter 完成） */
@RestController
@RequestMapping("/v1")
public class OpenAiCompatController {

    private final GatewayRoutingService routingService;
    private final VideoGatewayService videoGatewayService;

    public OpenAiCompatController(GatewayRoutingService routingService, VideoGatewayService videoGatewayService) {
        this.routingService = routingService;
        this.videoGatewayService = videoGatewayService;
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chatCompletions(@RequestBody String body) {
        GatewayRoutingService.RouteResult result = routingService.route("/chat/completions", body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping(value = "/images/generations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> imageGenerations(@RequestBody String body) {
        GatewayRoutingService.RouteResult result = routingService.route("/images/generations", body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public String models() {
        return "{\"object\":\"list\",\"data\":[]}";
    }

    /** 创建视频任务：按 model 路由（MiniMax-H3→minimax / veo-*→laozhang），上游响应透传 */
    @PostMapping(value = "/videos", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createVideo(@RequestBody String body) {
        VideoGatewayService.VideoResult result = videoGatewayService.create(body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 轮询视频任务状态：遍历 enabled 渠道反查（第一版简化方案），上游响应透传 */
    @GetMapping(value = "/videos/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pollVideo(@PathVariable String taskId) {
        VideoGatewayService.VideoResult result = videoGatewayService.poll(taskId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /** 视频下载：网关流式代理（Laozhang 转发原生端点；MiniMax 用 call_log 暂存的限时直链） */
    @GetMapping(value = "/videos/{taskId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            videoContent(@PathVariable String taskId) {
        return videoGatewayService.download(taskId);
    }
}
