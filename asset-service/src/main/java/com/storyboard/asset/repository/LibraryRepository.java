package com.storyboard.asset.repository;

import com.storyboard.asset.client.AssetContracts;
import com.storyboard.asset.client.AssetContracts.AiAssetKind;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.MetadataState;
import com.storyboard.asset.client.AssetContracts.LibraryFolderNode;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.ProjectLinkRef;
import com.storyboard.asset.client.AssetContracts.SnapshotItem;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.TagRef;
import com.storyboard.asset.domain.AssetSearchText;
import com.storyboard.asset.domain.GroupSnapshotExpander;
import com.storyboard.asset.dto.ImportContracts.AnalysisTaskStatus;
import com.storyboard.asset.dto.ImportContracts.ImportMode;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** asset schema 的唯一数据访问层。 */
@Repository
public class LibraryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcClient client;

    public LibraryRepository(NamedParameterJdbcTemplate jdbc, JdbcClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public Optional<AssetSummary> findAsset(String userId, String assetId) {
        return client.sql("""
                        SELECT a.*, %s AS metadata_state
                        FROM asset.library_assets a
                        LEFT JOIN asset.library_analysis_tasks t ON t.asset_id = a.id
                        WHERE a.id = :id AND a.owner_user_id = :userId
                        """.formatted(metadataStateSql()))
                .param("id", assetId).param("userId", userId)
                .query(assetRowMapper())
                .optional()
                .map(this::hydrate);
    }

    public Optional<AssetSummary> findAssetInternal(String assetId) {
        return client.sql("""
                        SELECT a.*, %s AS metadata_state
                        FROM asset.library_assets a
                        LEFT JOIN asset.library_analysis_tasks t ON t.asset_id = a.id
                        WHERE a.id = :id
                        """.formatted(metadataStateSql()))
                .param("id", assetId)
                .query(assetRowMapper())
                .optional()
                .map(this::hydrate);
    }

    public Optional<AssetSummary> findAssetBySource(String userId, String sourceType, String sourceId) {
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()) {
            return Optional.empty();
        }
        return client.sql("""
                        SELECT a.*, %s AS metadata_state
                        FROM asset.library_assets a
                        LEFT JOIN asset.library_analysis_tasks t ON t.asset_id = a.id
                        WHERE a.owner_user_id = :userId
                          AND a.source_type = :sourceType
                          AND a.source_id = :sourceId
                          AND a.status = 'ACTIVE'
                        ORDER BY a.created_at
                        LIMIT 1
                        """.formatted(metadataStateSql()))
                .param("userId", userId)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .query(assetRowMapper())
                .optional()
                .map(this::hydrate);
    }

    public PageResponse<AssetSummary> searchAssets(
            String userId,
            AssetScope scope,
            String projectId,
            String folderId,
            String query,
            AssetType type,
            Collection<String> tagIds,
            TagMode tagMode,
            boolean deleted,
            int page,
            int size
    ) {
        StringBuilder where = new StringBuilder(" a.owner_user_id = :userId ");
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        if (deleted) {
            where.append(" AND a.status <> 'ACTIVE' AND a.status <> 'PURGED' ");
        } else {
            where.append(" AND a.status = 'ACTIVE' ");
        }
        if (scope == AssetScope.GLOBAL) {
            where.append(" AND a.scope = 'GLOBAL' ");
        } else if (scope == AssetScope.PROJECT) {
            if (projectId == null || projectId.isBlank()) {
                where.append(" AND 1 = 0 ");
            } else {
                where.append(" AND (a.owner_project_id = :projectId OR EXISTS (")
                        .append(" SELECT 1 FROM asset.library_project_links l WHERE l.asset_id = a.id AND l.project_id = :projectId")
                        .append(")) ");
                params.addValue("projectId", projectId);
            }
        }
        if (type != null) {
            where.append(" AND a.asset_type = :type ");
            params.addValue("type", type.name());
        }
        if (folderId != null && !folderId.isBlank()) {
            if ("UNFILED".equals(folderId)) {
                where.append(" AND NOT EXISTS (SELECT 1 FROM asset.library_asset_folders af ")
                        .append("WHERE af.asset_id = a.id AND af.owner_user_id = :userId ")
                        .append("AND af.scope = COALESCE(:folderScope, a.scope) ")
                        .append("AND af.owner_project_id IS NOT DISTINCT FROM ")
                        .append("(CASE WHEN COALESCE(:folderScope, a.scope) = 'PROJECT' THEN :folderProjectId ELSE NULL END)) ");
            } else {
                where.append(" AND EXISTS (SELECT 1 FROM asset.library_asset_folders af ")
                        .append("WHERE af.asset_id = a.id AND af.folder_id = :folderId ")
                        .append("AND af.owner_user_id = :userId) ");
                params.addValue("folderId", folderId);
            }
            params.addValue("folderScope", scope == null ? null : scope.name());
            params.addValue("folderProjectId", scope == AssetScope.PROJECT ? projectId : null);
        }
        List<String> terms = query == null ? List.of() : java.util.Arrays.stream(query.trim().split("\\s+"))
                .filter(term -> !term.isBlank()).limit(12).toList();
        for (int i = 0; i < terms.size(); i++) {
            where.append(" AND a.search_text ILIKE :term").append(i).append(" ");
            params.addValue("term" + i, "%" + terms.get(i).toLowerCase(Locale.ROOT) + "%");
        }
        List<String> normalizedTagIds = tagIds == null ? List.of() : tagIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (!normalizedTagIds.isEmpty()) {
            if (tagMode == TagMode.ANY) {
                where.append(" AND EXISTS (SELECT 1 FROM asset.library_asset_tags lt ")
                        .append("WHERE lt.asset_id = a.id AND lt.tag_id IN (:tagIds)) ");
            } else {
                where.append(" AND (SELECT count(DISTINCT lt.tag_id) FROM asset.library_asset_tags lt ")
                        .append("WHERE lt.asset_id = a.id AND lt.tag_id IN (:tagIds)) = :tagCount ");
                params.addValue("tagCount", normalizedTagIds.size());
            }
            params.addValue("tagIds", normalizedTagIds);
        }

        long total = jdbc.queryForObject("SELECT count(*) FROM asset.library_assets a WHERE " + where, params, Long.class);
        params.addValue("limit", Math.min(100, Math.max(1, size)));
        params.addValue("offset", (Math.max(1, page) - 1L) * Math.min(100, Math.max(1, size)));
        List<AssetSummary> records = jdbc.query("""
                        SELECT a.*, %s AS metadata_state
                        FROM asset.library_assets a
                        LEFT JOIN asset.library_analysis_tasks t ON t.asset_id = a.id
                        WHERE %s
                        ORDER BY a.updated_at DESC
                        LIMIT :limit OFFSET :offset
                        """.formatted(metadataStateSql(), where), params, assetRowMapper())
                .stream().map(row -> hydrate(row, scope, projectId)).toList();
        return new PageResponse<>(records, total, Math.max(1, page), Math.min(100, Math.max(1, size)));
    }

    public PageResponse<AssetSummary> searchAssets(
            String userId,
            AssetScope scope,
            String projectId,
            String query,
            AssetType type,
            Collection<String> tagIds,
            TagMode tagMode,
            boolean deleted,
            int page,
            int size
    ) {
        return searchAssets(userId, scope, projectId, null, query, type, tagIds, tagMode, deleted, page, size);
    }

    public String insertAsset(
            String ownerUserId,
            AssetScope scope,
            String projectId,
            AssetType type,
            AiAssetKind aiKind,
            String name,
            String description,
            String sourceType,
            String sourceId
    ) {
        String searchText = AssetSearchText.build(name, description, null, List.of());
        return client.sql("""
                        INSERT INTO asset.library_assets (
                            owner_user_id, scope, owner_project_id, asset_type, ai_kind,
                            name, description, source_type, source_id, search_text
                        ) VALUES (
                            :ownerUserId, :scope, :projectId, :assetType, :aiKind,
                            :name, :description, :sourceType, :sourceId, :searchText
                        ) RETURNING id
                        """)
                .param("ownerUserId", ownerUserId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .param("assetType", type.name())
                .param("aiKind", aiKind == null ? null : aiKind.name())
                .param("name", name)
                .param("description", description)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("searchText", searchText)
                .query(String.class)
                .single();
    }

    /** 迁移旧资产时保留原 ID，保证既有 scene_assets 与素材组 ID 继续有效。 */
    public String insertAssetWithId(
            String id,
            String ownerUserId,
            AssetScope scope,
            String projectId,
            AssetType type,
            AiAssetKind aiKind,
            String name,
            String description,
            String sourceType,
            String sourceId
    ) {
        String searchText = AssetSearchText.build(name, description, null, List.of());
        return client.sql("""
                        INSERT INTO asset.library_assets (
                            id, owner_user_id, scope, owner_project_id, asset_type, ai_kind,
                            name, description, source_type, source_id, search_text
                        ) VALUES (
                            :id, :ownerUserId, :scope, :projectId, :assetType, :aiKind,
                            :name, :description, :sourceType, :sourceId, :searchText
                        )
                        ON CONFLICT (id) DO NOTHING
                        RETURNING id
                        """)
                .param("id", id)
                .param("ownerUserId", ownerUserId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .param("assetType", type.name())
                .param("aiKind", aiKind == null ? null : aiKind.name())
                .param("name", name)
                .param("description", description)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("searchText", searchText)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    public String duplicateAsset(AssetSummary source, String name, String searchText) {
        return client.sql("""
                        INSERT INTO asset.library_assets (
                            owner_user_id, scope, owner_project_id, asset_type, ai_kind,
                            name, description, ai_description, manual_override, status,
                            source_type, source_id, cover_url, search_text
                        ) VALUES (
                            :ownerUserId, :scope, :projectId, :assetType, :aiKind,
                            :name, :description, :aiDescription, :manualOverride, 'ACTIVE',
                            :sourceType, NULL, :coverUrl, :searchText
                        ) RETURNING id
                        """)
                .param("ownerUserId", source.ownerUserId())
                .param("scope", source.scope().name())
                .param("projectId", source.scope() == AssetScope.PROJECT ? source.ownerProjectId() : null)
                .param("assetType", source.type().name())
                .param("aiKind", source.aiKind() == null ? null : source.aiKind().name())
                .param("name", name)
                .param("description", source.description())
                .param("aiDescription", source.aiDescription())
                .param("manualOverride", source.manualOverride())
                .param("sourceType", source.sourceType())
                .param("coverUrl", source.coverUrl())
                .param("searchText", searchText)
                .query(String.class)
                .single();
    }

    public void copyMediaLinks(String sourceAssetId, String targetAssetId) {
        client.sql("""
                        INSERT INTO asset.library_media(
                            asset_id, blob_id, sort_order, role, is_primary, constraint_text, status
                        )
                        SELECT :targetAssetId, blob_id, sort_order, role, is_primary, constraint_text, status
                        FROM asset.library_media
                        WHERE asset_id = :sourceAssetId
                        ON CONFLICT (asset_id, blob_id, sort_order) DO NOTHING
                        """)
                .param("sourceAssetId", sourceAssetId)
                .param("targetAssetId", targetAssetId)
                .update();
    }

    public void copyAssetTags(String sourceAssetId, String targetAssetId) {
        client.sql("""
                        INSERT INTO asset.library_asset_tags(asset_id, tag_id, source)
                        SELECT :targetAssetId, tag_id, source
                        FROM asset.library_asset_tags
                        WHERE asset_id = :sourceAssetId
                        ON CONFLICT (asset_id, tag_id) DO NOTHING
                        """)
                .param("sourceAssetId", sourceAssetId)
                .param("targetAssetId", targetAssetId)
                .update();
    }

    public void copyProjectLinks(String sourceAssetId, String targetAssetId, String userId) {
        client.sql("""
                        INSERT INTO asset.library_project_links(asset_id, project_id, added_by_user_id)
                        SELECT :targetAssetId, project_id, :userId
                        FROM asset.library_project_links
                        WHERE asset_id = :sourceAssetId
                        ON CONFLICT (asset_id, project_id) DO NOTHING
                        """)
                .param("sourceAssetId", sourceAssetId)
                .param("targetAssetId", targetAssetId)
                .param("userId", userId)
                .update();
    }

    public void duplicateGroup(String sourceGroupId, String targetGroupId) {
        client.sql("""
                        INSERT INTO asset.library_groups(
                            id, asset_id, owner_user_id, scope, owner_project_id
                        )
                        SELECT :targetGroupId, :targetGroupId, owner_user_id, scope, owner_project_id
                        FROM asset.library_groups
                        WHERE asset_id = :sourceGroupId
                        """)
                .param("sourceGroupId", sourceGroupId)
                .param("targetGroupId", targetGroupId)
                .update();
        client.sql("""
                        INSERT INTO asset.library_group_items(
                            group_id, media_id, member_asset_id, sort_order, role
                        )
                        SELECT :targetGroupId, media_id, member_asset_id, sort_order, role
                        FROM asset.library_group_items
                        WHERE group_id = :sourceGroupId
                        ON CONFLICT (group_id, media_id) DO NOTHING
                        """)
                .param("sourceGroupId", sourceGroupId)
                .param("targetGroupId", targetGroupId)
                .update();
    }

    public boolean updateAsset(
            String userId,
            String assetId,
            String name,
            String description,
            String aiDescription,
            boolean manualOverride,
            long expectedVersion
    ) {
        AssetSummary current = findAsset(userId, assetId).orElseThrow();
        List<String> tags = listTags(assetId).stream().map(TagRef::name).toList();
        String searchText = AssetSearchText.build(
                name == null ? current.name() : name,
                description == null ? current.description() : description,
                aiDescription == null ? current.aiDescription() : aiDescription,
                tags
        );
        int updated = client.sql("""
                        UPDATE asset.library_assets
                        SET name = COALESCE(:name, name),
                            description = COALESCE(:description, description),
                            ai_description = COALESCE(:aiDescription, ai_description),
                            manual_override = manual_override OR :manualOverride,
                            search_text = :searchText,
                            version = version + 1,
                            updated_at = now()
                        WHERE id = :id AND owner_user_id = :userId AND version = :expectedVersion
                        """)
                .param("name", name)
                .param("description", description)
                .param("aiDescription", aiDescription)
                .param("manualOverride", manualOverride)
                .param("searchText", searchText)
                .param("id", assetId)
                .param("userId", userId)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    public boolean promote(String userId, String assetId, long expectedVersion) {
        return client.sql("""
                        UPDATE asset.library_assets
                        SET scope = 'GLOBAL', owner_project_id = NULL, version = version + 1, updated_at = now()
                        WHERE id = :id AND owner_user_id = :userId AND version = :version AND status = 'ACTIVE'
                        """)
                .param("id", assetId).param("userId", userId).param("version", expectedVersion)
                .update() == 1;
    }

    public void linkProject(String userId, String assetId, String projectId) {
        client.sql("""
                        INSERT INTO asset.library_project_links(asset_id, project_id, added_by_user_id)
                        VALUES (:assetId, :projectId, :userId)
                        ON CONFLICT (asset_id, project_id) DO NOTHING
                        """)
                .param("assetId", assetId).param("projectId", projectId).param("userId", userId)
                .update();
    }

    public void unlinkAllProjects(String assetId) {
        client.sql("""
                        DELETE FROM asset.library_project_links
                        WHERE asset_id = :assetId
                        """)
                .param("assetId", assetId)
                .update();
    }

    public boolean trash(String userId, String assetId, long expectedVersion, OffsetDateTime purgeAfter) {
        return client.sql("""
                        UPDATE asset.library_assets
                        SET status = 'TRASHED', deleted_at = now(), purge_after = :purgeAfter,
                            version = version + 1, updated_at = now()
                        WHERE id = :id AND owner_user_id = :userId AND version = :version AND status = 'ACTIVE'
                        """)
                .param("id", assetId).param("userId", userId).param("version", expectedVersion)
                .param("purgeAfter", purgeAfter)
                .update() == 1;
    }

    public boolean restore(String userId, String assetId) {
        return client.sql("""
                        UPDATE asset.library_assets
                        SET status = 'ACTIVE', deleted_at = NULL, purge_after = NULL,
                            purge_blocked_at = NULL, version = version + 1, updated_at = now()
                        WHERE id = :id AND owner_user_id = :userId
                          AND status IN ('TRASHED', 'PURGE_PENDING', 'PURGE_BLOCKED')
                        """)
                .param("id", assetId).param("userId", userId)
                .update() == 1;
    }

    public boolean markPurgePending(String assetId) {
        return client.sql("""
                        UPDATE asset.library_assets
                        SET status = 'PURGE_PENDING', version = version + 1, updated_at = now()
                        WHERE id = :id AND status = 'TRASHED'
                        """)
                .param("id", assetId)
                .update() == 1;
    }

    public void markPurged(String assetId) {
        client.sql("""
                        UPDATE asset.library_assets
                        SET status = 'PURGED', purged_at = now(), version = version + 1, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", assetId).update();
    }

    public boolean hasActiveReferences(String assetId) {
        Integer count = client.sql("""
                        SELECT
                            (SELECT count(*) FROM asset.library_project_links WHERE asset_id = :assetId)
                          + (SELECT count(*) FROM asset.library_group_items WHERE member_asset_id = :assetId)
                          + (SELECT count(*) FROM asset.library_group_snapshot_items WHERE asset_id = :assetId)
                          + (SELECT count(*) FROM asset.library_group_outputs WHERE result_asset_id = :assetId)
                        """)
                .param("assetId", assetId).query(Integer.class).single();
        return count != null && count > 0;
    }

    static final String REFERENCED_OBJECT_KEYS_SQL = """
            SELECT DISTINCT b.object_key AS key
            FROM asset.library_media m
            JOIN asset.library_blobs b ON b.id = m.blob_id
            JOIN asset.library_assets a ON a.id = m.asset_id
            WHERE b.object_key IN (:keys) AND a.status NOT IN ('PURGE_PENDING', 'PURGED')
            UNION
            SELECT DISTINCT b.object_key AS key
            FROM asset.library_group_items gi
            JOIN asset.library_media m ON m.id = gi.media_id
            JOIN asset.library_blobs b ON b.id = m.blob_id
            JOIN asset.library_groups g ON g.id = gi.group_id
            JOIN asset.library_assets a ON a.id = g.asset_id
            WHERE b.object_key IN (:keys) AND a.status NOT IN ('PURGE_PENDING', 'PURGED')
            UNION
            SELECT DISTINCT si.object_key AS key
            FROM asset.library_group_snapshot_items si
            JOIN asset.library_group_snapshots s ON s.id = si.snapshot_id
            JOIN asset.library_groups g ON g.id = s.group_id
            JOIN asset.library_assets a ON a.id = g.asset_id
            WHERE si.object_key IN (:keys) AND a.status NOT IN ('PURGE_PENDING', 'PURGED')
            """;

    static final String ACTIVE_UPLOAD_SESSION_KEYS_SQL = """
            SELECT DISTINCT object_key AS key
            FROM asset.library_upload_sessions
            WHERE object_key IN (:keys)
              AND status = 'CREATED'
              AND expires_at > now()
            """;

    public Set<String> referencedObjectKeys(Collection<String> objectKeys) {
        List<String> keys = normalizeObjectKeys(objectKeys);
        if (keys.isEmpty()) return Set.of();
        return new LinkedHashSet<>(client.sql(REFERENCED_OBJECT_KEYS_SQL)
                .param("keys", keys)
                .query(String.class)
                .list());
    }

    public Set<String> activeUploadSessionObjectKeys(Collection<String> objectKeys) {
        List<String> keys = normalizeObjectKeys(objectKeys);
        if (keys.isEmpty()) return Set.of();
        return new LinkedHashSet<>(client.sql(ACTIVE_UPLOAD_SESSION_KEYS_SQL)
                .param("keys", keys)
                .query(String.class)
                .list());
    }

    private static List<String> normalizeObjectKeys(Collection<String> objectKeys) {
        return objectKeys == null ? List.of() : objectKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
    }

    public void markPurgeBlocked(String assetId) {
        client.sql("""
                        UPDATE asset.library_assets
                        SET status = 'PURGE_BLOCKED', purge_blocked_at = now(),
                            version = version + 1, updated_at = now()
                        WHERE id = :assetId
                        """)
                .param("assetId", assetId).update();
    }

    /** 在事务内锁定到期未清理的回收站资产；调用方须在同一事务里完成清理，否则行锁提前释放。 */
    public List<String> claimDueTrashAssets(int limit) {
        return client.sql("""
                        SELECT id FROM asset.library_assets
                        WHERE status = 'TRASHED'
                          AND purge_after IS NOT NULL
                          AND purge_after <= now()
                        ORDER BY purge_after
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public String upsertBlob(
            String objectKey,
            String sha256,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            Double durationSeconds,
            MediaType mediaType
    ) {
        return client.sql("""
                        INSERT INTO asset.library_blobs(
                            object_key, sha256, mime_type, size_bytes, width, height, duration_seconds, media_type
                        ) VALUES (
                            :objectKey, :sha256, :mimeType, :sizeBytes, :width, :height, :duration, :mediaType
                        )
                        ON CONFLICT (sha256) DO UPDATE SET
                            object_key = EXCLUDED.object_key,
                            mime_type = EXCLUDED.mime_type,
                            size_bytes = EXCLUDED.size_bytes,
                            width = COALESCE(EXCLUDED.width, asset.library_blobs.width),
                            height = COALESCE(EXCLUDED.height, asset.library_blobs.height),
                            duration_seconds = COALESCE(EXCLUDED.duration_seconds, asset.library_blobs.duration_seconds),
                            media_type = EXCLUDED.media_type,
                            updated_at = now()
                        RETURNING id
                        """)
                .param("objectKey", objectKey)
                .param("sha256", sha256)
                .param("mimeType", mimeType)
                .param("sizeBytes", sizeBytes)
                .param("width", width)
                .param("height", height)
                .param("duration", durationSeconds)
                .param("mediaType", mediaType.name())
                .query(String.class)
                .single();
    }

    public String upsertMedia(
            String assetId,
            String blobId,
            int sortOrder,
            MediaRole role,
            boolean primary,
            String constraintText
    ) {
        return client.sql("""
                        INSERT INTO asset.library_media(
                            asset_id, blob_id, sort_order, role, is_primary, constraint_text
                        ) VALUES (
                            :assetId, :blobId, :sortOrder, :role, :primary, :constraintText
                        )
                        ON CONFLICT (asset_id, blob_id, sort_order) DO UPDATE
                        SET role = EXCLUDED.role, is_primary = EXCLUDED.is_primary,
                            constraint_text = EXCLUDED.constraint_text
                        RETURNING id
                        """)
                .param("assetId", assetId)
                .param("blobId", blobId)
                .param("sortOrder", sortOrder)
                .param("role", role.name())
                .param("primary", primary)
                .param("constraintText", constraintText)
                .query(String.class)
                .single();
    }

    public List<MediaRef> listMedia(String assetId) {
        return client.sql("""
                        SELECT m.id, m.blob_id, b.media_type, b.object_key, b.mime_type, b.size_bytes,
                               b.width, b.height, b.duration_seconds, m.sort_order, m.role,
                               m.is_primary, m.constraint_text
                        FROM asset.library_media m
                        JOIN asset.library_blobs b ON b.id = m.blob_id
                        WHERE m.asset_id = :assetId
                        ORDER BY m.sort_order
                        """)
                .param("assetId", assetId)
                .query((rs, rowNum) -> new MediaRef(
                        rs.getString("id"),
                        rs.getString("blob_id"),
                        MediaType.valueOf(rs.getString("media_type")),
                        "/api/files/" + rs.getString("object_key"),
                        "/api/library/media/" + rs.getString("id") + "/content",
                        rs.getString("object_key"),
                        null,
                        null,
                        rs.getString("mime_type"),
                        rs.getLong("size_bytes"),
                        nullableInt(rs, "width"),
                        nullableInt(rs, "height"),
                        nullableDouble(rs, "duration_seconds"),
                        rs.getInt("sort_order"),
                        MediaRole.valueOf(rs.getString("role")),
                        rs.getBoolean("is_primary"),
                        rs.getString("constraint_text")
                ))
                .list();
    }

    public Optional<MediaRef> findMedia(String userId, String mediaId) {
        return client.sql("""
                        SELECT m.id, m.blob_id, b.media_type, b.object_key, b.mime_type, b.size_bytes,
                               b.width, b.height, b.duration_seconds, m.sort_order, m.role,
                               m.is_primary, m.constraint_text, a.owner_user_id
                        FROM asset.library_media m
                        JOIN asset.library_blobs b ON b.id = m.blob_id
                        JOIN asset.library_assets a ON a.id = m.asset_id
                        WHERE m.id = :mediaId AND a.owner_user_id = :userId
                        """)
                .param("mediaId", mediaId).param("userId", userId)
                .query((rs, rowNum) -> new MediaRef(
                        rs.getString("id"), rs.getString("blob_id"), MediaType.valueOf(rs.getString("media_type")),
                        "/api/files/" + rs.getString("object_key"),
                        "/api/library/media/" + rs.getString("id") + "/content",
                        rs.getString("object_key"), null, null, rs.getString("mime_type"),
                        rs.getLong("size_bytes"), nullableInt(rs, "width"), nullableInt(rs, "height"),
                        nullableDouble(rs, "duration_seconds"), rs.getInt("sort_order"),
                        MediaRole.valueOf(rs.getString("role")), rs.getBoolean("is_primary"),
                        rs.getString("constraint_text")))
                .optional();
    }

    public Optional<String> findMediaOwner(String mediaId) {
        return client.sql("""
                        SELECT a.owner_user_id
                        FROM asset.library_media m
                        JOIN asset.library_assets a ON a.id = m.asset_id
                        WHERE m.id = :mediaId
                        """)
                .param("mediaId", mediaId)
                .query(String.class)
                .optional();
    }

    public Optional<String> findMediaAssetId(String userId, String mediaId) {
        return client.sql("""
                        SELECT m.asset_id
                        FROM asset.library_media m
                        JOIN asset.library_assets a ON a.id = m.asset_id
                        WHERE m.id = :mediaId AND a.owner_user_id = :userId
                        """)
                .param("mediaId", mediaId).param("userId", userId)
                .query(String.class).optional();
    }

    public void deleteMedia(String mediaId) {
        client.sql("DELETE FROM asset.library_media WHERE id = :mediaId")
                .param("mediaId", mediaId).update();
    }

    public Optional<BlobRow> findBlobBySha(String sha256) {
        return client.sql("""
                        SELECT id, object_key, sha256, mime_type, size_bytes, width, height,
                               duration_seconds, media_type
                        FROM asset.library_blobs WHERE sha256 = :sha256
                        """)
                .param("sha256", sha256)
                .query((rs, rowNum) -> new BlobRow(
                        rs.getString("id"), rs.getString("object_key"), rs.getString("sha256"),
                        rs.getString("mime_type"), rs.getLong("size_bytes"), nullableInt(rs, "width"),
                        nullableInt(rs, "height"), nullableDouble(rs, "duration_seconds"),
                        MediaType.valueOf(rs.getString("media_type"))))
                .optional();
    }

    public void updateCover(String assetId, String coverUrl) {
        client.sql("UPDATE asset.library_assets SET cover_url = :coverUrl, updated_at = now() WHERE id = :assetId")
                .param("coverUrl", coverUrl).param("assetId", assetId).update();
    }

    public Optional<String> findAssetIdByBlobAndOwner(String ownerUserId, String blobId) {
        return client.sql("""
                        SELECT a.id
                        FROM asset.library_media m
                        JOIN asset.library_assets a ON a.id = m.asset_id
                        WHERE m.blob_id = :blobId AND a.owner_user_id = :ownerUserId
                          AND a.status = 'ACTIVE'
                        ORDER BY a.created_at
                        LIMIT 1
                        """)
                .param("blobId", blobId).param("ownerUserId", ownerUserId)
                .query(String.class).optional();
    }

    public List<TagRef> listTags(String assetId) {
        return client.sql("""
                        SELECT t.id, t.name, t.color, at.source
                        FROM asset.library_asset_tags at
                        JOIN asset.library_tags t ON t.id = at.tag_id
                        WHERE at.asset_id = :assetId
                        ORDER BY lower(t.name)
                        """)
                .param("assetId", assetId)
                .query((rs, rowNum) -> new TagRef(
                        rs.getString("id"), rs.getString("name"), rs.getString("color"), rs.getString("source")))
                .list();
    }

    public List<ProjectLinkRef> listProjectLinks(String assetId) {
        return client.sql("""
                        SELECT project_id, created_at
                        FROM asset.library_project_links
                        WHERE asset_id = :assetId
                        ORDER BY created_at
                        """)
                .param("assetId", assetId)
                .query((rs, rowNum) -> new ProjectLinkRef(
                        rs.getString("project_id"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public String createTag(String userId, String name, String color) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return client.sql("""
                        INSERT INTO asset.library_tags(owner_user_id, name, normalized_name, color)
                        VALUES (:userId, :name, :normalized, :color)
                        ON CONFLICT (owner_user_id, normalized_name) DO UPDATE SET
                            name = EXCLUDED.name, color = COALESCE(EXCLUDED.color, asset.library_tags.color)
                        RETURNING id
                        """)
                .param("userId", userId).param("name", name.trim())
                .param("normalized", normalized).param("color", color)
                .query(String.class).single();
    }

    public List<TagRef> listTagsByUser(String userId) {
        return client.sql("""
                        SELECT id, name, color, 'MANUAL' AS source
                        FROM asset.library_tags
                        WHERE owner_user_id = :userId
                        ORDER BY lower(name)
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new TagRef(
                        rs.getString("id"), rs.getString("name"), rs.getString("color"), rs.getString("source")))
                .list();
    }

    public boolean deleteTag(String userId, String tagId) {
        return client.sql("""
                        DELETE FROM asset.library_tags
                        WHERE id = :tagId AND owner_user_id = :userId
                        """)
                .param("tagId", tagId).param("userId", userId)
                .update() == 1;
    }

    public void replaceTags(String userId, String assetId, List<String> tagIds, String source) {
        client.sql("""
                        DELETE FROM asset.library_asset_tags
                        WHERE asset_id = :assetId
                          AND tag_id IN (SELECT id FROM asset.library_tags WHERE owner_user_id = :userId AND :source = 'MANUAL')
                        """)
                .param("assetId", assetId).param("userId", userId).param("source", source)
                .update();
        if (tagIds != null) {
            for (String tagId : tagIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList()) {
                client.sql("""
                                INSERT INTO asset.library_asset_tags(asset_id, tag_id, source)
                                SELECT :assetId, t.id, :source
                                FROM asset.library_tags t
                                WHERE t.id = :tagId AND t.owner_user_id = :userId
                                ON CONFLICT (asset_id, tag_id) DO UPDATE SET source = EXCLUDED.source
                                """)
                        .param("assetId", assetId).param("tagId", tagId)
                        .param("userId", userId).param("source", source)
                        .update();
            }
        }
        rebuildSearchText(assetId);
    }

    public void addAiTags(String assetId, List<String> names) {
        if (names == null || names.isEmpty()) return;
        AssetSummary asset = findAssetInternal(assetId).orElse(null);
        if (asset == null || asset.manualOverride()) return;
        for (String name : names.stream().filter(n -> n != null && !n.isBlank()).distinct().limit(20).toList()) {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            client.sql("""
                            INSERT INTO asset.library_asset_tags(asset_id, tag_id, source)
                            SELECT :assetId, t.id, 'AI'
                            FROM asset.library_tags t
                            WHERE t.owner_user_id = :userId AND t.normalized_name = :normalized
                            ON CONFLICT (asset_id, tag_id) DO NOTHING
                            """)
                    .param("assetId", assetId).param("userId", asset.ownerUserId())
                    .param("normalized", normalized).update();
        }
        rebuildSearchText(assetId);
    }

    public void rebuildSearchText(String assetId) {
        AssetSummary asset = findAssetInternal(assetId).orElse(null);
        if (asset == null) return;
        String searchText = AssetSearchText.build(
                asset.name(), asset.description(), asset.aiDescription(),
                listTags(assetId).stream().map(TagRef::name).toList());
        client.sql("UPDATE asset.library_assets SET search_text = :searchText WHERE id = :id")
                .param("searchText", searchText).param("id", assetId).update();
    }

    public String createIdempotencyKey(String userId, String operation, String key, String resourceId) {
        if (key == null || key.isBlank()) return resourceId;
        return client.sql("""
                        INSERT INTO asset.library_idempotency_keys(
                            owner_user_id, operation, idempotency_key, resource_id, expires_at
                        ) VALUES (
                            :userId, :operation, :key, :resourceId, now() + interval '7 days'
                        )
                        ON CONFLICT (owner_user_id, operation, idempotency_key) DO UPDATE SET
                            resource_id = COALESCE(asset.library_idempotency_keys.resource_id, EXCLUDED.resource_id)
                        RETURNING resource_id
                        """)
                .param("userId", userId).param("operation", operation).param("key", key)
                .param("resourceId", resourceId)
                .query(String.class).single();
    }

    public Optional<String> findIdempotencyResource(String userId, String operation, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return client.sql("""
                        SELECT resource_id FROM asset.library_idempotency_keys
                        WHERE owner_user_id = :userId AND operation = :operation AND idempotency_key = :key
                          AND expires_at > now()
                        """)
                .param("userId", userId).param("operation", operation).param("key", key)
                .query(String.class).optional();
    }

    public void markAiMetadata(String assetId, String description, List<String> tags) {
        client.sql("""
                        UPDATE asset.library_assets
                        SET ai_description = CASE WHEN manual_override THEN ai_description ELSE :description END,
                            search_text = CASE WHEN manual_override THEN search_text
                                ELSE trim(concat_ws(' ', name, description, :description)) END,
                            updated_at = now(), version = version + 1
                        WHERE id = :assetId
                        """)
                .param("description", description).param("assetId", assetId).update();
        addAiTags(assetId, tags);
    }

    public void createAnalysisTask(String assetId) {
        client.sql("""
                        INSERT INTO asset.library_analysis_tasks(asset_id)
                        SELECT :assetId
                        WHERE EXISTS (
                            SELECT 1 FROM asset.library_assets
                            WHERE id = :assetId AND scope = 'PROJECT' AND status = 'ACTIVE'
                        )
                        ON CONFLICT (asset_id) DO NOTHING
                        """)
                .param("assetId", assetId).update();
    }

    public void requeueAnalysisTask(String assetId) {
        client.sql("""
                        INSERT INTO asset.library_analysis_tasks(
                            asset_id, status, attempt_count, next_retry_at
                        )
                        SELECT :assetId, 'PENDING', 0, now()
                        WHERE EXISTS (
                            SELECT 1 FROM asset.library_assets
                            WHERE id = :assetId AND scope = 'PROJECT' AND status = 'ACTIVE'
                        )
                        ON CONFLICT (asset_id) DO UPDATE SET
                            status = 'PENDING',
                            attempt_count = 0,
                            lease_owner = NULL,
                            lease_until = NULL,
                            next_retry_at = now(),
                            error_code = NULL,
                            error_message = NULL,
                            updated_at = now()
                        WHERE asset.library_analysis_tasks.status <> 'SUCCEEDED'
                        """)
                .param("assetId", assetId).update();
    }

    public List<AnalysisTask> claimAnalysisTasks(String workerId, int limit) {
        return client.sql("""
                        WITH claimed AS (
                            SELECT t.id FROM asset.library_analysis_tasks t
                            WHERE t.status = 'PENDING' AND t.next_retry_at <= now()
                              AND EXISTS (
                                  SELECT 1 FROM asset.library_assets a
                                  WHERE a.id = t.asset_id
                                    AND a.scope = 'PROJECT' AND a.status = 'ACTIVE'
                              )
                            ORDER BY t.created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE asset.library_analysis_tasks t
                        SET status = 'PROCESSING', lease_owner = :workerId,
                            lease_until = now() + interval '5 minutes', attempt_count = attempt_count + 1,
                            updated_at = now()
                        FROM claimed
                        WHERE t.id = claimed.id
                        RETURNING t.id, t.asset_id, t.attempt_count
                        """)
                .param("workerId", workerId).param("limit", limit)
                .query((rs, rowNum) -> new AnalysisTask(
                        rs.getString("id"), rs.getString("asset_id"), rs.getInt("attempt_count")))
                .list();
    }

    public void completeAnalysis(String taskId) {
        client.sql("""
                        UPDATE asset.library_analysis_tasks
                        SET status = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL,
                            error_code = NULL, error_message = NULL, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", taskId).update();
    }

    public void failAnalysis(String taskId, int attemptCount, String errorCode, String errorMessage) {
        boolean dead = attemptCount >= 3;
        client.sql("""
                        UPDATE asset.library_analysis_tasks
                        SET status = CASE WHEN :dead THEN 'DEAD' ELSE 'PENDING' END,
                            lease_owner = NULL, lease_until = NULL,
                            next_retry_at = now() + make_interval(secs => LEAST(300, power(2, :attemptCount)::int * 5)),
                            error_code = :errorCode, error_message = :errorMessage, updated_at = now()
                        WHERE id = :id
                        """)
                .param("dead", dead).param("attemptCount", attemptCount)
                .param("errorCode", errorCode).param("errorMessage", errorMessage).param("id", taskId).update();
    }

    public void createCleanupTask(String objectKey) {
        client.sql("""
                        INSERT INTO asset.library_storage_cleanup_tasks(object_key)
                        SELECT :objectKey
                        WHERE NOT EXISTS (
                            SELECT 1
                            FROM asset.library_storage_cleanup_tasks
                            WHERE object_key = :objectKey
                              AND status IN ('PENDING', 'PROCESSING')
                        )
                        """)
                .param("objectKey", objectKey).update();
    }

    public List<CleanupTask> claimCleanupTasks(String workerId, int limit) {
        return client.sql("""
                        WITH claimed AS (
                            SELECT id FROM asset.library_storage_cleanup_tasks
                            WHERE status = 'PENDING' AND next_retry_at <= now()
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE asset.library_storage_cleanup_tasks t
                        SET status = 'PROCESSING', lease_owner = :workerId,
                            lease_until = now() + interval '5 minutes',
                            attempt_count = attempt_count + 1, updated_at = now()
                        FROM claimed
                        WHERE t.id = claimed.id
                        RETURNING t.id, t.object_key, t.attempt_count
                        """)
                .param("workerId", workerId).param("limit", limit)
                .query((rs, rowNum) -> new CleanupTask(
                        rs.getString("id"), rs.getString("object_key"), rs.getInt("attempt_count")))
                .list();
    }

    public void completeCleanup(String taskId) {
        client.sql("""
                        UPDATE asset.library_storage_cleanup_tasks
                        SET status = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", taskId).update();
    }

    public void failCleanup(String taskId, int attemptCount, String error) {
        client.sql("""
                        UPDATE asset.library_storage_cleanup_tasks
                        SET status = CASE WHEN :dead THEN 'DEAD' ELSE 'PENDING' END,
                            lease_owner = NULL, lease_until = NULL,
                            next_retry_at = now() + make_interval(secs => LEAST(3600, power(2, :attemptCount)::int * 10)),
                            error_message = :error, updated_at = now()
                        WHERE id = :id
                        """)
                .param("dead", attemptCount >= 5).param("attemptCount", attemptCount)
                .param("error", error).param("id", taskId).update();
    }

    public String createUploadSession(
            String userId,
            String projectId,
            String fileName,
            String relativePath,
            String mimeType,
            long sizeBytes,
            String sha256,
            String objectKey,
            String minioUploadId,
            long partSize,
            OffsetDateTime expiresAt
    ) {
        return client.sql("""
                        INSERT INTO asset.library_upload_sessions(
                            owner_user_id, project_id, file_name, relative_path, mime_type,
                            size_bytes, sha256, object_key, minio_upload_id, part_size_bytes, expires_at
                        ) VALUES (
                            :userId, :projectId, :fileName, :relativePath, :mimeType,
                            :sizeBytes, :sha256, :objectKey, :uploadId, :partSize, :expiresAt
                        ) RETURNING id
                        """)
                .param("userId", userId).param("projectId", projectId)
                .param("fileName", fileName).param("relativePath", relativePath)
                .param("mimeType", mimeType).param("sizeBytes", sizeBytes)
                .param("sha256", sha256).param("objectKey", objectKey)
                .param("uploadId", minioUploadId).param("partSize", partSize)
                .param("expiresAt", expiresAt)
                .query(String.class).single();
    }

    public Optional<UploadSession> findUploadSession(String userId, String sessionId) {
        return client.sql("""
                        SELECT * FROM asset.library_upload_sessions
                        WHERE id = :id AND owner_user_id = :userId AND status = 'CREATED'
                        """)
                .param("id", sessionId).param("userId", userId)
                .query((rs, rowNum) -> new UploadSession(
                        rs.getString("id"), rs.getString("owner_user_id"), rs.getString("project_id"),
                        rs.getString("file_name"), rs.getString("relative_path"), rs.getString("mime_type"),
                        rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("object_key"),
                        rs.getString("minio_upload_id"), rs.getLong("part_size_bytes"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
    }

    public void markUploadComplete(String userId, String sessionId) {
        client.sql("""
                        UPDATE asset.library_upload_sessions
                        SET status = 'COMPLETED', updated_at = now()
                        WHERE id = :id AND owner_user_id = :userId
                        """)
                .param("id", sessionId).param("userId", userId).update();
    }

    public void markUploadCancelled(String userId, String sessionId) {
        client.sql("""
                        UPDATE asset.library_upload_sessions
                        SET status = 'CANCELLED', updated_at = now()
                        WHERE id = :id AND owner_user_id = :userId
                        """)
                .param("id", sessionId).param("userId", userId).update();
    }

    public List<UploadSession> expiredUploadSessions(int limit) {
        return client.sql("""
                        SELECT * FROM asset.library_upload_sessions
                        WHERE status = 'CREATED' AND expires_at < now()
                        ORDER BY expires_at
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new UploadSession(
                        rs.getString("id"), rs.getString("owner_user_id"), rs.getString("project_id"),
                        rs.getString("file_name"), rs.getString("relative_path"), rs.getString("mime_type"),
                        rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("object_key"),
                        rs.getString("minio_upload_id"), rs.getLong("part_size_bytes"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .list();
    }

    public String createImportJob(
            String userId,
            String projectId,
            AssetScope scope,
            ImportMode mode,
            String groupId,
            String manifestHash,
            String confirmationHash,
            OffsetDateTime expiresAt,
            int totalCount,
            long totalBytes,
            int ignoredCount
    ) {
        return client.sql("""
                        INSERT INTO asset.library_import_jobs(
                            owner_user_id, project_id, scope, mode, group_id, manifest_hash, confirmation_hash,
                            confirmation_expires_at, total_count, total_bytes, ignored_count
                        ) VALUES (
                            :userId, :projectId, :scope, :mode, :groupId, :manifestHash, :confirmationHash,
                            :expiresAt, :totalCount, :totalBytes, :ignoredCount
                        ) RETURNING id
                        """)
                .param("userId", userId).param("projectId", projectId).param("scope", scope.name())
                .param("mode", mode.name()).param("groupId", groupId)
                .param("manifestHash", manifestHash).param("confirmationHash", confirmationHash)
                .param("expiresAt", expiresAt).param("totalCount", totalCount)
                .param("totalBytes", totalBytes).param("ignoredCount", ignoredCount)
                .query(String.class).single();
    }

    public void insertImportItem(String jobId, String itemKey, String relativePath, String fileName,
                                 long sizeBytes, String mimeType, int sortOrder) {
        client.sql("""
                        INSERT INTO asset.library_import_items(
                            job_id, item_key, relative_path, file_name, size_bytes, mime_type, sort_order
                        ) VALUES (
                            :jobId, :itemKey, :relativePath, :fileName, :sizeBytes, :mimeType, :sortOrder
                        )
                        ON CONFLICT (job_id, item_key) DO NOTHING
                        """)
                .param("jobId", jobId).param("itemKey", itemKey).param("relativePath", relativePath)
                .param("fileName", fileName).param("sizeBytes", sizeBytes).param("mimeType", mimeType)
                .param("sortOrder", sortOrder)
                .update();
    }

    public Optional<ImportJob> findImportJob(String userId, String jobId) {
        return client.sql("""
                        SELECT * FROM asset.library_import_jobs
                        WHERE id = :jobId AND owner_user_id = :userId
                        """)
                .param("jobId", jobId).param("userId", userId)
                .query(importJobMapper())
                .optional();
    }

    public List<AnalysisTaskStatus> listAnalysisTaskStatuses(
            String userId,
            Collection<String> assetIds
    ) {
        List<String> normalized = assetIds == null ? List.of() : assetIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().limit(200).toList();
        if (normalized.isEmpty()) return List.of();
        return client.sql("""
                        SELECT t.asset_id, t.status, t.error_message, t.updated_at
                        FROM asset.library_analysis_tasks t
                        JOIN asset.library_assets a ON a.id = t.asset_id
                        WHERE a.owner_user_id = :userId AND t.asset_id IN (:assetIds)
                        ORDER BY t.created_at
                        """)
                .param("userId", userId).param("assetIds", normalized)
                .query((rs, rowNum) -> new AnalysisTaskStatus(
                        rs.getString("asset_id"),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    public ImportItem findImportItem(String jobId, String itemKey) {
        return client.sql("""
                        SELECT * FROM asset.library_import_items
                        WHERE job_id = :jobId AND item_key = :itemKey
                        """)
                .param("jobId", jobId).param("itemKey", itemKey)
                .query(importItemMapper())
                .optional()
                .orElseThrow(() -> new com.storyboard.common.BusinessException(40401, "导入项不存在"));
    }

    public void finishImportItem(String itemId, String status, String assetId, String sha256, String error) {
        client.sql("""
                        UPDATE asset.library_import_items
                        SET status = :status, asset_id = :assetId, sha256 = :sha256,
                            error_message = :error, attempt_count = attempt_count + 1, updated_at = now()
                        WHERE id = :id
                        """)
                .param("status", status).param("assetId", assetId).param("sha256", sha256)
                .param("error", error).param("id", itemId).update();
        client.sql("""
                        UPDATE asset.library_import_jobs j
                        SET processed_count = stats.processed,
                            succeeded_count = stats.succeeded,
                            duplicate_count = stats.duplicates,
                            failed_count = stats.failed,
                            status = CASE WHEN stats.processed >= j.total_count + j.ignored_count
                                          THEN CASE WHEN stats.failed > 0 THEN 'PARTIAL' ELSE 'COMPLETED' END
                                          ELSE 'UPLOADING' END,
                            updated_at = now()
                        FROM (
                            SELECT job_id,
                                   count(*) FILTER (WHERE status <> 'PENDING') AS processed,
                                   count(*) FILTER (WHERE status = 'REGISTERED') AS succeeded,
                                   count(*) FILTER (WHERE status = 'DUPLICATE_LINKED') AS duplicates,
                                   count(*) FILTER (WHERE status = 'FAILED') AS failed
                            FROM asset.library_import_items
                            WHERE job_id = (SELECT job_id FROM asset.library_import_items WHERE id = :id)
                            GROUP BY job_id
                        ) stats
                        WHERE j.id = stats.job_id
                        """)
                .param("id", itemId).update();
    }

    private RowMapper<ImportJob> importJobMapper() {
        return (rs, rowNum) -> new ImportJob(
                rs.getString("id"), rs.getString("owner_user_id"), rs.getString("project_id"),
                AssetScope.valueOf(rs.getString("scope")),
                ImportMode.valueOf(rs.getString("mode")), rs.getString("group_id"), rs.getString("manifest_hash"),
                rs.getString("confirmation_hash"), rs.getObject("confirmation_expires_at", OffsetDateTime.class),
                rs.getString("status"), rs.getInt("total_count"), rs.getLong("total_bytes"),
                rs.getInt("ignored_count"), rs.getInt("processed_count"),
                rs.getInt("succeeded_count"), rs.getInt("duplicate_count"), rs.getInt("failed_count"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private RowMapper<ImportItem> importItemMapper() {
        return (rs, rowNum) -> new ImportItem(
                rs.getString("id"), rs.getString("job_id"), rs.getString("item_key"),
                rs.getString("relative_path"), rs.getString("file_name"), rs.getLong("size_bytes"),
                rs.getString("mime_type"), rs.getString("sha256"), rs.getString("status"),
                rs.getString("asset_id"), rs.getString("error_message"), rs.getInt("attempt_count"),
                rs.getInt("sort_order"));
    }

    public Optional<String> findExistingOutput(String producer, String generationRecordId, int resultIndex) {
        return client.sql("""
                        SELECT result_asset_id FROM asset.library_group_outputs
                        WHERE producer = :producer AND generation_record_id = :generationRecordId
                          AND result_index = :resultIndex
                        """)
                .param("producer", producer)
                .param("generationRecordId", generationRecordId)
                .param("resultIndex", resultIndex)
                .query(String.class)
                .optional();
    }

    public void linkGroupOutput(String groupId, String producer, String generationRecordId, int resultIndex, String resultAssetId) {
        client.sql("""
                        INSERT INTO asset.library_group_outputs(
                            group_id, producer, generation_record_id, result_index, result_asset_id
                        ) VALUES (
                            :groupId, :producer, :generationRecordId, :resultIndex, :resultAssetId
                        )
                        ON CONFLICT (producer, generation_record_id, result_index) DO NOTHING
                        """)
                .param("groupId", groupId).param("producer", producer)
                .param("generationRecordId", generationRecordId).param("resultIndex", resultIndex)
                .param("resultAssetId", resultAssetId)
                .update();
    }

    public void insertGroup(String groupId, String userId, AssetScope scope, String projectId) {
        client.sql("""
                        INSERT INTO asset.library_groups(id, asset_id, owner_user_id, scope, owner_project_id)
                        VALUES (:id, :assetId, :userId, :scope, :projectId)
                        """)
                .param("id", groupId).param("assetId", groupId).param("userId", userId)
                .param("scope", scope.name()).param("projectId", projectId).update();
    }

    public Optional<String> findPrimaryMediaId(String assetId) {
        return client.sql("""
                        SELECT id FROM asset.library_media
                        WHERE asset_id = :assetId AND status = 'ACTIVE'
                        ORDER BY is_primary DESC, sort_order, created_at
                        LIMIT 1
                        """)
                .param("assetId", assetId)
                .query(String.class)
                .optional();
    }

    public void linkGroupItem(String groupId, String mediaId, String memberAssetId, int sortOrder) {
        client.sql("""
                        INSERT INTO asset.library_group_items(
                            group_id, media_id, member_asset_id, sort_order, role
                        ) VALUES (
                            :groupId, :mediaId, :memberAssetId, :sortOrder, 'REFERENCE'
                        )
                        ON CONFLICT (group_id, media_id) DO NOTHING
                        """)
                .param("groupId", groupId).param("mediaId", mediaId)
                .param("memberAssetId", memberAssetId).param("sortOrder", sortOrder)
                .update();
    }

    public Optional<GroupRow> findGroup(String userId, String groupId) {
        return client.sql("""
                        SELECT g.id, g.asset_id, g.owner_user_id, g.scope, g.owner_project_id,
                               g.version, a.name, a.description
                        FROM asset.library_groups g
                        JOIN asset.library_assets a ON a.id = g.asset_id
                        WHERE g.id = :groupId AND g.owner_user_id = :userId
                        """)
                .param("groupId", groupId).param("userId", userId)
                .query((rs, rowNum) -> new GroupRow(
                        rs.getString("id"), rs.getString("asset_id"), rs.getString("owner_user_id"),
                        AssetScope.valueOf(rs.getString("scope")), rs.getString("owner_project_id"),
                        rs.getLong("version"), rs.getString("name"), rs.getString("description")))
                .optional();
    }

    public void replaceGroupItems(String userId, String groupId, List<GroupItemWrite> items) {
        client.sql("DELETE FROM asset.library_group_items WHERE group_id = :groupId")
                .param("groupId", groupId).update();
        for (GroupItemWrite item : items) {
            int inserted = client.sql("""
                            INSERT INTO asset.library_group_items(group_id, media_id, member_asset_id, sort_order, role)
                            SELECT :groupId, m.id, m.asset_id, :sortOrder, :role
                            FROM asset.library_media m
                            JOIN asset.library_assets a ON a.id = m.asset_id
                            WHERE m.id = :mediaId
                              AND a.owner_user_id = :userId
                              AND a.status = 'ACTIVE'
                              AND a.asset_type IN ('IMAGE','VIDEO','AUDIO')
                            """)
                    .param("groupId", groupId).param("mediaId", item.mediaId())
                    .param("sortOrder", item.sortOrder()).param("role", item.role())
                    .param("userId", userId)
                    .update();
            if (inserted == 0) throw new com.storyboard.common.BusinessException(40001, "资产组只允许当前用户的图片、视频或音频媒体");
        }
        client.sql("""
                        UPDATE asset.library_groups
                        SET version = version + 1, updated_at = now()
                        WHERE id = :groupId AND owner_user_id = :userId
                        """)
                .param("groupId", groupId).param("userId", userId).update();
    }

    public List<GroupSnapshotExpander.Item> listGroupItems(String userId, String groupId) {
        return client.sql("""
                        SELECT m.id AS media_id, m.asset_id, b.id AS blob_id, b.object_key,
                               b.media_type, gi.role, gi.sort_order
                        FROM asset.library_group_items gi
                        JOIN asset.library_groups g ON g.id = gi.group_id
                        JOIN asset.library_media m ON m.id = gi.media_id
                        JOIN asset.library_blobs b ON b.id = m.blob_id
                        WHERE gi.group_id = :groupId AND g.owner_user_id = :userId
                        ORDER BY gi.sort_order
                        """)
                .param("groupId", groupId).param("userId", userId)
                .query((rs, rowNum) -> new GroupSnapshotExpander.Item(
                        rs.getString("media_id"), rs.getString("asset_id"), rs.getString("blob_id"),
                        rs.getString("object_key"), rs.getString("media_type"), rs.getString("role"),
                        rs.getInt("sort_order")))
                .list();
    }

    public List<String> listGroupMediaIds(String userId, String groupId) {
        return client.sql("""
                        SELECT gi.media_id
                        FROM asset.library_group_items gi
                        JOIN asset.library_groups g ON g.id = gi.group_id
                        WHERE gi.group_id = :groupId AND g.owner_user_id = :userId
                        ORDER BY gi.sort_order
                        """)
                .param("groupId", groupId).param("userId", userId)
                .query(String.class).list();
    }

    public String upsertSnapshot(
            String userId, String groupId, long groupVersion, String contentHash, String target
    ) {
        return client.sql("""
                        INSERT INTO asset.library_group_snapshots(
                            group_id, group_version, content_hash, target, created_by_user_id
                        ) VALUES (
                            :groupId, :groupVersion, :contentHash, :target, :userId
                        )
                        ON CONFLICT (group_id, group_version, content_hash, target) DO UPDATE SET
                            created_at = asset.library_group_snapshots.created_at
                        RETURNING id
                        """)
                .param("groupId", groupId).param("groupVersion", groupVersion)
                .param("contentHash", contentHash).param("target", target).param("userId", userId)
                .query(String.class).single();
    }

    public void replaceSnapshotItems(String snapshotId, List<GroupSnapshotExpander.Item> items) {
        client.sql("DELETE FROM asset.library_group_snapshot_items WHERE snapshot_id = :snapshotId")
                .param("snapshotId", snapshotId).update();
        for (GroupSnapshotExpander.Item item : items) {
            client.sql("""
                            INSERT INTO asset.library_group_snapshot_items(
                                snapshot_id, asset_id, media_id, blob_id, object_key, media_type, role, sort_order
                            ) VALUES (
                                :snapshotId, :assetId, :mediaId, :blobId, :objectKey, :mediaType, :role, :sortOrder
                            )
                            """)
                    .param("snapshotId", snapshotId).param("assetId", item.assetId())
                    .param("mediaId", item.mediaId()).param("blobId", item.blobId())
                    .param("objectKey", item.objectKey()).param("mediaType", item.mediaType())
                    .param("role", item.role()).param("sortOrder", item.sortOrder()).update();
        }
    }

    public Optional<SnapshotRow> findSnapshot(String userId, String snapshotId) {
        return client.sql("""
                        SELECT s.id, s.group_id, s.group_version, s.content_hash, s.target, s.created_at
                        FROM asset.library_group_snapshots s
                        JOIN asset.library_groups g ON g.id = s.group_id
                        WHERE s.id = :snapshotId AND g.owner_user_id = :userId
                        """)
                .param("snapshotId", snapshotId).param("userId", userId)
                .query((rs, rowNum) -> new SnapshotRow(
                        rs.getString("id"), rs.getString("group_id"), rs.getLong("group_version"),
                        rs.getString("content_hash"), rs.getString("target"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    public List<SnapshotItem> listSnapshotItems(String snapshotId) {
        return client.sql("""
                        SELECT asset_id, media_id, blob_id, object_key, media_type, role, sort_order
                        FROM asset.library_group_snapshot_items
                        WHERE snapshot_id = :snapshotId
                        ORDER BY sort_order
                        """)
                .param("snapshotId", snapshotId)
                .query((rs, rowNum) -> new SnapshotItem(
                        rs.getString("asset_id"), rs.getString("media_id"), rs.getString("blob_id"),
                        rs.getString("object_key"),
                        "/api/library/media/" + rs.getString("media_id") + "/content",
                        MediaType.valueOf(rs.getString("media_type")),
                        MediaRole.valueOf(rs.getString("role")),
                        rs.getInt("sort_order")))
                .list();
    }

    public Optional<FolderRow> findFolder(String userId, String folderId) {
        return client.sql("""
                        SELECT id, owner_user_id, scope, owner_project_id, parent_id,
                               name, normalized_name, sort_order, version
                        FROM asset.library_folders
                        WHERE id = :folderId AND owner_user_id = :userId
                        """)
                .param("folderId", folderId)
                .param("userId", userId)
                .query((rs, rowNum) -> folderRow(rs))
                .optional();
    }

    public Optional<FolderRow> findFolderBySibling(
            String userId,
            AssetScope scope,
            String projectId,
            String parentId,
            String normalizedName
    ) {
        return client.sql("""
                        SELECT id, owner_user_id, scope, owner_project_id, parent_id,
                               name, normalized_name, sort_order, version
                        FROM asset.library_folders
                        WHERE owner_user_id = :userId
                          AND scope = :scope
                          AND owner_project_id IS NOT DISTINCT FROM :projectId
                          AND parent_id IS NOT DISTINCT FROM :parentId
                          AND normalized_name = :normalizedName
                        """)
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", projectId)
                .param("parentId", parentId)
                .param("normalizedName", normalizedName)
                .query((rs, rowNum) -> folderRow(rs))
                .optional();
    }

    public String insertFolder(
            String userId,
            AssetScope scope,
            String projectId,
            String parentId,
            String name,
            String normalizedName
    ) {
        return client.sql("""
                        INSERT INTO asset.library_folders(
                            owner_user_id, scope, owner_project_id, parent_id,
                            name, normalized_name
                        ) VALUES (
                            :userId, :scope, :projectId, :parentId, :name, :normalizedName
                        )
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .param("parentId", parentId)
                .param("name", name)
                .param("normalizedName", normalizedName)
                .query(String.class)
                .single();
    }

    public boolean renameFolder(
            String userId,
            String folderId,
            String name,
            String normalizedName,
            long expectedVersion
    ) {
        return client.sql("""
                        UPDATE asset.library_folders
                        SET name = :name,
                            normalized_name = :normalizedName,
                            version = version + 1,
                            updated_at = now()
                        WHERE id = :folderId AND owner_user_id = :userId AND version = :expectedVersion
                        """)
                .param("userId", userId)
                .param("folderId", folderId)
                .param("name", name)
                .param("normalizedName", normalizedName)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public boolean moveFolder(
            String userId,
            String folderId,
            String parentId,
            long expectedVersion
    ) {
        return client.sql("""
                        UPDATE asset.library_folders
                        SET parent_id = :parentId,
                            version = version + 1,
                            updated_at = now()
                        WHERE id = :folderId
                          AND owner_user_id = :userId
                          AND version = :expectedVersion
                        """)
                .param("userId", userId)
                .param("folderId", folderId)
                .param("parentId", parentId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public boolean deleteEmptyFolder(String userId, String folderId, long expectedVersion) {
        return client.sql("""
                        DELETE FROM asset.library_folders f
                        WHERE f.id = :folderId
                          AND f.owner_user_id = :userId
                          AND f.version = :expectedVersion
                          AND NOT EXISTS (
                              SELECT 1 FROM asset.library_folders child WHERE child.parent_id = f.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM asset.library_asset_folders item WHERE item.folder_id = f.id
                          )
                        """)
                .param("userId", userId)
                .param("folderId", folderId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public List<String> listFolderTreeIds(String userId, String folderId) {
        return client.sql("""
                        WITH RECURSIVE folder_tree AS (
                            SELECT id, 0 AS depth
                            FROM asset.library_folders
                            WHERE id = :folderId AND owner_user_id = :userId
                            UNION ALL
                            SELECT child.id, parent.depth + 1
                            FROM asset.library_folders child
                            JOIN folder_tree parent ON child.parent_id = parent.id
                            WHERE child.owner_user_id = :userId
                        )
                        SELECT id
                        FROM folder_tree
                        ORDER BY depth DESC
                        """)
                .param("folderId", folderId)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    public List<LibraryRepository.FolderRow> listChildFolders(String userId, String parentId) {
        return client.sql("""
                        SELECT id, owner_user_id, scope, owner_project_id, parent_id,
                               name, normalized_name, sort_order, version
                        FROM asset.library_folders
                        WHERE owner_user_id = :userId
                          AND parent_id = :parentId
                        ORDER BY sort_order, name, id
                        """)
                .param("userId", userId)
                .param("parentId", parentId)
                .query((rs, rowNum) -> folderRow(rs))
                .list();
    }

    public List<String> listDirectFolderAssetIds(String userId, String folderId) {
        return client.sql("""
                        SELECT af.asset_id
                        FROM asset.library_asset_folders af
                        JOIN asset.library_assets a ON a.id = af.asset_id
                        WHERE af.owner_user_id = :userId
                          AND af.folder_id = :folderId
                          AND a.status = 'ACTIVE'
                        ORDER BY af.created_at, af.asset_id
                        """)
                .param("userId", userId)
                .param("folderId", folderId)
                .query(String.class)
                .list();
    }

    public void trashAssetsInFolders(
            String userId,
            AssetScope scope,
            String projectId,
            Collection<String> folderIds,
            int retentionDays
    ) {
        List<String> normalized = folderIds == null ? List.of() : folderIds.stream().distinct().toList();
        if (normalized.isEmpty()) return;
        client.sql("""
                        UPDATE asset.library_assets a
                        SET status = 'TRASHED',
                            deleted_at = now(),
                            purge_after = now() + make_interval(days => :retentionDays),
                            version = version + 1,
                            updated_at = now()
                        WHERE a.owner_user_id = :userId
                          AND a.status = 'ACTIVE'
                          AND EXISTS (
                              SELECT 1
                              FROM asset.library_asset_folders af
                              WHERE af.asset_id = a.id
                                AND af.owner_user_id = :userId
                                AND af.scope = :scope
                                AND af.owner_project_id IS NOT DISTINCT FROM :projectId
                                AND af.folder_id IN (:folderIds)
                          )
                        """)
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .param("folderIds", normalized)
                .param("retentionDays", retentionDays)
                .update();
    }

    public void deleteAssetFolderMappingsForFolders(
            String userId,
            Collection<String> folderIds
    ) {
        List<String> normalized = folderIds == null ? List.of() : folderIds.stream().distinct().toList();
        if (normalized.isEmpty()) return;
        client.sql("""
                        DELETE FROM asset.library_asset_folders
                        WHERE owner_user_id = :userId
                          AND folder_id IN (:folderIds)
                        """)
                .param("userId", userId)
                .param("folderIds", normalized)
                .update();
    }

    public void deleteFolderRow(String userId, String folderId) {
        client.sql("""
                        DELETE FROM asset.library_folders
                        WHERE id = :folderId AND owner_user_id = :userId
                        """)
                .param("folderId", folderId)
                .param("userId", userId)
                .update();
    }

    public long countFolderChildren(String folderId) {
        Long count = client.sql("SELECT count(*) FROM asset.library_folders WHERE parent_id = :folderId")
                .param("folderId", folderId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public long countFolderAssets(String folderId) {
        Long count = client.sql("SELECT count(*) FROM asset.library_asset_folders WHERE folder_id = :folderId")
                .param("folderId", folderId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public List<LibraryFolderNode> listFolders(
            String userId,
            AssetScope scope,
            String projectId
    ) {
        return client.sql("""
                        WITH RECURSIVE descendants AS (
                            SELECT f.id AS root_id, f.id
                            FROM asset.library_folders f
                            WHERE f.owner_user_id = :userId
                              AND f.scope = :scope
                              AND f.owner_project_id IS NOT DISTINCT FROM :projectId
                            UNION ALL
                            SELECT d.root_id, child.id
                            FROM descendants d
                            JOIN asset.library_folders child ON child.parent_id = d.id
                        )
                        SELECT f.id, f.parent_id, f.name, f.scope, f.owner_project_id, f.version,
                               count(DISTINCT direct.asset_id) AS direct_count,
                               count(DISTINCT nested.asset_id) AS nested_count
                        FROM asset.library_folders f
                        LEFT JOIN asset.library_asset_folders direct ON direct.folder_id = f.id
                        LEFT JOIN descendants d ON d.root_id = f.id
                        LEFT JOIN asset.library_asset_folders nested ON nested.folder_id = d.id
                        WHERE f.owner_user_id = :userId
                          AND f.scope = :scope
                          AND f.owner_project_id IS NOT DISTINCT FROM :projectId
                        GROUP BY f.id, f.parent_id, f.name, f.scope, f.owner_project_id, f.version
                        ORDER BY f.parent_id NULLS FIRST,
                                 CASE WHEN f.parent_id IS NULL THEN f.name END NULLS LAST,
                                 f.sort_order, f.name,
                                 f.id
                        """)
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .query((rs, rowNum) -> new LibraryFolderNode(
                        rs.getString("id"),
                        rs.getString("parent_id"),
                        rs.getString("name"),
                        AssetScope.valueOf(rs.getString("scope")),
                        rs.getString("owner_project_id"),
                        rs.getLong("direct_count"),
                        rs.getLong("nested_count"),
                        rs.getLong("version")
                ))
                .list();
    }

    public long countUnfiledAssets(String userId, AssetScope scope, String projectId) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(*)
                FROM asset.library_assets a
                WHERE a.owner_user_id = :userId
                  AND a.status = 'ACTIVE'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM asset.library_asset_folders af
                      WHERE af.asset_id = a.id
                        AND af.scope = :scope
                        AND af.owner_project_id IS NOT DISTINCT FROM :projectId
                  )
                """);
        if (scope == AssetScope.PROJECT) {
            sql.append("""
                      AND (a.owner_project_id = :projectId OR EXISTS (
                          SELECT 1 FROM asset.library_project_links l
                          WHERE l.asset_id = a.id AND l.project_id = :projectId
                      ))
                    """);
        } else {
            sql.append(" AND a.scope = 'GLOBAL' ");
        }
        Long count = client.sql(sql.toString())
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public Optional<FolderLocation> findAssetFolder(
            String userId,
            String assetId,
            AssetScope scope,
            String projectId
    ) {
        return client.sql("""
                        WITH RECURSIVE ancestors AS (
                            SELECT f.id, f.parent_id, f.name, 0 AS depth
                            FROM asset.library_asset_folders af
                            JOIN asset.library_folders f ON f.id = af.folder_id
                            WHERE af.asset_id = :assetId
                              AND af.owner_user_id = :userId
                              AND af.scope = :scope
                              AND af.owner_project_id IS NOT DISTINCT FROM :projectId
                            UNION ALL
                            SELECT parent.id, parent.parent_id, parent.name, child.depth + 1
                            FROM ancestors child
                            JOIN asset.library_folders parent ON parent.id = child.parent_id
                        )
                        SELECT id, array_agg(name ORDER BY depth DESC) AS path
                        FROM ancestors
                        GROUP BY id
                        """)
                .param("userId", userId)
                .param("assetId", assetId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .query((rs, rowNum) -> new FolderLocation(
                        rs.getString("id"),
                        List.of((String[]) rs.getArray("path").getArray())
                ))
                .optional();
    }

    public void assignAssetsToFolder(
            String userId,
            AssetScope scope,
            String projectId,
            String folderId,
            Collection<String> assetIds
    ) {
        List<String> normalized = assetIds == null ? List.of() : assetIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (normalized.isEmpty()) return;
        client.sql("""
                        DELETE FROM asset.library_asset_folders
                        WHERE owner_user_id = :userId
                          AND scope = :scope
                          AND owner_project_id IS NOT DISTINCT FROM :projectId
                          AND asset_id IN (:assetIds)
                        """)
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .param("assetIds", normalized)
                .update();
        if (folderId == null || folderId.isBlank()) return;
        client.sql("""
                        INSERT INTO asset.library_asset_folders(
                            owner_user_id, scope, owner_project_id, asset_id, folder_id
                        )
                        SELECT :userId, :scope, :projectId, a.id, :folderId
                        FROM asset.library_assets a
                        WHERE a.owner_user_id = :userId
                          AND a.id IN (:assetIds)
                          AND a.status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("scope", scope.name())
                .param("projectId", scope == AssetScope.PROJECT ? projectId : null)
                .param("folderId", folderId)
                .param("assetIds", normalized)
                .update();
    }

    public void removeAssetFolderMappings(String userId, String assetId) {
        client.sql("""
                        DELETE FROM asset.library_asset_folders
                        WHERE owner_user_id = :userId AND asset_id = :assetId
                        """)
                .param("userId", userId)
                .param("assetId", assetId)
                .update();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private AssetSummary hydrate(AssetSummary row) {
        return hydrate(row, row.scope(), row.ownerProjectId());
    }

    private AssetSummary hydrate(AssetSummary row, AssetScope scope, String projectId) {
        Optional<FolderLocation> folder = scope == null
                ? Optional.empty()
                : findAssetFolder(
                        row.ownerUserId(), row.id(), scope,
                        scope == AssetScope.PROJECT ? projectId : null);
        return new AssetSummary(
                row.id(), row.ownerUserId(), row.scope(), row.ownerProjectId(), row.type(), row.aiKind(),
                row.name(), row.description(), row.aiDescription(), row.manualOverride(), row.metadataState(),
                row.status(), row.coverUrl(), row.version(),
                listTags(row.id()), listProjectLinks(row.id()),
                folder.map(FolderLocation::folderId).orElse(null),
                folder.map(FolderLocation::path).orElse(List.of()),
                row.sourceType(), row.sourceId(), row.createdAt(), row.updatedAt()
        );
    }

    private static FolderRow folderRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FolderRow(
                rs.getString("id"),
                rs.getString("owner_user_id"),
                AssetScope.valueOf(rs.getString("scope")),
                rs.getString("owner_project_id"),
                rs.getString("parent_id"),
                rs.getString("name"),
                rs.getString("normalized_name"),
                rs.getInt("sort_order"),
                rs.getLong("version")
        );
    }

    private RowMapper<AssetSummary> assetRowMapper() {
        return (rs, rowNum) -> new AssetSummary(
                rs.getString("id"),
                rs.getString("owner_user_id"),
                AssetScope.valueOf(rs.getString("scope")),
                rs.getString("owner_project_id"),
                AssetType.valueOf(rs.getString("asset_type")),
                rs.getString("ai_kind") == null ? null : AiAssetKind.valueOf(rs.getString("ai_kind")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("ai_description"),
                rs.getBoolean("manual_override"),
                metadataState(rs.getString("metadata_state")),
                AssetStatus.valueOf(rs.getString("status")),
                rs.getString("cover_url"),
                rs.getLong("version"),
                List.of(),
                List.of(),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private static String metadataStateSql() {
        return """
                CASE
                    WHEN a.manual_override THEN 'EDITED'
                    WHEN t.status IN ('PENDING', 'PROCESSING') THEN 'AI_GENERATING'
                    WHEN a.ai_description IS NOT NULL AND btrim(a.ai_description) <> '' THEN 'AI_GENERATED'
                    WHEN t.status = 'DEAD' THEN 'AI_FAILED'
                    ELSE 'NONE'
                END
                """;
    }

    private static MetadataState metadataState(String value) {
        if (value == null || value.isBlank()) return MetadataState.NONE;
        try {
            return MetadataState.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return MetadataState.NONE;
        }
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public record AnalysisTask(String id, String assetId, int attemptCount) {
    }

    public record FolderRow(
            String id,
            String ownerUserId,
            AssetScope scope,
            String ownerProjectId,
            String parentId,
            String name,
            String normalizedName,
            int sortOrder,
            long version
    ) {
    }

    public record FolderLocation(String folderId, List<String> path) {
    }

    public record BlobRow(
            String id,
            String objectKey,
            String sha256,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            Double durationSeconds,
            MediaType mediaType
    ) {
    }

    public record GroupRow(
            String id,
            String assetId,
            String ownerUserId,
            AssetScope scope,
            String ownerProjectId,
            long version,
            String name,
            String description
    ) {
    }

    public record GroupItemWrite(String mediaId, int sortOrder, String role) {
    }

    public record SnapshotRow(
            String id,
            String groupId,
            long groupVersion,
            String contentHash,
            String target,
            OffsetDateTime createdAt
    ) {
    }

    public record UploadSession(
            String id,
            String ownerUserId,
            String projectId,
            String fileName,
            String relativePath,
            String mimeType,
            long sizeBytes,
            String sha256,
            String objectKey,
            String uploadId,
            long partSize,
            OffsetDateTime expiresAt
    ) {
    }

    public record ImportJob(
            String id,
            String ownerUserId,
            String projectId,
            AssetScope scope,
            ImportMode mode,
            String groupId,
            String manifestHash,
            String confirmationHash,
            OffsetDateTime confirmationExpiresAt,
            String status,
            int totalCount,
            long totalBytes,
            int ignoredCount,
            int processedCount,
            int succeededCount,
            int duplicateCount,
            int failedCount,
            OffsetDateTime updatedAt
    ) {
    }

    public record ImportItem(
            String id,
            String jobId,
            String itemKey,
            String relativePath,
            String fileName,
            long sizeBytes,
            String mimeType,
            String sha256,
            String status,
            String assetId,
            String errorMessage,
            int attemptCount,
            int sortOrder
    ) {
    }

    public record CleanupTask(String id, String objectKey, int attemptCount) {
    }
}
