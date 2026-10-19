package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 路由候选解析：把某个公共模型名的绑定行，变成按「优先级 + 权重」排好序的渠道候选。
 *
 * <p>规则：
 * <ul>
 *   <li>跳过停用路由、停用渠道、渠道已被逻辑删除的行；</li>
 *   <li>同一渠道重复绑定只保留一条；</li>
 *   <li>先按路由 priority 升序，再按渠道 priority 升序；</li>
 *   <li>同一优先级层内按 weight 加权随机（无放回），既分摊流量又能故障切换；</li>
 *   <li>weight = 路由 weight × 渠道 weight（都至少 1）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RouteResolver {

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;

    public List<RouteCandidate> resolve(String model, String forcedChannelId) {
        if (model == null || model.isBlank()) return List.of();
        List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .eq(ModelRoute::getModelName, model.trim()));
        if (routes == null || routes.isEmpty()) return List.of();

        Map<String, RouteCandidate> byChannel = new LinkedHashMap<>();
        boolean forced = forcedChannelId != null && !forcedChannelId.isBlank();
        for (ModelRoute route : routes) {
            if (!forced && Boolean.FALSE.equals(route.getEnabled())) continue;
            if (forced && !forcedChannelId.equals(route.getChannelId())) continue;
            if (route.getChannelId() == null) continue;
            Channel channel = channelMapper.selectById(route.getChannelId());
            if (channel == null) continue;
            // 强制测试单条路由时允许停用路由/渠道，测的就是这行配置本身
            if (!forced && Boolean.FALSE.equals(channel.getEnabled())) continue;
            byChannel.putIfAbsent(channel.getId(), new RouteCandidate(
                    route, channel,
                    weightOf(route.getWeight()) * weightOf(channel.getWeight()),
                    route.getPriority() == null ? 0 : route.getPriority(),
                    channel.getPriority() == null ? 0 : channel.getPriority()));
        }
        List<RouteCandidate> candidates = new ArrayList<>(byChannel.values());
        candidates.sort(Comparator
                .comparingInt(RouteCandidate::priority)
                .thenComparingInt(RouteCandidate::channelPriority));

        // 同一 (route.priority, channel.priority) 层内加权洗牌，层间保持优先级严格顺序
        List<RouteCandidate> ordered = new ArrayList<>(candidates.size());
        int start = 0;
        while (start < candidates.size()) {
            int end = start + 1;
            while (end < candidates.size()
                    && candidates.get(end).priority() == candidates.get(start).priority()
                    && candidates.get(end).channelPriority() == candidates.get(start).channelPriority()) {
                end++;
            }
            ordered.addAll(weightedShuffle(candidates.subList(start, end)));
            start = end;
        }
        return ordered;
    }

    private static List<RouteCandidate> weightedShuffle(List<RouteCandidate> tier) {
        List<RouteCandidate> pool = new ArrayList<>(tier);
        List<RouteCandidate> out = new ArrayList<>(pool.size());
        while (!pool.isEmpty()) {
            int total = pool.stream().mapToInt(RouteCandidate::weight).sum();
            int target = ThreadLocalRandom.current().nextInt(Math.max(1, total));
            int acc = 0;
            int picked = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                acc += pool.get(i).weight();
                if (target < acc) {
                    picked = i;
                    break;
                }
            }
            out.add(pool.remove(picked));
        }
        return out;
    }

    private static int weightOf(Integer weight) {
        return weight == null || weight < 1 ? 1 : weight;
    }

    /** 一条可尝试的「模型 × 渠道」部署。 */
    public record RouteCandidate(
            ModelRoute route,
            Channel channel,
            int weight,
            int priority,
            int channelPriority) {

        public String upstreamModel() {
            String upstream = route.getUpstreamModel();
            return upstream == null || upstream.isBlank() ? route.getModelName() : upstream.trim();
        }

        public int maxAttempts() {
            Integer retries = route.getMaxRetries() != null ? route.getMaxRetries() : channel.getMaxRetries();
            int value = retries == null ? 0 : retries;
            return 1 + Math.max(0, Math.min(3, value));
        }

        public Integer timeoutMs() {
            Integer routeTimeout = route.getTimeoutMs();
            return routeTimeout != null && routeTimeout > 0 ? routeTimeout : channel.getTimeoutMs();
        }

        public String modelName() {
            return route.getModelName();
        }

        public String type() {
            return route.getType() == null || route.getType().isBlank() ? "text" : route.getType();
        }
    }
}
