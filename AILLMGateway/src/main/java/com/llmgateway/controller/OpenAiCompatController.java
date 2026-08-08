package com.llmgateway.controller;

import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.VideoGatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** OpenAI 兼容对外入口（静态 Key 鉴权由 StaticApiKeyFilter 完成） */
@RestController
@RequestMapping("/v1")
public class OpenAiCompatController {

    private final GatewayRoutingService routingService;
    private final VideoGatewayService videoGatewayService;
    private final ImageEditService imageEditService;
    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatController(GatewayRoutingService routingService,
                                  VideoGatewayService videoGatewayService,
                                  ImageEditService imageEditService,
                                  ModelRouteMapper routeMapper,
                                  ChannelMapper channelMapper) {
        this.routingService = routingService;
        this.videoGatewayService = videoGatewayService;
        this.imageEditService = imageEditService;
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
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

    @PostMapping(value = "/images/edits", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> imageEdits(@RequestBody byte[] body,
                                             @RequestHeader("Content-Type") String contentType) {
        // 图改图：原始 multipart 字节流 + Content-Type（含 boundary）原样透传
        // 透传上游真实状态码（4xx 错误体不再被 200 包装，对齐 chat/images 端点语义）
        GatewayRoutingService.RouteResult result = imageEditService.edit(body, contentType);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public String models(@RequestParam(required = false) String type) throws Exception {
        // 从 model_route 返回可用模型列表（OpenAI 风格 {data:[{id,type}]}），供调用方（如 AI 分镜前端）动态获取生图/生视频模型
        // type 过滤（image/video/text/vision）；渠道须启用；同一模型多渠道轮换时去重保留首个
        List<ModelRoute> routes = routeMapper.selectList(null);
        Set<String> enabledChannels = channelMapper.selectList(null).stream()
                .filter(c -> c.getEnabled() == null || c.getEnabled())
                .map(Channel::getId)
                .collect(Collectors.toSet());
        Map<String, String> modelTypeMap = new LinkedHashMap<>();   // modelName -> type
        for (ModelRoute r : routes) {
            if (r.getChannelId() == null || !enabledChannels.contains(r.getChannelId())) continue;
            String t = r.getType() == null || r.getType().isBlank() ? "text" : r.getType();
            if (type != null && !type.isBlank() && !type.equals(t)) continue;
            modelTypeMap.putIfAbsent(r.getModelName(), t);
        }
        // 组装 OpenAI 风格响应：{"object":"list","data":[{"id":..,"object":"model","type":..}]}
        List<Map<String, Object>> data = new java.util.ArrayList<>();
        modelTypeMap.forEach((name, t) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", name);
            m.put("object", "model");
            m.put("type", t);
            data.add(m);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        return objectMapper.writeValueAsString(result);
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
