package com.llmgateway.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelFallbackMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.ChannelKeyService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelCatalogCache;
import com.llmgateway.service.RouteResolver;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.vendor.route.RouteVendorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRoutingDefaultModelTest {

    @Test
    void modelListMarksTheRoutingDefaultEvenWhenTheParamsFlagDisagrees() throws Exception {
        ModelRouteMapper routeMapper = mock(ModelRouteMapper.class);
        ChannelMapper channelMapper = mock(ChannelMapper.class);
        ModelParamsMapper modelParamsMapper = mock(ModelParamsMapper.class);
        KeyService keyService = mock(KeyService.class);
        UpstreamClient upstreamClient = mock(UpstreamClient.class);
        RouteVendorRegistry routeRegistry = mock(RouteVendorRegistry.class);
        CallLogService callLogService = mock(CallLogService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        ModelRoute staleDefaultRoute = route("r1", "mimo-v2.6-pro", "c1", true);
        ModelRoute actualDefaultRoute = route("r2", "deepseek-v4-flash", "c2", false);
        when(routeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(staleDefaultRoute, actualDefaultRoute));
        when(routeMapper.selectList(null)).thenReturn(List.of(staleDefaultRoute, actualDefaultRoute));
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel("c1"), channel("c2")));
        when(channelMapper.selectList(null)).thenReturn(List.of(channel("c1"), channel("c2")));
        when(modelParamsMapper.selectList(null)).thenReturn(List.of(
                params("mimo-v2.6-pro", "text", false),
                params("deepseek-v4-flash", "text", true)
        ));

        GatewayRoutingServiceImpl service = new GatewayRoutingServiceImpl(
                routeMapper, channelMapper, modelParamsMapper, keyService, upstreamClient,
                routeRegistry, callLogService, new ModelCatalogCache(redisTemplate),
                mock(RouteResolver.class), mock(ChannelKeyService.class), mock(ModelFallbackMapper.class)
        );

        JsonNode data = new ObjectMapper().readTree(service.fetchModels("text")).path("data");
        // 路由默认（真正决定用哪个模型生成的开关）说了算；参数表的旧标记不再影响列表
        assertThat(find(data, "mimo-v2.6-pro").path("is_default").asBoolean()).isTrue();
        assertThat(find(data, "deepseek-v4-flash").path("is_default").asBoolean()).isFalse();
    }

    @Test
    void redisDefaultWinsWhenItIsStillInTheList() throws Exception {
        ModelRouteMapper routeMapper = mock(ModelRouteMapper.class);
        ChannelMapper channelMapper = mock(ChannelMapper.class);
        ModelParamsMapper modelParamsMapper = mock(ModelParamsMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        // Redis 里实际生效的默认模型与路由标记不一致时，以 Redis 为准（运行值优先）
        when(valueOperations.get("gateway:default_model:text")).thenReturn("deepseek-v4-flash");

        ModelRoute staleDefault = route("r1", "mimo-v2.6-pro", "c1", true);
        ModelRoute actualDefault = route("r2", "deepseek-v4-flash", "c2", false);
        when(routeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(staleDefault, actualDefault));
        when(routeMapper.selectList(null)).thenReturn(List.of(staleDefault, actualDefault));
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel("c1"), channel("c2")));
        when(channelMapper.selectList(null)).thenReturn(List.of(channel("c1"), channel("c2")));
        when(modelParamsMapper.selectList(null)).thenReturn(List.of(
                params("mimo-v2.6-pro", "text", true),
                params("deepseek-v4-flash", "text", false)
        ));

        GatewayRoutingServiceImpl service = new GatewayRoutingServiceImpl(
                routeMapper, channelMapper, modelParamsMapper, mock(KeyService.class),
                mock(UpstreamClient.class), mock(RouteVendorRegistry.class),
                mock(CallLogService.class), new ModelCatalogCache(redisTemplate),
                mock(RouteResolver.class), mock(ChannelKeyService.class), mock(ModelFallbackMapper.class));

        JsonNode data = new ObjectMapper().readTree(service.fetchModels("text")).path("data");
        assertThat(find(data, "deepseek-v4-flash").path("is_default").asBoolean()).isTrue();
        assertThat(find(data, "mimo-v2.6-pro").path("is_default").asBoolean()).isFalse();
    }

    private static JsonNode find(JsonNode data, String model) {
        for (JsonNode node : data) {
            if (model.equals(node.path("id").asText())) return node;
        }
        throw new AssertionError("model not found: " + model);
    }

    private static ModelRoute route(String id, String model, String channelId, boolean isDefault) {
        ModelRoute route = new ModelRoute();
        route.setId(id);
        route.setModelName(model);
        route.setChannelId(channelId);
        route.setType("text");
        route.setIsDefault(isDefault);
        return route;
    }

    private static Channel channel(String id) {
        Channel channel = new Channel();
        channel.setId(id);
        channel.setName(id);
        channel.setEnabled(true);
        channel.setPriority(0);
        return channel;
    }

    private static ModelParams params(String model, String type, boolean isDefault) {
        ModelParams params = new ModelParams();
        params.setModelName(model);
        params.setType(type);
        params.setIsDefault(isDefault);
        return params;
    }
}
