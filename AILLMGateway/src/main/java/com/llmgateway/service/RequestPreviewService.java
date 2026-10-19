package com.llmgateway.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llmgateway.dto.admin.RequestPreviewRequest;
import com.llmgateway.dto.vo.RequestPreviewVO;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求预览：按真实路由链路算一遍「最终发给上游的 JSON」，但不发请求。
 *
 * <p>顺序与线上一致：Spec 默认值/键映射/值映射 → 路由 rename/delete/setIfAbsent/set → upstream_model。
 * 这样用户在改模型配置或路由参数覆盖时，能直接看到结果，而不是靠猜。
 */
@Service
@RequiredArgsConstructor
public class RequestPreviewService {

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final ModelParamsMapper modelParamsMapper;

    public RequestPreviewVO preview(RequestPreviewRequest req) {
        if (req == null || req.modelName() == null || req.modelName().isBlank()) {
            throw new IllegalArgumentException("modelName 不能为空");
        }
        if (req.body() == null || req.body().isBlank()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        String type = req.type() == null || req.type().isBlank() ? "image" : req.type();

        List<ModelRoute> routes = routeMapper.selectList(Wrappers.<ModelRoute>lambdaQuery()
                .eq(ModelRoute::getModelName, req.modelName())
                .eq(ModelRoute::getType, type)
                .orderByAsc(ModelRoute::getPriority));
        if (req.channelId() != null && !req.channelId().isBlank()) {
            routes = routes.stream()
                    .filter(r -> req.channelId().equals(String.valueOf(r.getChannelId())))
                    .toList();
        }
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("该模型没有匹配的路由，先到「模型路由」绑定渠道");
        }
        ModelRoute route = routes.get(0);
        Channel channel = channelMapper.selectById(route.getChannelId());
        if (channel == null) {
            throw new IllegalArgumentException("路由指向的渠道不存在: " + route.getChannelId());
        }

        ModelParams params = modelParamsMapper.selectOne(Wrappers.<ModelParams>lambdaQuery()
                .eq(ModelParams::getModelName, req.modelName())
                .eq(ModelParams::getType, type)
                .last("LIMIT 1"));
        String spec = params == null ? null : params.getParamsSchema();
        boolean passthrough = "openai_compatible".equals(channel.getType());

        List<String> steps = new ArrayList<>();
        SpecRequestMapping.Result specResult = SpecRequestMapping.applyWithTrace(req.body(), spec, passthrough);
        steps.addAll(specResult.steps());
        ParamOverrideSupport.Result overResult = ParamOverrideSupport.applyWithTrace(
                specResult.body(), route.getParamOverrides(), route.getUpstreamModel(), passthrough);
        steps.addAll(overResult.steps());
        if (spec == null || spec.isBlank()) {
            steps.add("未配置模型 Schema：只做路由覆盖，不做默认值/键值映射");
        }
        if (route.getParamOverrides() == null || route.getParamOverrides().isBlank()) {
            steps.add("该路由没有参数覆盖");
        }

        String path = req.path();
        if (path == null || path.isBlank()) {
            path = switch (type) {
                case "image" -> "/images/generations";
                case "text", "vision" -> "/chat/completions";
                default -> null;
            };
        }
        String targetUrl = null;
        String note = null;
        if (passthrough && path != null) {
            targetUrl = join(channel.getBaseUrl(), path);
        } else if (!passthrough) {
            note = "该渠道由 " + channel.getType() + " 适配器构建上游请求，最终 URL 不是简单的 baseUrl + path；上面 forwarded 是适配器输入。";
        }

        String upstreamModel = route.getUpstreamModel() == null || route.getUpstreamModel().isBlank()
                ? req.modelName() : route.getUpstreamModel();
        return new RequestPreviewVO(
                String.valueOf(route.getId()), req.modelName(), type,
                String.valueOf(channel.getId()), channel.getName(), channel.getType(),
                upstreamModel, targetUrl, note,
                spec != null && !spec.isBlank(),
                req.body(), specResult.body(), overResult.body(), steps);
    }

    private static String join(String base, String path) {
        if (base == null || base.isBlank()) return path;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }
}
