package com.llmgateway.service;

import com.llmgateway.support.InMemoryStringRedisTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 模型目录缓存回归：失效必须"删列表 + 换版本号"两件事都做，
 * 缺了版本号，跨服务的消费端就只能等 60s TTL 才看到新默认。
 */
class ModelCatalogCacheTest {

    private final InMemoryStringRedisTemplate redis = new InMemoryStringRedisTemplate();
    private final ModelCatalogCache cache = new ModelCatalogCache(redis);

    @Test
    void everyMutationChangesTheCatalogVersion() {
        String before = cache.currentVersion();
        cache.evict("image");

        String after = cache.currentVersion();
        assertThat(after).isNotNull();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void evictClearsBothTheTypeKeyAndTheAllKey() {
        cache.writeModels("image", "{\"data\":[\"a\"]}");
        cache.writeModels(null, "{\"data\":[\"a\"]}");

        cache.evict("image");

        assertThat(redis.deletedKeys())
                .contains(ModelCatalogCache.MODELS_PREFIX + "image", ModelCatalogCache.ALL_KEY);
        assertThat(cache.readModels("image")).isNull();
        assertThat(cache.readModels(null)).isNull();
    }

    @Test
    void evictAllClearsEveryKnownTypeWithoutScanningKeys() {
        for (String type : new String[]{"text", "image", "video", "vision"}) {
            cache.writeModels(type, "{\"data\":[]}");
        }
        cache.writeModels(null, "{\"data\":[]}");

        cache.evictAll();

        for (String type : new String[]{"text", "image", "video", "vision"}) {
            assertThat(cache.readModels(type)).as("type=%s", type).isNull();
        }
        assertThat(cache.readModels(null)).isNull();
    }

    @Test
    void defaultModelRoundTripAndClear() {
        cache.writeDefault("image", "doubao-seedream-4-0-250828");
        assertThat(cache.readDefault("image")).isEqualTo("doubao-seedream-4-0-250828");
        assertThat(redis.rawGet(ModelCatalogCache.DEFAULT_PREFIX + "image"))
                .isEqualTo("doubao-seedream-4-0-250828");

        cache.clearDefault("image");
        assertThat(cache.readDefault("image")).isNull();
    }

    @Test
    void redisFailureDegradesInsteadOfThrowing() {
        redis.failReads(true);

        assertThatCode(() -> {
            assertThat(cache.readModels("image")).isNull();
            assertThat(cache.readDefault("image")).isNull();
            assertThat(cache.currentVersion()).isNull();
            // 失效路径即使在"读不通"时也不能把异常抛给业务
            cache.evict("image");
            cache.evictAll();
        }).doesNotThrowAnyException();
    }
}
