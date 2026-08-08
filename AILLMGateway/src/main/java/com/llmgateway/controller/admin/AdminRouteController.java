package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.RouteRequest;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/** 模型路由管理：模型名 → 渠道映射 CRUD */
@RestController
@RequestMapping("/admin/routes")
public class AdminRouteController {

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;

    public AdminRouteController(ModelRouteMapper routeMapper, ChannelMapper channelMapper) {
        this.routeMapper = routeMapper;
        this.channelMapper = channelMapper;
    }

    /** 创建路由：modelName + channelId 必填，且渠道必须存在 */
    @PostMapping
    public ApiResponse<ModelRoute> create(@RequestBody RouteRequest request) {
        if (request.getModelName() == null || request.getModelName().isBlank()
                || request.getChannelId() == null || request.getChannelId().isBlank()) {
            throw new BusinessException(40001, "modelName/channelId 不能为空");
        }
        if (channelMapper.selectById(request.getChannelId()) == null) {
            throw new BusinessException(40401, "渠道不存在");
        }
        ModelRoute route = new ModelRoute();
        route.setModelName(request.getModelName());
        route.setChannelId(request.getChannelId());
        route.setDefaultParams(request.getDefaultParams());
        route.setCreatedAt(OffsetDateTime.now());
        route.setUpdatedAt(OffsetDateTime.now());
        routeMapper.insert(route);
        return ApiResponse.ok(route);
    }

    /** 路由列表（第一版返回原始行，不做渠道名 join） */
    @GetMapping
    public ApiResponse<List<ModelRoute>> list() {
        List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .orderByAsc(ModelRoute::getModelName));
        return ApiResponse.ok(routes);
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelRoute> update(@PathVariable String id, @RequestBody RouteRequest request) {
        ModelRoute route = routeMapper.selectById(id);
        if (route == null) throw new BusinessException(40401, "路由不存在");
        if (request.getModelName() != null) route.setModelName(request.getModelName());
        if (request.getChannelId() != null) {
            // 换渠道时同样校验渠道存在
            if (channelMapper.selectById(request.getChannelId()) == null) {
                throw new BusinessException(40401, "渠道不存在");
            }
            route.setChannelId(request.getChannelId());
        }
        if (request.getDefaultParams() != null) route.setDefaultParams(request.getDefaultParams());
        route.setUpdatedAt(OffsetDateTime.now());
        routeMapper.updateById(route);
        return ApiResponse.ok(route);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        if (routeMapper.deleteById(id) == 0) throw new BusinessException(40401, "路由不存在");
        return ApiResponse.ok(null);
    }
}
