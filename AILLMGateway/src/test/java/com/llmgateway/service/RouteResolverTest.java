package com.llmgateway.service;

import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteResolverTest {

    private final ModelRouteMapper routeMapper = mock(ModelRouteMapper.class);
    private final ChannelMapper channelMapper = mock(ChannelMapper.class);
    private final RouteResolver resolver = new RouteResolver(routeMapper, channelMapper);

    @Test
    void excludesDisabledAndDeduplicatesAndOrdersByPriority() {
        when(routeMapper.selectList(any())).thenReturn(List.of(
                route("m", "c1", 0, true),
                route("m", "c2", 0, true),
                route("m", "c3", 1, true),
                route("m", "c4", 0, false),
                route("m", "c1", 0, true)   // 重复绑定
        ));
        for (String id : List.of("c1", "c2", "c3", "c4")) {
            when(channelMapper.selectById(id)).thenReturn(channel(id, true));
        }

        List<RouteResolver.RouteCandidate> candidates = resolver.resolve("m", null);

        assertEquals(3, candidates.size());
        assertTrue(List.of("c1", "c2").contains(candidates.get(0).channel().getId()));
        assertTrue(List.of("c1", "c2").contains(candidates.get(1).channel().getId()));
        assertEquals("c3", candidates.get(2).channel().getId());
    }

    @Test
    void forcedChannelIgnoresEnabledFlags() {
        when(routeMapper.selectList(any())).thenReturn(List.of(
                route("m", "c1", 0, false)
        ));
        when(channelMapper.selectById("c1")).thenReturn(channel("c1", false));

        List<RouteResolver.RouteCandidate> candidates = resolver.resolve("m", "c1");

        assertEquals(1, candidates.size());
        assertEquals("c1", candidates.get(0).channel().getId());
    }

    @Test
    void disabledChannelIsExcludedInNormalPath() {
        when(routeMapper.selectList(any())).thenReturn(List.of(route("m", "c1", 0, true)));
        when(channelMapper.selectById("c1")).thenReturn(channel("c1", false));

        assertTrue(resolver.resolve("m", null).isEmpty());
    }

    private static ModelRoute route(String model, String channelId, int priority, boolean enabled) {
        ModelRoute route = new ModelRoute();
        route.setModelName(model);
        route.setChannelId(channelId);
        route.setType("text");
        route.setPriority(priority);
        route.setWeight(1);
        route.setEnabled(enabled);
        return route;
    }

    private static Channel channel(String id, boolean enabled) {
        Channel channel = new Channel();
        channel.setId(id);
        channel.setName(id);
        channel.setEnabled(enabled);
        channel.setPriority(0);
        channel.setWeight(1);
        return channel;
    }
}
