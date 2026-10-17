package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llmgateway.dto.admin.RouteRequest;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.ModelCatalogCache;
import com.llmgateway.service.ModelRouteService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 模型路由管理服务实现：模型名 → 渠道映射 CRUD + 默认模型 Redis 同步 */
@Service
@RequiredArgsConstructor
public class ModelRouteServiceImpl implements ModelRouteService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouteServiceImpl.class);

    /** 合法模型类型 */
    private static final Set<String> ROUTE_TYPES = Set.of("text", "image", "video", "vision");

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final ModelParamsMapper modelParamsMapper;
    /** 模型列表缓存 / 默认模型 / 版本号的唯一持有者 */
    private final ModelCatalogCache catalogCache;

    /**
     * 启动时收敛默认模型：把「路由表 → Redis 运行值」对齐（Redis 值优先，不收敛就会被上次运行的
     * 陈旧值劫持，例如手工 SQL 改了 model_route.is_default 却没清 Redis），并把参数表的
     * 「默认」标记对齐到路由默认。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmRedisDefaults() {
        reconcileDefaultsToRedis();
        reconcileParamsDefaults();
    }

    @Override
    public ModelRoute create(RouteRequest request) {
        if (request.getModelName() == null || request.getModelName().isBlank()
                || request.getChannelId() == null || request.getChannelId().isBlank()) {
            throw new BusinessException(40001, "modelName/channelId 不能为空");
        }
        if (channelMapper.selectById(request.getChannelId()) == null) {
            throw new BusinessException(40401, "渠道不存在");
        }
        String type = normalizeType(request.getType());

        // 设为默认时，先清掉同类型其它路由的默认标记
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefaultForType(type);
        }

        ModelRoute route = new ModelRoute();
        route.setModelName(request.getModelName().trim());
        route.setChannelId(request.getChannelId());
        route.setType(type);
        route.setDefaultParams(request.getDefaultParams());
        route.setIsDefault(Boolean.TRUE.equals(request.getIsDefault()));
        route.setCreatedAt(OffsetDateTime.now());
        route.setUpdatedAt(OffsetDateTime.now());
        routeMapper.insert(route);

        // 同步 Redis
        syncDefaultToRedis(type, route.getIsDefault(), route.getModelName());
        // 清除模型列表缓存，确保前端立即获取最新列表
        catalogCache.evict(type);
        return route;
    }

    @Override
    public List<ModelRoute> list() {
        return routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .orderByAsc(ModelRoute::getModelName));
    }

    @Override
    public ModelRoute update(String id, RouteRequest request) {
        ModelRoute route = routeMapper.selectById(id);
        if (route == null) throw new BusinessException(40401, "路由不存在");
        // 记住改 type 前的旧类型：旧类型的列表缓存里还有这条路由，只清新类型会残留到 TTL 过期
        String previousType = route.getType();
        if (request.getModelName() != null) route.setModelName(request.getModelName().trim());
        if (request.getChannelId() != null) {
            if (channelMapper.selectById(request.getChannelId()) == null) {
                throw new BusinessException(40401, "渠道不存在");
            }
            route.setChannelId(request.getChannelId());
        }
        if (request.getDefaultParams() != null) route.setDefaultParams(request.getDefaultParams());
        if (request.getType() != null) route.setType(normalizeType(request.getType()));

        // isDefault 变更处理
        if (request.getIsDefault() != null) {
            if (Boolean.TRUE.equals(request.getIsDefault())) {
                clearDefaultForType(route.getType());
            }
            route.setIsDefault(request.getIsDefault());
        }

        route.setUpdatedAt(OffsetDateTime.now());
        routeMapper.updateById(route);

        // 同步 Redis
        syncDefaultToRedis(route.getType(), route.getIsDefault(), route.getModelName());
        // 清除模型列表缓存，确保前端立即获取最新列表
        catalogCache.evict(route.getType());
        if (previousType != null && !previousType.equals(route.getType())) {
            // type 改了：旧类型的默认若指向这条模型，需要按旧类型重算，并清掉旧类型的列表缓存
            reconcileDefault(previousType);
            catalogCache.evict(previousType);
        }
        return route;
    }

    @Override
    public void delete(String id) {
        ModelRoute route = routeMapper.selectById(id);
        if (route == null) throw new BusinessException(40401, "路由不存在");
        routeMapper.deleteById(id);
        // 删除的是默认路由时重算该类型的默认（直接删 key 会让 Redis 快路径消失，
        // 也让「实际生效的默认」与库标记短暂不一致）
        if (Boolean.TRUE.equals(route.getIsDefault())) {
            reconcileDefault(route.getType());
        }
        // 清除模型列表缓存
        catalogCache.evict(route.getType());
    }

    @Override
    public ModelRoute getById(String id) {
        ModelRoute route = routeMapper.selectById(id);
        if (route == null) throw new BusinessException(40401, "路由不存在");
        return route;
    }

    @Override
    public List<ModelRoute> listByChannelId(String channelId) {
        return routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .eq(ModelRoute::getChannelId, channelId)
                .orderByAsc(ModelRoute::getModelName));
    }

    @Override
    public long countAll() {
        Long count = routeMapper.selectCount(null);
        return count == null ? 0 : count;
    }

    @Override
    public boolean setDefaultRoute(String type, String modelName, boolean isDefault) {
        if (type == null || type.isBlank() || modelName == null || modelName.isBlank()) return false;
        String normalizedType = normalizeType(type);
        String trimmed = modelName.trim();
        List<ModelRoute> matches = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .eq(ModelRoute::getType, normalizedType)
                .eq(ModelRoute::getModelName, trimmed));
        if (matches.isEmpty()) return false;

        if (isDefault) {
            clearDefaultForType(normalizedType);
            for (ModelRoute route : matches) {
                route.setIsDefault(true);
                route.setUpdatedAt(OffsetDateTime.now());
                routeMapper.updateById(route);
            }
        } else {
            for (ModelRoute route : matches) {
                if (!Boolean.TRUE.equals(route.getIsDefault())) continue;
                route.setIsDefault(false);
                route.setUpdatedAt(OffsetDateTime.now());
                routeMapper.updateById(route);
            }
        }
        syncDefaultToRedis(normalizedType, isDefault, trimmed);
        catalogCache.evict(normalizedType);
        return true;
    }

    /** 清除同类型所有路由的默认标记 */
    private void clearDefaultForType(String type) {
        routeMapper.update(null, new LambdaUpdateWrapper<ModelRoute>()
                .eq(ModelRoute::getType, type)
                .eq(ModelRoute::getIsDefault, true)
                .set(ModelRoute::getIsDefault, false));
    }

    /**
     * 同步单条到 Redis：置真就直接写入；置假则从路由表<b>重算</b>该类型的默认，
     * 而不是无条件删除——「关掉某个非默认模型的开关」不应该把真正默认模型的快路径也删掉。
     */
    private void syncDefaultToRedis(String type, Boolean isDefault, String modelName) {
        if (Boolean.TRUE.equals(isDefault)) {
            catalogCache.writeDefault(type, modelName);
        } else {
            reconcileDefault(type);
        }
    }

    /**
     * 把某个类型的运行时默认模型重算并写回 Redis。
     *
     * @return 是否发生了变化（调用方据此决定要不要顺带清列表缓存）
     */
    public boolean reconcileDefault(String type) {
        if (type == null || type.isBlank()) return false;
        try {
            String current = catalogCache.readDefault(type);
            String effective = resolveEffectiveDefaultFromDb(type);
            if (Objects.equals(current, effective)) return false;
            if (effective == null) {
                catalogCache.clearDefault(type);
            } else {
                catalogCache.writeDefault(type, effective);
            }
            log.info("默认模型已收敛: type={}, {} → {}", type, current, effective);
            return true;
        } catch (Exception e) {
            log.warn("默认模型收敛失败（不影响业务）: type={}, error={}", type, e.getMessage());
            return false;
        }
    }

    /**
     * 从 model_route（is_default=true）推导某类型真正可用的默认模型：渠道被禁用的路由不能作为默认，
     * 否则服务端会拿一个没有可用渠道的模型当默认，每次生成都撞 50301。
     */
    private String resolveEffectiveDefaultFromDb(String type) {
        List<ModelRoute> defaults = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .eq(ModelRoute::getType, type)
                .eq(ModelRoute::getIsDefault, true));
        if (defaults == null) return null;
        for (ModelRoute route : defaults) {
            if (route.getModelName() == null || route.getModelName().isBlank()) continue;
            if (!channelEnabled(route.getChannelId())) continue;
            return route.getModelName();
        }
        return null;
    }

    private boolean channelEnabled(String channelId) {
        if (channelId == null || channelId.isBlank()) return false;
        Channel channel = channelMapper.selectById(channelId);
        return channel != null && (channel.getEnabled() == null || channel.getEnabled());
    }

    /**
     * 全量收敛所有类型的默认模型：启动预热与定时任务共用。
     *
     * <p>定时跑的意义：手工 SQL（例如 V19 那类迁移）改了 {@code model_route.is_default} 却忘了清
     * {@code gateway:default_model:*} 时，Redis 的陈旧值会优先于库标记生效（列表标记与实际生成
     * 都会继续用旧默认），过去只能重启网关才恢复；现在 ≤ 一个周期自动收敛。
     */
    @Scheduled(fixedDelayString = "${gateway.model-catalog.reconcile-interval-ms:300000}",
            initialDelayString = "${gateway.model-catalog.reconcile-interval-ms:300000}")
    public void reconcileDefaultsToRedis() {
        Set<String> types = new LinkedHashSet<>(ROUTE_TYPES);
        try {
            List<ModelRoute> routes = routeMapper.selectList(null);
            if (routes != null) {
                routes.stream().map(ModelRoute::getType).filter(Objects::nonNull).forEach(types::add);
            }
        } catch (Exception e) {
            log.warn("读取路由表失败，仅收敛已知类型: {}", e.getMessage());
        }
        for (String type : types) {
            // 默认变了要连带清列表缓存：/v1/models 里的 is_default 标记来自同一份数据
            if (reconcileDefault(type)) catalogCache.evict(type);
        }
    }

    /**
     * 让 model_params.is_default 跟随路由默认：同一类型下只有默认模型标记为 true。
     * 参数表的这个标记只服务于后台展示，不再决定 /v1/models 的默认；这里保证两者一致。
     */
    private void reconcileParamsDefaults() {
        try {
            List<ModelRoute> defaults = routeMapper.selectList(
                    new LambdaQueryWrapper<ModelRoute>().eq(ModelRoute::getIsDefault, true));
            Map<String, String> defaultByType = new LinkedHashMap<>();
            for (ModelRoute route : defaults) {
                if (route.getType() != null && route.getModelName() != null) {
                    defaultByType.putIfAbsent(route.getType(), route.getModelName());
                }
            }
            for (String type : ROUTE_TYPES) {
                String model = defaultByType.get(type);
                modelParamsMapper.update(null, new LambdaUpdateWrapper<ModelParams>()
                        .eq(ModelParams::getType, type)
                        .eq(ModelParams::getIsDefault, true)
                        .ne(model != null, ModelParams::getModelName, model)
                        .set(ModelParams::getIsDefault, false));
                if (model == null) continue;
                modelParamsMapper.update(null, new LambdaUpdateWrapper<ModelParams>()
                        .eq(ModelParams::getType, type)
                        .eq(ModelParams::getModelName, model)
                        .set(ModelParams::getIsDefault, true));
            }
            log.info("模型参数默认标记已对齐路由默认: {}", defaultByType);
        } catch (Exception e) {
            log.warn("模型参数默认标记对齐失败（不影响启动）: {}", e.getMessage());
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "text";
        String t = type.trim().toLowerCase();
        if (!ROUTE_TYPES.contains(t)) throw new BusinessException(40001, "type 仅支持 text/image/video/vision");
        return t;
    }
}
