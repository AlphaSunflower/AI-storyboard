package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.ModelCatalogCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「默认模型」只能有一个权威来源（路由默认），参数页开关与参数表标记都必须跟着它走，
 * 否则会出现「列表标默认 A、实际生成用 B」这类漂移。
 */
class DefaultModelConsistencyTest {

    /** Lambda 条件构造器需要实体的 TableInfo：脱离 Spring/MyBatis 上下文时手动初始化一次 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ModelRoute.class);
        TableInfoHelper.initTableInfo(assistant, ModelParams.class);
    }

    /**
     * Redis 替身：模板与值操作两个 mock。
     * 不写成 {@code verify(redisTemplate.opsForValue()).set(...)} 链式校验——那样会把中间那次
     * {@code opsForValue()} 的调用次数也按 times(1) 严格比对，而缓存组件一次变更里会多次取
     * opsForValue()（写默认 + 换版本号），链式校验会误报 TooManyActualInvocations。
     */
    private record RedisDouble(StringRedisTemplate template, ValueOperations<String, String> values) {
    }

    @SuppressWarnings("unchecked")
    private static RedisDouble redis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        return new RedisDouble(template, values);
    }

    @Test
    void setDefaultRouteWritesRouteFlagRedisAndClearsListCache() {
        ModelRouteMapper routeMapper = mock(ModelRouteMapper.class);
        ChannelMapper channelMapper = mock(ChannelMapper.class);
        ModelParamsMapper modelParamsMapper = mock(ModelParamsMapper.class);
        RedisDouble redis = redis();
        ModelRoute target = route("r1", "doubao-seedream-4-0-250828", "image", false);
        when(routeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(target));

        ModelRouteServiceImpl service = new ModelRouteServiceImpl(
                routeMapper, channelMapper, modelParamsMapper, new ModelCatalogCache(redis.template()));

        boolean applied = service.setDefaultRoute("image", "doubao-seedream-4-0-250828", true);

        assertThat(applied).isTrue();
        assertThat(target.getIsDefault()).isTrue();
        verify(routeMapper).updateById(any(ModelRoute.class));
        verify(redis.values()).set("gateway:default_model:image", "doubao-seedream-4-0-250828");
        verify(redis.template()).delete("gateway:models:image");
        verify(redis.template()).delete("gateway:models:all");
    }

    @Test
    void setDefaultRouteReportsMissingRouteInsteadOfSilentlyDoingNothing() {
        ModelRouteMapper routeMapper = mock(ModelRouteMapper.class);
        when(routeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        ModelRouteServiceImpl service = new ModelRouteServiceImpl(
                routeMapper, mock(ChannelMapper.class),
                mock(ModelParamsMapper.class), new ModelCatalogCache(redis().template()));

        assertThat(service.setDefaultRoute("image", "no-such-model", true)).isFalse();
        verify(routeMapper, never()).updateById(any(ModelRoute.class));
    }

    @Test
    void startupReconcilesTheParamsFlagToTheRoutingDefault() {
        ModelRouteMapper routeMapper = mock(ModelRouteMapper.class);
        ChannelMapper channelMapper = mock(ChannelMapper.class);
        ModelParamsMapper modelParamsMapper = mock(ModelParamsMapper.class);
        RedisDouble redis = redis();
        when(routeMapper.selectList(any(Wrapper.class))).thenReturn(
                List.of(route("r1", "doubao-seedream-4-0-250828", "image", true)));
        // 默认路由必须挂在启用渠道上才会被认作可用默认（禁用渠道的默认会让生成撞 50301）
        when(channelMapper.selectById("c1")).thenReturn(channel("c1"));

        ModelRouteServiceImpl service = new ModelRouteServiceImpl(
                routeMapper, channelMapper, modelParamsMapper, new ModelCatalogCache(redis.template()));

        service.warmRedisDefaults();

        // 路由默认写进 Redis，并把参数表里同类型的旧标记清掉、给默认模型打上标记
        verify(redis.values(), atLeastOnce())
                .set("gateway:default_model:image", "doubao-seedream-4-0-250828");
        verify(modelParamsMapper, atLeastOnce())
                .update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class));
    }

    @Test
    void paramsPageDefaultToggleWritesThroughToTheRoutingDefault() {
        ModelParamsMapper modelParamsMapper = mock(ModelParamsMapper.class);
        com.llmgateway.service.ModelRouteService routeService =
                mock(com.llmgateway.service.ModelRouteService.class);
        when(modelParamsMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ModelParamsServiceImpl service = new ModelParamsServiceImpl(
                modelParamsMapper, routeService, new ModelCatalogCache(redis().template()));

        service.upsert(paramsRequest(
                "doubao-seedream-4-0-250828", "image", true));

        verify(routeService).setDefaultRoute("image", "doubao-seedream-4-0-250828", true);
    }

    /** 只填关键三项，其余保持「不修改」 */
    private static com.llmgateway.dto.admin.ModelParamsRequest paramsRequest(
            String modelName, String type, Boolean isDefault) {
        return new com.llmgateway.dto.admin.ModelParamsRequest(
                modelName, type, isDefault,
                null, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null,
                null, null,
                null, null);
    }

    private static ModelRoute route(String id, String model, String type, boolean isDefault) {
        ModelRoute route = new ModelRoute();
        route.setId(id);
        route.setModelName(model);
        route.setChannelId("c1");
        route.setType(type);
        route.setIsDefault(isDefault);
        return route;
    }

    private static Channel channel(String id) {
        Channel channel = new Channel();
        channel.setId(id);
        channel.setName(id);
        channel.setEnabled(true);
        return channel;
    }
}
