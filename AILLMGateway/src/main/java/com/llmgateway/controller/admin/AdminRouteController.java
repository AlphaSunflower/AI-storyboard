package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.RequestPreviewRequest;
import com.llmgateway.dto.admin.RouteRequest;
import com.llmgateway.dto.vo.ModelRouteVO;
import com.llmgateway.dto.vo.RequestPreviewVO;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.service.ModelFallbackService;
import com.llmgateway.service.ModelRouteService;
import com.llmgateway.service.RequestPreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 模型路由管理：模型名 → 渠道映射 CRUD */
@RestController
@RequestMapping("/admin/routes")
@RequiredArgsConstructor
public class AdminRouteController {

    private final ModelRouteService routeService;
    private final ModelFallbackService fallbackService;
    private final RequestPreviewService previewService;

    /** 请求预览：不调用上游，展示 Spec + 路由覆盖后的最终请求 */
    @PostMapping("/preview")
    public ApiResponse<RequestPreviewVO> preview(@RequestBody RequestPreviewRequest request) {
        return ApiResponse.ok(previewService.preview(request));
    }

    /** 创建路由：modelName + channelId 必填，且渠道必须存在 */
    @PostMapping
    public ApiResponse<ModelRouteVO> create(@RequestBody RouteRequest request) {
        ModelRoute route = routeService.create(request);
        if (request.getFallbacks() != null) {
            fallbackService.replace(route.getModelName(), route.getType(), request.getFallbacks());
        }
        return ApiResponse.ok(toVO(route));
    }

    /** 路由列表（第一版返回原始行，不做渠道名 join） */
    @GetMapping
    public ApiResponse<List<ModelRouteVO>> list() {
        return ApiResponse.ok(routeService.list().stream().map(this::toVO).toList());
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelRouteVO> update(@PathVariable String id, @RequestBody RouteRequest request) {
        ModelRoute route = routeService.update(id, request);
        if (request.getFallbacks() != null) {
            fallbackService.replace(route.getModelName(), route.getType(), request.getFallbacks());
        }
        return ApiResponse.ok(toVO(route));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        routeService.delete(id);
        return ApiResponse.ok(null);
    }

    private ModelRouteVO toVO(ModelRoute e) {
        return new ModelRouteVO(e.getId(), e.getModelName(), e.getChannelId(), e.getType(),
                e.getDefaultParams(), e.getIsDefault(), e.getEnabled(), e.getPriority(), e.getWeight(),
                e.getUpstreamModel(), e.getTimeoutMs(), e.getMaxRetries(), e.getParamOverrides(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
