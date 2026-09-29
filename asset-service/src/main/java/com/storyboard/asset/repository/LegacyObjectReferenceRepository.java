package com.storyboard.asset.repository;

import com.storyboard.asset.domain.ObjectKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * public schema 历史引用来源（旧画布 / 旧 AI 资产 / 旧分镜）的只读判定。
 * 表或列不存在视为该来源不存在；查询执行报错时向上抛出，由调用方整体 fail-safe。
 */
@Repository
public class LegacyObjectReferenceRepository {

    static final Duration PROBE_TTL = Duration.ofMinutes(5);

    static final String CANVAS_ASSETS_SQL = """
            SELECT DISTINCT object_key AS key
            FROM public.canvas_assets
            WHERE object_key IN (:keys)
            """;

    static final String ASSET_IMAGES_SQL = urlKeySql("public.asset_images", "url");
    static final String SCENE_REFERENCE_IMAGES_SQL =
            urlKeySql("public.scene_reference_images", "image_url");
    static final String SCENES_IMAGE_SQL = urlKeySql("public.scenes", "image_url");
    static final String SCENES_VIDEO_SQL = urlKeySql("public.scenes", "video_url");
    static final String AGENT_ASSET_URL_SQL = urlKeySql("public.agent_assets", "url");
    static final String AGENT_RESULT_URLS_SQL = """
            SELECT DISTINCT result_urls
            FROM public.agent_assets
            WHERE result_urls LIKE '%images/%' OR result_urls LIKE '%videos/%'
            """;

    private final JdbcClient jdbc;
    private final Map<String, Boolean> probeResults = new ConcurrentHashMap<>();
    private final Map<String, Instant> probeTimes = new ConcurrentHashMap<>();

    public LegacyObjectReferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回 candidates 中仍被 public schema 历史来源引用的子集。 */
    public Set<String> referencedKeys(Collection<String> candidates) {
        List<String> keys = candidates == null ? List.of() : candidates.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) return Set.of();

        Set<String> referenced = new LinkedHashSet<>();
        if (sourceExists("public.canvas_assets", "object_key")) {
            referenced.addAll(queryKeys(CANVAS_ASSETS_SQL, keys));
        }
        if (sourceExists("public.asset_images", "url")) {
            referenced.addAll(queryKeys(ASSET_IMAGES_SQL, keys));
        }
        if (sourceExists("public.scene_reference_images", "image_url")) {
            referenced.addAll(queryKeys(SCENE_REFERENCE_IMAGES_SQL, keys));
        }
        if (sourceExists("public.scenes", "image_url")) {
            referenced.addAll(queryKeys(SCENES_IMAGE_SQL, keys));
        }
        if (sourceExists("public.scenes", "video_url")) {
            referenced.addAll(queryKeys(SCENES_VIDEO_SQL, keys));
        }
        if (sourceExists("public.agent_assets", "url")) {
            referenced.addAll(queryKeys(AGENT_ASSET_URL_SQL, keys));
        }
        if (sourceExists("public.agent_assets", "result_urls")) {
            Set<String> fromJson = new LinkedHashSet<>();
            for (String json : jdbc.sql(AGENT_RESULT_URLS_SQL).query(String.class).list()) {
                fromJson.addAll(ObjectKeys.fromJsonArray(json));
            }
            fromJson.retainAll(keys);
            referenced.addAll(fromJson);
        }
        return referenced;
    }

    private Set<String> queryKeys(String sql, List<String> keys) {
        return new LinkedHashSet<>(jdbc.sql(sql).param("keys", keys).query(String.class).list());
    }

    private boolean sourceExists(String qualifiedTable, String column) {
        String cacheKey = qualifiedTable + "." + column;
        Instant checkedAt = probeTimes.get(cacheKey);
        if (checkedAt != null && Duration.between(checkedAt, Instant.now()).compareTo(PROBE_TTL) < 0) {
            return Boolean.TRUE.equals(probeResults.get(cacheKey));
        }
        String[] parts = qualifiedTable.split("\\.", 2);
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = :schema
                              AND table_name = :table
                              AND column_name = :column
                        )
                        """)
                .param("schema", parts[0])
                .param("table", parts.length > 1 ? parts[1] : parts[0])
                .param("column", column)
                .query(Boolean.class)
                .single();
        probeResults.put(cacheKey, Boolean.TRUE.equals(exists));
        probeTimes.put(cacheKey, Instant.now());
        return Boolean.TRUE.equals(exists);
    }

    private static String urlKeySql(String table, String column) {
        return """
                SELECT key FROM (
                    SELECT DISTINCT split_part(regexp_replace(%s, '^.*/api/files/', ''), '?', 1) AS key
                    FROM %s
                    WHERE %s IS NOT NULL
                ) normalized
                WHERE normalized.key IN (:keys)
                """.formatted(column, table, column);
    }
}