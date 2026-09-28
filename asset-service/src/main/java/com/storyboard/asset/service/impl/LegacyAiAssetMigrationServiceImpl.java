package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AiAssetKind;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.domain.ObjectKeys;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.LegacyAiAssetMigrationService;
import com.storyboard.asset.storage.ObjectStorage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Service
public class LegacyAiAssetMigrationServiceImpl implements LegacyAiAssetMigrationService {

    private final JdbcClient jdbc;
    private final LibraryRepository repository;
    private final ObjectStorage storage;

    public LegacyAiAssetMigrationServiceImpl(
            JdbcClient jdbc,
            LibraryRepository repository,
            ObjectStorage storage
    ) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.storage = storage;
    }

    @Override
    @Transactional
    public int migrate(String ownerUserId) {
        if (!legacyTablesExist()) return 0;
        String where = ownerUserId == null || ownerUserId.isBlank() ? "" : " WHERE user_id = :ownerUserId";
        var statement = jdbc.sql("""
                        SELECT id, user_id, project_id, type, name, description
                        FROM public.assets
                        """ + where);
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            statement = statement.param("ownerUserId", ownerUserId);
        }
        List<LegacyAsset> assets = statement.query((rs, rowNum) -> new LegacyAsset(
                rs.getString("id"), rs.getString("user_id"), rs.getString("project_id"),
                rs.getString("type"), rs.getString("name"), rs.getString("description"))).list();
        int migrated = 0;
        for (LegacyAsset asset : assets) {
            if (repository.findAsset(asset.ownerUserId(), asset.id()).isPresent()) continue;
            AssetScope scope = asset.projectId() == null || asset.projectId().isBlank()
                    ? AssetScope.GLOBAL : AssetScope.PROJECT;
            String created = repository.insertAssetWithId(
                    asset.id(), asset.ownerUserId(), scope, asset.projectId(), AssetType.GROUP,
                    aiKind(asset.type()), asset.name(), asset.description(),
                    "legacy-ai-asset", asset.id());
            if (created == null) continue;
            repository.insertGroup(asset.id(), asset.ownerUserId(), scope, asset.projectId());
            if (scope == AssetScope.PROJECT) repository.linkProject(asset.ownerUserId(), asset.id(), asset.projectId());

            List<LegacyImage> images = jdbc.sql("""
                            SELECT id, url, file_name, text, sort_order
                            FROM public.asset_images
                            WHERE asset_id = :assetId
                            ORDER BY sort_order, created_at
                            """)
                    .param("assetId", asset.id())
                    .query((rs, rowNum) -> new LegacyImage(
                            rs.getString("id"), rs.getString("url"), rs.getString("file_name"),
                            rs.getString("text"), rs.getInt("sort_order")))
                    .list();
            for (LegacyImage image : images) {
                String objectKey = ObjectKeys.fromUrl(image.url());
                if (objectKey == null) continue;
                long size = 0;
                try {
                    size = storage.contentLength(objectKey);
                } catch (Exception ignored) {
                    // 历史文件可能仍在旧上传目录；保留媒体记录，内容迁移后再补齐。
                }
                String blobId = repository.upsertBlob(
                        objectKey,
                        LibraryRepository.sha256Hex(image.url().getBytes(StandardCharsets.UTF_8)),
                        mimeType(image.url()),
                        Math.max(0, size),
                        null, null, null, MediaType.IMAGE);
                String mediaId = repository.upsertMedia(
                        asset.id(), blobId, image.sortOrder(), MediaRole.REFERENCE,
                        image.sortOrder() == 0, image.text());
                repository.linkGroupItem(asset.id(), mediaId, asset.id(), image.sortOrder());
                if (image.sortOrder() == 0) repository.updateCover(asset.id(), image.url());
            }
            migrated++;
        }
        return migrated;
    }

    private boolean legacyTablesExist() {
        return jdbc.sql("SELECT to_regclass('public.assets') IS NOT NULL")
                .query(Boolean.class).single();
    }

    private static AiAssetKind aiKind(String type) {
        if (type == null) return null;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "character" -> AiAssetKind.CHARACTER;
            case "prop" -> AiAssetKind.PROP;
            case "scene" -> AiAssetKind.SCENE;
            default -> null;
        };
    }

    private static String mimeType(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private record LegacyAsset(
            String id,
            String ownerUserId,
            String projectId,
            String type,
            String name,
            String description
    ) {
    }

    private record LegacyImage(String id, String url, String fileName, String text, int sortOrder) {
    }
}
