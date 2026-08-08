package com.llmgateway.controller;

import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.VideoGatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
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
    private final ModelParamsMapper modelParamsMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatController(GatewayRoutingService routingService,
                                  VideoGatewayService videoGatewayService,
                                  ImageEditService imageEditService,
                                  ModelRouteMapper routeMapper,
                                  ChannelMapper channelMapper,
                                  ModelParamsMapper modelParamsMapper) {
        this.routingService = routingService;
        this.videoGatewayService = videoGatewayService;
        this.imageEditService = imageEditService;
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
        this.modelParamsMapper = modelParamsMapper;
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
        // 查全量模型参数表，按 modelName 建立索引（组装 data[] 时逐模型取参）
        Map<String, ModelParams> paramsMap = modelParamsMapper.selectList(null).stream()
                .collect(Collectors.toMap(ModelParams::getModelName, p -> p, (a, b) -> a));
        // 组装 OpenAI 风格响应：{"object":"list","data":[{"id":..,"object":"model","type":..,"params":..}]}
        List<Map<String, Object>> data = new java.util.ArrayList<>();
        modelTypeMap.forEach((name, t) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", name);
            m.put("object", "model");
            m.put("type", t);
            // 按 model_name 组装 params（能力+默认值；未配置 → null）
            m.put("params", buildParams(paramsMap.get(name)));
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

    /**
     * 按 model_name 组装 params 对象（能力枚举 + 默认值）：
     * - 无配置记录 → null
     * - 各字段非空才放入（全空 → null）
     * text：defaults{temperature,max_tokens,top_p}；image：n{min,max,default} + sizes/sizeDefault + qualities/qualityDefault + styles/styleDefault
     * video：durations(Integer[])/durationDefault + resolutions/resolutionDefault + aspectRatios/aspectRatioDefault
     */
    private Map<String, Object> buildParams(ModelParams mp) {
        if (mp == null) return null;
        Map<String, Object> params = new LinkedHashMap<>();
        // text 默认值对象（任一非空才放；temperature/top_p 存 TEXT，转 double）
        Map<String, Object> defaults = new LinkedHashMap<>();
        if (mp.getTemperature() != null) defaults.put("temperature", Double.parseDouble(mp.getTemperature()));
        if (mp.getMaxTokens() != null) defaults.put("max_tokens", mp.getMaxTokens());
        if (mp.getTopP() != null) defaults.put("top_p", Double.parseDouble(mp.getTopP()));
        if (!defaults.isEmpty()) params.put("defaults", defaults);
        // image：n 范围 + 默认（各自非空才放）
        if (mp.getNMin() != null || mp.getNMax() != null || mp.getNDefault() != null) {
            Map<String, Object> n = new LinkedHashMap<>();
            if (mp.getNMin() != null) n.put("min", mp.getNMin());
            if (mp.getNMax() != null) n.put("max", mp.getNMax());
            if (mp.getNDefault() != null) n.put("default", mp.getNDefault());
            params.put("n", n);
        }
        // image：枚举 + 默认（逗号分隔 → 数组；空跳过）
        putCsvList(params, "sizes", mp.getSizes());
        putDefault(params, "sizeDefault", mp.getSizeDefault());
        putCsvList(params, "qualities", mp.getQualities());
        putDefault(params, "qualityDefault", mp.getQualityDefault());
        putCsvList(params, "styles", mp.getStyles());
        putDefault(params, "styleDefault", mp.getStyleDefault());
        // video：时长（Integer 数组）+ 默认、分辨率/画幅枚举 + 默认
        putCsvIntList(params, "durations", mp.getDurations());
        putIntDefault(params, "durationDefault", mp.getDurationDefault());
        putCsvList(params, "resolutions", mp.getResolutions());
        putDefault(params, "resolutionDefault", mp.getResolutionDefault());
        putCsvList(params, "aspectRatios", mp.getAspectRatios());
        putDefault(params, "aspectRatioDefault", mp.getAspectRatioDefault());
        return params.isEmpty() ? null : params;
    }

    /** 逗号分隔字符串 → 字符串数组（去空白；空串跳过；全部为空/空值 → 不放） */
    private void putCsvList(Map<String, Object> params, String key, String csv) {
        if (csv == null || csv.isBlank()) return;
        List<String> list = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (!list.isEmpty()) params.put(key, list);
    }

    /** 逗号分隔数字字符串 → Integer 数组（解析失败跳过；空 → 不放） */
    private void putCsvIntList(Map<String, Object> params, String key, String csv) {
        if (csv == null || csv.isBlank()) return;
        List<Integer> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                list.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // 单个非法值跳过，不影响整体
            }
        }
        if (!list.isEmpty()) params.put(key, list);
    }

    /** 默认值非空才放（空串/空白 → 不放） */
    private void putDefault(Map<String, Object> params, String key, String val) {
        if (val != null && !val.isBlank()) params.put(key, val.trim());
    }

    /** 数字默认值（如时长默认秒数）：可解析为 Integer 则放数字（契约 durationDefault 为 number），否则原样字符串 */
    private void putIntDefault(Map<String, Object> params, String key, String val) {
        if (val == null || val.isBlank()) return;
        try {
            params.put(key, Integer.parseInt(val.trim()));
        } catch (NumberFormatException e) {
            params.put(key, val.trim());
        }
    }
}
