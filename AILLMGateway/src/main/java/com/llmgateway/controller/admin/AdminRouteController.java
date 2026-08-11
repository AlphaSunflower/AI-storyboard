package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.RouteRequest;
import com.llmgateway.dto.vo.ModelRouteVO;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.service.ModelRouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 模型路由管理：模型名 → 渠道映射 CRUD */
@RestController
@RequestMapping("/admin/routes")
@RequiredArgsConstructor
public class AdminRouteController {

    private final ModelRouteService routeService;

    /** 创建路由：modelName + channelId 必填，且渠道必须存在 */
    @PostMapping
    public ApiResponse<ModelRouteVO> create(@RequestBody RouteRequest request) {
        return ApiResponse.ok(toVO(routeService.create(request)));
    }

    /** 路由列表（第一版返回原始行，不做渠道名 join） */
    @GetMapping
    public ApiResponse<List<ModelRouteVO>> list() {
        return ApiResponse.ok(routeService.list().stream().map(this::toVO).toList());
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelRouteVO> update(@PathVariable String id, @RequestBody RouteRequest request) {
        return ApiResponse.ok(toVO(routeService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        routeService.delete(id);
        return ApiResponse.ok(null);
    }

    private ModelRouteVO toVO(ModelRoute e) {
        return new ModelRouteVO(e.getId(), e.getModelName(), e.getChannelId(), e.getType(),
                e.getDefaultParams(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
