package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llmgateway.dto.admin.RouteRequest;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import com.llmgateway.service.ModelRouteService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** 模型路由管理服务实现：模型名 → 渠道映射 CRUD + 默认模型 Redis 同步 */
@Service
@RequiredArgsConstructor
public class ModelRouteServiceImpl implements ModelRouteService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouteServiceImpl.class);

    /** Redis key 前缀：gateway:default_model:{type} → model_name */
    private static final String REDIS_KEY_PREFIX = "gateway:default_model:";

    /** 合法模型类型 */
    private static final Set<String> ROUTE_TYPES = Set.of("text", "image", "video", "vision");

    private final ModelRouteMapper routeMapper;
    private final ChannelMapper channelMapper;
    private final StringRedisTemplate redisTemplate;

    /** 启动时将所有默认路由同步到 Redis */
    @EventListener(ApplicationReadyEvent.class)
    public void warmRedisDefaults() {
        syncAllDefaultsToRedis();
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
        return route;
    }

    @Override
    public void delete(String id) {
        ModelRoute route = routeMapper.selectById(id);
        if (route == null) throw new BusinessException(40401, "路由不存在");
        routeMapper.deleteById(id);
        // 删除的是默认路由时，清理 Redis
        if (Boolean.TRUE.equals(route.getIsDefault())) {
            try { redisTemplate.delete(REDIS_KEY_PREFIX + route.getType()); } catch (Exception e) {
                log.warn("Redis 清理默认模型失败: type={}", route.getType());
            }
        }
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

    /** 清除同类型所有路由的默认标记 */
    private void clearDefaultForType(String type) {
        routeMapper.update(null, new LambdaUpdateWrapper<ModelRoute>()
                .eq(ModelRoute::getType, type)
                .eq(ModelRoute::getIsDefault, true)
                .set(ModelRoute::getIsDefault, false));
    }

    /** 同步单条到 Redis：isDefault=true 写入，false 删除 */
    private void syncDefaultToRedis(String type, Boolean isDefault, String modelName) {
        try {
            String key = REDIS_KEY_PREFIX + type;
            if (Boolean.TRUE.equals(isDefault)) {
                redisTemplate.opsForValue().set(key, modelName);
            } else {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("Redis 同步默认模型失败: type={}, model={}, error={}", type, modelName, e.getMessage());
        }
    }

    /** 启动时全量同步所有默认路由到 Redis */
    private void syncAllDefaultsToRedis() {
        try {
            List<ModelRoute> defaults = routeMapper.selectList(
                    new LambdaQueryWrapper<ModelRoute>().eq(ModelRoute::getIsDefault, true));
            for (ModelRoute r : defaults) {
                if (r.getType() != null && r.getModelName() != null) {
                    redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + r.getType(), r.getModelName());
                }
            }
            log.info("Redis 默认模型预热完成: {} 条", defaults.size());
        } catch (Exception e) {
            log.warn("Redis 默认模型预热失败（不影响启动）: {}", e.getMessage());
        }
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "text";
        String t = type.trim().toLowerCase();
        if (!ROUTE_TYPES.contains(t)) throw new BusinessException(40001, "type 仅支持 text/image/video/vision");
        return t;
    }
}
