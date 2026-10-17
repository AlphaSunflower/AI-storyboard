package com.llmgateway.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 模型目录缓存：网关内唯一持有 Redis key 命名、读写与失效逻辑的组件。
 *
 * <p>两类 key：
 * <ul>
 *   <li>{@code gateway:models:{type|all}} → {@code /v1/models} 的 JSON 响应（短 TTL，缓解三表全扫）；</li>
 *   <li>{@code gateway:default_model:{type}} → 运行时默认模型名，被服务端生成链路
 *       （agent-core {@code GatewayModelServiceImpl}）直接读取，是「切换默认模型立即生效」的快路径；</li>
 *   <li>{@code gateway:models:version} → 目录版本号，任何一次变更都换新值，
 *       消费端（主后端 / MoonAgent / 画布）据此毫秒级失效各自的本地缓存，不必等 60s TTL。</li>
 * </ul>
 *
 * <p>为什么用随机值而不是自增计数做版本号：Redis 是 {@code allkeys-lru} 淘汰策略，
 * 计数键被淘汰后重建会回到小数字，消费端可能拿到与旧缓存相同的版本号而继续用陈旧列表；
 * 随机值不存在这种碰撞。版本号缺失时消费端按「未知」处理并回退 60s TTL，不会造成请求风暴。
 *
 * <p>所有方法都不抛异常：Redis 不可用时静默降级（读返回 null → 调用方查库；写失败靠 TTL 自愈）。
 */
@Component
@RequiredArgsConstructor
public class ModelCatalogCache {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogCache.class);

    /** 模型列表缓存 key 前缀：gateway:models:{type|all} */
    public static final String MODELS_PREFIX = "gateway:models:";

    /** 不带 type 的列表（「全部模型」）缓存 key */
    public static final String ALL_KEY = MODELS_PREFIX + "all";

    /** 目录版本号 key：每次目录变更换新值，供跨服务失效 */
    public static final String VERSION_KEY = MODELS_PREFIX + "version";

    /** 运行时默认模型 key 前缀：gateway:default_model:{type} */
    public static final String DEFAULT_PREFIX = "gateway:default_model:";

    /**
     * 模型列表缓存 TTL：只作兜底 max-age（正常失效走版本号）。
     * 保持 60s 是为了「版本号缺失/被淘汰」时最坏表现与历史一致，不让任何路径比改造前更陈旧。
     */
    public static final Duration MODELS_TTL = Duration.ofSeconds(60);

    /**
     * 路由类型枚举（model_route.type 的合法取值）。
     * 全量失效时逐个删 key，避免在生产 Redis 上执行 KEYS 全量扫描。
     */
    private static final List<String> KNOWN_TYPES = List.of("text", "image", "video", "vision");

    private final StringRedisTemplate redisTemplate;

    /** 列表缓存 key：type 为空视为「全部」 */
    public static String modelsKey(String type) {
        return type == null || type.isBlank() ? ALL_KEY : MODELS_PREFIX + type;
    }

    /** 默认模型 key */
    public static String defaultKey(String type) {
        return DEFAULT_PREFIX + type;
    }

    /** 读列表缓存；未命中或 Redis 异常返回 null（调用方降级查库） */
    public String readModels(String type) {
        try {
            return redisTemplate.opsForValue().get(modelsKey(type));
        } catch (Exception e) {
            log.warn("Redis 读模型列表失败，降级查库: type={}, error={}", type, e.getMessage());
            return null;
        }
    }

    /** 写列表缓存；失败忽略（靠 TTL 自愈） */
    public void writeModels(String type, String json) {
        try {
            redisTemplate.opsForValue().set(modelsKey(type), json, MODELS_TTL);
        } catch (Exception e) {
            log.warn("Redis 写模型列表缓存失败（忽略，靠 TTL 自愈）: type={}, error={}", type, e.getMessage());
        }
    }

    /** 读运行时默认模型；缺失或 Redis 异常返回 null（调用方回落库标记/HTTP） */
    public String readDefault(String type) {
        try {
            String value = redisTemplate.opsForValue().get(defaultKey(type));
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            log.warn("Redis 读默认模型失败，回落路由表标记: type={}, error={}", type, e.getMessage());
            return null;
        }
    }

    /** 写运行时默认模型（admin 切换默认 / 启动与定时收敛都走这里） */
    public void writeDefault(String type, String model) {
        try {
            redisTemplate.opsForValue().set(defaultKey(type), model);
        } catch (Exception e) {
            log.warn("Redis 同步默认模型失败: type={}, model={}, error={}", type, model, e.getMessage());
        }
    }

    /** 清运行时默认模型（该类型确实没有可用默认时才调用，不要用它表达「关掉某个模型的开关」） */
    public void clearDefault(String type) {
        try {
            redisTemplate.delete(defaultKey(type));
        } catch (Exception e) {
            log.warn("Redis 清理默认模型失败: type={}, error={}", type, e.getMessage());
        }
    }

    /** 当前目录版本号；缺失（尚无变更 / 被 LRU 淘汰）或 Redis 异常返回 null */
    public String currentVersion() {
        try {
            return redisTemplate.opsForValue().get(VERSION_KEY);
        } catch (Exception e) {
            log.debug("Redis 读模型目录版本号失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 指定类型的目录发生变化：清列表缓存 + 换版本号。
     * 除列表内容外，<b>默认模型标记</b>也随列表下发，因此默认变更同样要调这里。
     */
    public void evict(String type) {
        try {
            redisTemplate.delete(modelsKey(type));
            if (!ALL_KEY.equals(modelsKey(type))) redisTemplate.delete(ALL_KEY);
        } catch (Exception e) {
            log.warn("清除模型列表缓存失败（不影响业务）: type={}, error={}", type, e.getMessage());
        }
        bumpVersion();
    }

    /** 渠道等跨类型变更：清全部类型的列表缓存 + 换版本号 */
    public void evictAll() {
        try {
            redisTemplate.delete(ALL_KEY);
            for (String type : KNOWN_TYPES) {
                redisTemplate.delete(MODELS_PREFIX + type);
            }
        } catch (Exception e) {
            log.warn("清除全部模型列表缓存失败（不影响业务）: {}", e.getMessage());
        }
        bumpVersion();
    }

    /** 换一个新的目录版本号（随机值，避免淘汰重建后的版本号碰撞） */
    private void bumpVersion() {
        try {
            redisTemplate.opsForValue().set(VERSION_KEY, UUID.randomUUID().toString());
        } catch (Exception e) {
            log.warn("写模型目录版本号失败（消费端退化为 60s TTL 失效）: {}", e.getMessage());
        }
    }
}
