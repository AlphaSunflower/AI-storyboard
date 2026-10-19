package com.llmgateway.service.impl;

import com.llmgateway.entity.Channel;
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
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.vendor.route.ChatImageVendorAdapter;
import com.llmgateway.vendor.route.RouteVendorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理后台「测试这条路由」必须锁定该行渠道：
 * 同模型两条路由时，普通路由按优先级/权重选，测不出第二行。
 */
@ExtendWith(MockitoExtension.class)
class GatewayRoutingServiceImplForcedChannelTest {

    @Mock
    private ModelRouteMapper routeMapper;
    @Mock
    private ChannelMapper channelMapper;
    @Mock
    private ModelParamsMapper modelParamsMapper;
    @Mock
    private KeyService keyService;
    @Mock
    private UpstreamClient upstreamClient;
    @Mock
    private RouteVendorRegistry routeRegistry;
    @Mock
    private CallLogService callLogService;
    @Mock
    private ModelCatalogCache catalogCache;
    @Mock
    private RouteResolver routeResolver;
    @Mock
    private ChannelKeyService channelKeyService;
    @Mock
    private ModelFallbackMapper modelFallbackMapper;
    @Mock
    private ChatImageVendorAdapter adapter;
    @InjectMocks
    private GatewayRoutingServiceImpl service;

    @Test
    void forcedChannel_isUsedEvenWhenDisabledAndNotFirstCandidate() throws Exception {
        ModelRoute route = new ModelRoute();
        route.setModelName("m");
        route.setChannelId("B");
        route.setType("text");
        route.setEnabled(true);

        Channel channel = new Channel();
        channel.setId("B");
        channel.setType("openai_compatible");
        channel.setName("B 渠道");
        channel.setEnabled(false);
        channel.setPriority(99);

        RouteResolver.RouteCandidate candidate =
                new RouteResolver.RouteCandidate(route, channel, 1, 0, 99);
        when(routeResolver.resolve("m", "B")).thenReturn(List.of(candidate));
        when(channelKeyService.select(channel))
                .thenReturn(new ChannelKeyService.KeySelection("k1", "k"));
        when(routeRegistry.resolve("openai_compatible")).thenReturn(adapter);
        when(adapter.forward(anyString(), anyString(), eq(channel), eq("k"), isNull()))
                .thenReturn(new RouteResult(200, "{}"));

        RouteResult result = service.route("/chat/completions",
                "{\"model\":\"m\",\"messages\":[]}", null, "B");

        assertEquals(200, result.status());
        verify(adapter).forward(eq("/chat/completions"), anyString(), eq(channel), eq("k"), isNull());
        verify(channelKeyService).markSuccess("k1");
        verify(callLogService).log(eq("m"), eq("B"), eq("success"), anyLong(),
                isNull(), isNull(), isNull(), isNull(), eq("m"), eq(1), isNull(), isNull());
    }
}
