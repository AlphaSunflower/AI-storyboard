package com.moon.xiaoyuecanvas.controller;

import com.moon.moonagent.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关模型列表端点：把网关 /v1/models 的模型列表（含 params 能力枚举 + 默认标记）
 * 透传给前端，供 ChannelModelSelector 动态下拉「模型 + 参数」。
 *
 * <p>前端不直连网关（避免网关 key 泄露 + CORS），统一经本端点代理，网关 key 由后端
 * {@link GatewayModelService} 内部从 {@code ai.gateway.api-key} 注入。
 *
 * <p>默认模型（is_default → "default":"true"）由前端用于"未选时回退网关默认"，不再硬编码。
 */
@RestController
@RequiredArgsConstructor
public class GatewayModelsController {

    private final GatewayModelService gatewayModelService;

    /**
     * {@code GET /models?type=image} → [{value,label,default?,params?}]；
     * type 取值 image / video / vision / text，缺省返回空列表。
     */
    @GetMapping("/models")
    public List<Map<String, String>> models(@RequestParam(required = false) String type) {
        List<Map<String, String>> models = gatewayModelService.fetchModels(type);
        if (!models.isEmpty()) return models;

        String fallback = switch (type == null ? "" : type) {
            case "image" -> gatewayModelService.getDefaultImageModel();
            case "video" -> gatewayModelService.getDefaultVideoModel();
            case "vision" -> gatewayModelService.getDefaultVisionModel();
            case "text" -> gatewayModelService.getDefaultTextModel();
            default -> null;
        };
        if (fallback == null || fallback.isBlank()) return List.of();
        return List.of(Map.of(
                "value", fallback,
                "label", fallback,
                "default", "true"
        ));
    }
}
