package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetMarkRequest;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.AiAssetKind;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.LibraryOrigin;
import com.storyboard.asset.client.AssetContracts.LibraryGroupInsight;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.OutputConversationSummary;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetUsage;
import com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult;
import com.storyboard.asset.client.AssetContracts.BulkMarkRequest;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.domain.AssetStatePolicy;
import com.storyboard.asset.domain.AssetSearchText;
import com.storyboard.asset.dto.ImportContracts.AnalysisTaskStatus;
import com.storyboard.asset.dto.ImportContracts.AnalysisRequestItem;
import com.storyboard.asset.dto.ImportContracts.AnalysisRequestResult;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.FileAccessUrlSigner;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.AssetPurgeService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Service
public class AssetCatalogServiceImpl implements AssetCatalogService {

    private static final int MAX_BULK_ASSETS = 500;

    private final LibraryRepository repository;
    private final ProjectAccessPort projectAccess;
    private final MediaUrlSigner signer;
    private final FileAccessUrlSigner fileAccessUrlSigner;
    private final AssetPurgeService purgeService;
    private final StorageProperties properties;

    public AssetCatalogServiceImpl(
            LibraryRepository repository,
            ProjectAccessPort projectAccess,
            MediaUrlSigner signer,
            FileAccessUrlSigner fileAccessUrlSigner,
            AssetPurgeService purgeService,
            StorageProperties properties
    ) {
        this.repository = repository;
        this.projectAccess = projectAccess;
        this.signer = signer;
        this.fileAccessUrlSigner = fileAccessUrlSigner;
        this.purgeService = purgeService;
        this.properties = properties;
    }

    @Override
    public PageResponse<AssetSummary> list(
            String userId, AssetScope scope, String projectId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode, boolean deleted, int page, int size
    ) {
        return list(userId, scope, projectId, null, query, type, tagIds, tagMode, deleted, false, null, page, size);
    }

    @Override
    public PageResponse<AssetSummary> list(
            String userId, AssetScope scope, String projectId, String folderId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode, boolean deleted, boolean includeGlobal,
            LibraryOrigin origin, int page, int size
    ) {
        return list(userId, scope, projectId, folderId, query, type, tagIds, tagMode, deleted,
                includeGlobal, origin, null, page, size);
    }

    @Override
    public PageResponse<AssetSummary> list(
            String userId, AssetScope scope, String projectId, String folderId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode, boolean deleted, boolean includeGlobal,
            LibraryOrigin origin, String conversationId, int page, int size
    ) {
        return list(userId, scope, projectId, folderId, query, type, tagIds, tagMode, deleted,
                includeGlobal, origin, conversationId, null, null, null, page, size);
    }

    @Override
    public PageResponse<AssetSummary> list(
            String userId, AssetScope scope, String projectId, String folderId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode, boolean deleted, boolean includeGlobal,
            LibraryOrigin origin, String conversationId, Integer minRating, String flag,
            String colorLabel, int page, int size
    ) {
        if (scope == AssetScope.PROJECT && projectId != null && !projectId.isBlank()) {
            projectAccess.requireOwned(userId, projectId);
        }
        PageResponse<AssetSummary> result;
        boolean hasMarks = (minRating != null && minRating > 0)
                || (flag != null && !flag.isBlank())
                || (colorLabel != null && !colorLabel.isBlank());
        if (hasMarks) {
            result = repository.searchAssets(userId, scope, projectId, folderId, query, type,
                    tagIds, tagMode == null ? TagMode.ALL : tagMode, deleted, includeGlobal, origin,
                    conversationId, minRating, flag, colorLabel, page, size);
        } else if (conversationId == null || conversationId.isBlank()) {
            result = repository.searchAssets(userId, scope, projectId, folderId, query, type,
                    tagIds, tagMode == null ? TagMode.ALL : tagMode, deleted, includeGlobal, origin, page, size);
        } else {
            result = repository.searchAssets(userId, scope, projectId, folderId, query, type,
                    tagIds, tagMode == null ? TagMode.ALL : tagMode, deleted, includeGlobal, origin,
                    conversationId, page, size);
        }
        return new PageResponse<>(
                result.records().stream().map(this::withSignedCover).toList(),
                result.total(), result.page(), result.size());
    }

    @Override
    public List<OutputConversationSummary> listOutputConversations(
            String userId, String projectId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode
    ) {
        return listOutputConversations(userId, projectId, query, type, tagIds, tagMode, null, null, null);
    }

    @Override
    public List<OutputConversationSummary> listOutputConversations(
            String userId, String projectId, String query, AssetType type,
            Collection<String> tagIds, TagMode tagMode,
            Integer minRating, String flag, String colorLabel
    ) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(40001, "请先选择项目");
        }
        projectAccess.requireOwned(userId, projectId);
        return repository.listOutputConversations(userId, projectId, query, type, tagIds,
                        tagMode == null ? TagMode.ALL : tagMode, minRating, flag, colorLabel)
                .stream()
                .map(item -> new OutputConversationSummary(
                        item.conversationId(), item.assetCount(), item.imageCount(),
                        item.videoCount(), item.documentCount(),
                        signCover(item.coverUrl()),
                        item.previewUrls().stream().map(this::signCover).toList(),
                        item.lastAssetName(), item.lastGeneratedAt()))
                .toList();
    }

    @Override
    public AssetDetail get(String userId, String assetId) {
        AssetSummary asset = requireAsset(userId, assetId);
        List<MediaRef> media = repository.listMedia(assetId).stream()
                .map(item -> withSignedUrl(userId, item))
                .toList();
        return new AssetDetail(asset, media, asset.projects());
    }

    @Override
    public AssetDetail findBySource(String userId, String sourceType, String sourceId) {
        AssetSummary asset = repository.findAssetBySource(userId, sourceType, sourceId)
                .orElseThrow(() -> new BusinessException(40401, "来源媒体不存在"));
        List<MediaRef> media = repository.listMedia(asset.id()).stream()
                .map(item -> withSignedUrl(userId, item))
                .toList();
        return new AssetDetail(withSignedCover(asset), media, asset.projects());
    }

    @Override
    public MediaRef getMedia(String userId, String mediaId) {
        MediaRef media = repository.findMedia(userId, mediaId)
                .orElseThrow(() -> new BusinessException(40401, "媒体不存在"));
        return withSignedUrl(userId, media);
    }

    @Override
    @Transactional
    public AssetSummary create(String actorUserId, CreateAssetRequest request, String idempotencyKey) {
        validateCreate(actorUserId, request);
        var existing = repository.findIdempotencyResource(actorUserId, "asset-create", idempotencyKey);
        if (existing.isPresent()) return requireAsset(actorUserId, existing.get());
        AssetScope scope = request.ownerProjectId() == null ? AssetScope.GLOBAL : AssetScope.PROJECT;
        if (scope == AssetScope.PROJECT) projectAccess.requireOwned(actorUserId, request.ownerProjectId());
        AssetType type = parseType(request.type());
        AiAssetKind aiKind = request.aiKind() == null ? null : AiAssetKind.valueOf(request.aiKind().toUpperCase());
        String assetId = repository.insertAsset(
                actorUserId, scope, request.ownerProjectId(), type, aiKind,
                request.name().trim(), trimToNull(request.description()), request.sourceType(), null);
        if (scope == AssetScope.PROJECT) repository.linkProject(actorUserId, assetId, request.ownerProjectId());
        repository.createIdempotencyKey(actorUserId, "asset-create", idempotencyKey, assetId);
        return requireAsset(actorUserId, assetId);
    }

    @Override
    @Transactional
    public AssetSummary update(String userId, String assetId, UpdateAssetRequest request) {
        requireAsset(userId, assetId);
        if (request.expectedVersion() != null) {
            boolean updated = repository.updateAsset(
                    userId, assetId, trimToNull(request.name()), trimToNull(request.description()),
                    trimToNull(request.aiDescription()), Boolean.TRUE.equals(request.manualOverride()),
                    request.expectedVersion());
            if (!updated) throw new BusinessException(40901, "资产已被其他操作修改，请刷新后重试");
        } else {
            AssetSummary current = requireAsset(userId, assetId);
            boolean updated = repository.updateAsset(
                    userId, assetId, trimToNull(request.name()), trimToNull(request.description()),
                    trimToNull(request.aiDescription()), Boolean.TRUE.equals(request.manualOverride()),
                    current.version());
            if (!updated) throw new BusinessException(40901, "资产已被其他操作修改，请刷新后重试");
        }
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public AssetSummary promote(String userId, String assetId, long expectedVersion) {
        requireAsset(userId, assetId);
        if (!repository.promote(userId, assetId, expectedVersion)) {
            throw new BusinessException(40901, "资产版本已变化，无法提升到全局库");
        }
        repository.removeAssetFolderMappings(userId, assetId);
        repository.unlinkAllProjects(assetId);
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public BulkAssetOperationResult bulkPromote(String userId, Collection<String> assetIds) {
        List<AssetSummary> assets = validateBulkAssets(userId, assetIds);
        List<String> updated = new java.util.ArrayList<>(assets.size());
        for (AssetSummary asset : assets) {
            if (asset.scope() == AssetScope.GLOBAL) continue;
            if (!repository.promote(userId, asset.id(), asset.version())) {
                throw new BusinessException(40901, "资产状态已变化，批量提升已取消");
            }
            repository.removeAssetFolderMappings(userId, asset.id());
            repository.unlinkAllProjects(asset.id());
            updated.add(asset.id());
        }
        return new BulkAssetOperationResult(List.copyOf(updated), List.of());
    }

    @Override
    @Transactional
    public BulkAssetOperationResult duplicate(String userId, DuplicateAssetsRequest request) {
        if (request == null || request.assetIds() == null || request.assetIds().isEmpty()) {
            throw new BusinessException(40001, "请选择要复制的资产");
        }
        List<String> sourceIds = request.assetIds().stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (sourceIds.isEmpty()) throw new BusinessException(40001, "请选择要复制的资产");
        if (sourceIds.size() > MAX_BULK_ASSETS) throw new BusinessException(40001, "单次最多复制 500 项资产");

        var targetFolder = request.folderId() == null || request.folderId().isBlank()
                ? null
                : repository.findFolder(userId, request.folderId())
                    .orElseThrow(() -> new BusinessException(40401, "目标文件夹不存在"));

        List<String> updated = new java.util.ArrayList<>(sourceIds.size());
        List<com.storyboard.asset.client.AssetContracts.SkippedAsset> skipped = new java.util.ArrayList<>();
        for (String sourceId : sourceIds) {
            AssetSummary source = repository.findAsset(userId, sourceId)
                    .orElseThrow(() -> new BusinessException(40401, "资产不存在"));
            if (source.status() != AssetStatus.ACTIVE) {
                skipped.add(new com.storyboard.asset.client.AssetContracts.SkippedAsset(sourceId, "资产不可用"));
                continue;
            }
            if (targetFolder != null && (
                    targetFolder.scope() != source.scope()
                    || !java.util.Objects.equals(targetFolder.ownerProjectId(), source.ownerProjectId()))) {
                skipped.add(new com.storyboard.asset.client.AssetContracts.SkippedAsset(sourceId, "不能复制到其他项目文件夹"));
                continue;
            }

            String name = duplicateName(source.name());
            String searchText = AssetSearchText.build(
                    name,
                    source.description(),
                    source.aiDescription(),
                    source.tags().stream().map(tag -> tag.name()).toList()
            );
            String copyId = repository.duplicateAsset(source, name, searchText);
            repository.copyMediaLinks(source.id(), copyId);
            repository.copyAssetTags(source.id(), copyId);
            repository.copyProjectLinks(source.id(), copyId, userId);
            if (source.type() == AssetType.GROUP) repository.duplicateGroup(source.id(), copyId);
            if (targetFolder != null) {
                repository.assignAssetsToFolder(
                        userId, source.scope(), source.ownerProjectId(), targetFolder.id(), List.of(copyId));
            }
            updated.add(copyId);
        }
        return new BulkAssetOperationResult(List.copyOf(updated), List.copyOf(skipped));
    }

    @Override
    @Transactional
    public void linkProject(String userId, String assetId, String projectId) {
        AssetSummary asset = requireAsset(userId, assetId);
        projectAccess.requireOwned(userId, projectId);
        repository.linkProject(userId, assetId, projectId);
    }

    @Override
    @Transactional
    public AssetSummary trash(String userId, String assetId, long expectedVersion) {
        requireAsset(userId, assetId);
        boolean updated = repository.trash(userId, assetId, expectedVersion, OffsetDateTime.now().plusDays(properties.trashRetentionDays()));
        if (!updated) throw new BusinessException(40901, "资产版本已变化，删除失败");
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public AssetSummary restore(String userId, String assetId) {
        AssetSummary asset = requireAsset(userId, assetId);
        if (!AssetStatePolicy.canRestore(asset.status().name())) {
            throw new BusinessException(40901, "当前状态不可恢复");
        }
        if (!repository.restore(userId, assetId)) throw new BusinessException(40901, "恢复失败，请刷新后重试");
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public void purge(String userId, String assetId) {
        AssetSummary asset = requireAsset(userId, assetId);
        if (!AssetStatePolicy.canPurge(asset.status().name())) {
            throw new BusinessException(40901, "只有回收站资产可以彻底删除");
        }
        purgeService.purgeManually(assetId);
    }

    @Override
    @Transactional
    public AssetSummary registerMedia(String userId, MediaRegistration registration, String idempotencyKey) {
        var existing = repository.findIdempotencyResource(userId, "media-register", idempotencyKey);
        if (existing.isPresent()) return requireAsset(userId, existing.get());
        if (registration.scope() == AssetScope.PROJECT) {
            projectAccess.requireOwned(userId, registration.projectId());
        }
        String assetId = repository.insertAsset(
                userId, registration.scope(), registration.projectId(), registration.assetType(), null,
                registration.name().trim(), trimToNull(registration.description()),
                registration.sourceType(), registration.sourceId(),
                registration.conversationId(), registration.sceneId());
        String blobId = repository.upsertBlob(
                registration.objectKey(), registration.sha256(), registration.mimeType(),
                registration.sizeBytes(), registration.width(), registration.height(),
                registration.durationSeconds(), registration.mediaType());
        repository.upsertMedia(
                assetId, blobId, registration.sortOrder(), registration.role(),
                registration.primary(), registration.constraintText());
        String url = "/api/files/" + registration.objectKey();
        repository.updateCover(assetId, url);
        if (registration.scope() == AssetScope.PROJECT) repository.linkProject(userId, assetId, registration.projectId());
        repository.createIdempotencyKey(userId, "media-register", idempotencyKey, assetId);
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public BulkAssetOperationResult bulkTrash(String userId, Collection<String> assetIds) {
        List<AssetSummary> assets = validateBulkAssets(userId, assetIds);
        List<String> updated = new java.util.ArrayList<>(assets.size());
        java.time.OffsetDateTime purgeAfter = OffsetDateTime.now().plusDays(properties.trashRetentionDays());
        for (AssetSummary asset : assets) {
            if (!repository.trash(userId, asset.id(), asset.version(), purgeAfter)) {
                throw new BusinessException(40901, "资产状态已变化，批量删除已取消");
            }
            updated.add(asset.id());
        }
        return new BulkAssetOperationResult(List.copyOf(updated), List.of());
    }

    @Override
    public List<AnalysisTaskStatus> listAnalysisTaskStatuses(String userId, Collection<String> assetIds) {
        return repository.listAnalysisTaskStatuses(userId, assetIds);
    }

    @Override
    @Transactional
    public AnalysisRequestResult requestAnalysis(String userId, Collection<String> assetIds) {
        List<String> normalized = assetIds == null ? List.of() : assetIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().limit(MAX_BULK_ASSETS).toList();
        List<AnalysisRequestItem> queued = new java.util.ArrayList<>(normalized.size());
        int skipped = 0;
        for (String assetId : normalized) {
            AssetSummary asset = repository.findAsset(userId, assetId).orElse(null);
            if (asset == null || asset.status() != AssetStatus.ACTIVE || asset.type() != AssetType.IMAGE) {
                skipped++;
                continue;
            }
            repository.requestAnalysisTask(assetId);
            queued.add(new AnalysisRequestItem(asset.id(), asset.name()));
        }
        return new AnalysisRequestResult(List.copyOf(queued), skipped);
    }

    @Override
    @Transactional
    public AnalysisRequestResult requestFolderAnalysis(
            String userId,
            AssetScope scope,
            String projectId,
            String folderId
    ) {
        AssetScope safeScope = scope == null ? AssetScope.PROJECT : scope;
        if (safeScope == AssetScope.PROJECT) projectAccess.requireOwned(userId, projectId);
        String targetFolderId = "UNFILED".equalsIgnoreCase(folderId) ? null : folderId;
        List<LibraryRepository.FolderScanAsset> candidates = repository.listFolderAnalysisCandidates(
                userId, safeScope, projectId, targetFolderId, MAX_BULK_ASSETS);
        List<AnalysisRequestItem> queued = new java.util.ArrayList<>(candidates.size());
        for (LibraryRepository.FolderScanAsset candidate : candidates) {
            repository.requestAnalysisTask(candidate.id());
            queued.add(new AnalysisRequestItem(candidate.id(), candidate.name()));
        }
        return new AnalysisRequestResult(List.copyOf(queued), 0);
    }

    @Override
    public List<LibraryGroupInsight> listGroupInsights(String userId, String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(40001, "请先选择项目");
        }
        projectAccess.requireOwned(userId, projectId);
        return repository.listGroupInsights(userId, projectId);
    }

    @Override
    @Transactional
    public AssetSummary mark(String userId, String assetId, AssetMarkRequest request) {
        MarkValues marks = normalizeMarks(request == null ? null : request.rating(),
                request == null ? null : request.flag(), request == null ? null : request.colorLabel());
        boolean updated = repository.updateMarks(
                userId, assetId, marks.rating(), marks.flag(), marks.colorLabel());
        if (!updated) throw new BusinessException(40401, "素材不存在或不在可用状态");
        return requireAsset(userId, assetId);
    }

    @Override
    @Transactional
    public BulkAssetOperationResult bulkMark(String userId, BulkMarkRequest request) {
        if (request == null || request.assetIds() == null || request.assetIds().isEmpty()) {
            throw new BusinessException(40001, "请选择要标记的素材");
        }
        List<String> ids = request.assetIds().stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) throw new BusinessException(40001, "请选择要标记的素材");
        if (ids.size() > MAX_BULK_ASSETS) throw new BusinessException(40001, "单次最多标记 500 项素材");
        MarkValues marks = normalizeMarks(request.rating(), request.flag(), request.colorLabel());
        List<String> updated = new java.util.ArrayList<>(ids.size());
        List<com.storyboard.asset.client.AssetContracts.SkippedAsset> skipped =
                new java.util.ArrayList<>();
        for (String id : ids) {
            if (repository.updateMarks(userId, id, marks.rating(), marks.flag(), marks.colorLabel())) {
                updated.add(id);
            } else {
                skipped.add(new com.storyboard.asset.client.AssetContracts.SkippedAsset(id, "素材不可用"));
            }
        }
        return new BulkAssetOperationResult(List.copyOf(updated), List.copyOf(skipped));
    }

    @Override
    public List<AssetSummary> search(String userId, String projectId, String query,
                                     String color, String similarTo, int limit) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(40001, "请先选择项目");
        }
        projectAccess.requireOwned(userId, projectId);
        int size = Math.max(1, Math.min(60, limit));
        if (similarTo != null && !similarTo.isBlank()) {
            AssetSummary source = requireAsset(userId, similarTo);
            return repository.listSearchCandidates(
                            userId, projectId, source.type().name(), source.id(), 400)
                    .stream()
                    .map(candidate -> new ScoredAsset(candidate, similarityScore(source, candidate)))
                    .filter(scored -> scored.score() > 0.01)
                    .sorted(java.util.Comparator.comparingDouble(ScoredAsset::score).reversed())
                    .limit(size)
                    .map(scored -> withSignedCover(scored.asset()))
                    .toList();
        }
        if (color != null && !color.isBlank()) {
            int[] target = parseHex(color);
            if (target == null) throw new BusinessException(40001, "颜色格式应为 #RRGGBB");
            return repository.listColorCandidates(userId, projectId, 500).stream()
                    .map(candidate -> new ScoredColor(
                            candidate.asset(), colorDistance(target, candidate.color())))
                    .filter(scored -> scored.distance() >= 0)
                    .sorted(java.util.Comparator.comparingInt(ScoredColor::distance))
                    .limit(size)
                    .map(scored -> withSignedCover(scored.asset()))
                    .toList();
        }
        if (query == null || query.isBlank()) return List.of();
        return repository.searchAssetsByText(userId, projectId, query.trim(), null, null, size)
                .stream().map(this::withSignedCover).toList();
    }

    private record ScoredColor(AssetSummary asset, int distance) {
    }

    private record ScoredAsset(AssetSummary asset, double score) {
    }

    /** 中文按字符二元组做 Jaccard，名称权重最高；标签重叠再加分。 */
    private static double similarityScore(AssetSummary source, AssetSummary candidate) {
        double score = jaccard(bigrams(source.name()), bigrams(candidate.name())) * 2
                + jaccard(bigrams(source.aiDescription()), bigrams(candidate.aiDescription()))
                + jaccard(bigrams(source.description()), bigrams(candidate.description()));
        java.util.Set<String> sourceTags = source.tags().stream()
                .map(tag -> tag.id()).collect(java.util.stream.Collectors.toSet());
        long overlap = candidate.tags().stream()
                .filter(tag -> sourceTags.contains(tag.id())).count();
        return score + overlap * 0.3;
    }

    private static java.util.Set<String> bigrams(String text) {
        if (text == null || text.isBlank()) return java.util.Set.of();
        String clean = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        java.util.Set<String> grams = new java.util.HashSet<>();
        for (int index = 0; index < clean.length() - 1; index += 1) {
            grams.add(clean.substring(index, index + 2));
        }
        return grams;
    }

    private static double jaccard(java.util.Set<String> first, java.util.Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) return 0;
        long intersection = first.stream().filter(second::contains).count();
        long union = first.size() + second.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    private static int[] parseHex(String color) {
        if (color == null) return null;
        String value = color.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() != 6) return null;
        try {
            return new int[]{
                    Integer.parseInt(value.substring(0, 2), 16),
                    Integer.parseInt(value.substring(2, 4), 16),
                    Integer.parseInt(value.substring(4, 6), 16),
            };
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static int colorDistance(int[] target, String hex) {
        int[] candidate = parseHex(hex);
        if (candidate == null) return -1;
        int red = target[0] - candidate[0];
        int green = target[1] - candidate[1];
        int blue = target[2] - candidate[2];
        return red * red + green * green + blue * blue;
    }

    @Override
    public AssetUsage getUsage(String userId, String assetId) {
        return repository.findAssetUsage(requireAsset(userId, assetId));
    }

    @Override
    public PageResponse<AssetSummary> listUnused(
            String userId, String projectId, int page, int size
    ) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(40001, "请先选择项目");
        }
        projectAccess.requireOwned(userId, projectId);
        PageResponse<AssetSummary> result = repository.searchUnusedAssets(userId, projectId, page, size);
        return new PageResponse<>(
                result.records().stream().map(this::withSignedCover).toList(),
                result.total(), result.page(), result.size());
    }

    private static MarkValues normalizeMarks(Integer rating, String flag, String colorLabel) {        int normalizedRating = rating == null ? 0 : Math.max(0, Math.min(5, rating));
        String normalizedFlag = normalizeFlag(flag);
        String normalizedColor = normalizeColor(colorLabel);
        return new MarkValues(normalizedRating, normalizedFlag, normalizedColor);
    }

    private static String normalizeFlag(String flag) {
        if (flag == null || flag.isBlank()) return null;
        String value = flag.trim().toLowerCase();
        if (!value.equals("pick") && !value.equals("reject")) {
            throw new BusinessException(40001, "旗标只能是 pick 或 reject");
        }
        return value;
    }

    private static String normalizeColor(String colorLabel) {
        if (colorLabel == null || colorLabel.isBlank()) return null;
        String value = colorLabel.trim().toLowerCase();
        if (!List.of("red", "yellow", "green", "blue", "purple").contains(value)) {
            throw new BusinessException(40001, "色标取值不合法");
        }
        return value;
    }

    private record MarkValues(int rating, String flag, String colorLabel) {
    }

    private void validateCreate(String userId, CreateAssetRequest request) {
        if (userId == null || userId.isBlank()) throw new BusinessException(40001, "用户不能为空");
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(40001, "资产名称不能为空");
        }
    }

    private AssetSummary requireAsset(String userId, String assetId) {
        return withSignedCover(repository.findAsset(userId, assetId)
                .orElseThrow(() -> new BusinessException(40401, "资产不存在")));
    }

    private List<AssetSummary> validateBulkAssets(String userId, Collection<String> assetIds) {
        List<String> normalized = assetIds == null ? List.of() : assetIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (normalized.isEmpty()) throw new BusinessException(40001, "请选择资产");
        if (normalized.size() > MAX_BULK_ASSETS) {
            throw new BusinessException(40001, "单次最多操作 500 项资产");
        }
        List<AssetSummary> assets = normalized.stream()
                .map(id -> repository.findAsset(userId, id)
                        .orElseThrow(() -> new BusinessException(40401, "资产不存在")))
                .toList();
        if (assets.stream().anyMatch(asset -> asset.status() != AssetStatus.ACTIVE)) {
            throw new BusinessException(40901, "所选资产中包含不可操作项");
        }
        return assets;
    }

    private static AssetType parseType(String type) {
        try {
            return AssetType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            throw new BusinessException(40001, "资产类型不合法");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String duplicateName(String name) {
        String base = name == null || name.isBlank() ? "未命名素材" : name.trim();
        return base + " - 副本";
    }

    private MediaRef withSignedUrl(String userId, MediaRef media) {
        String token = signer.sign(media.id(), userId, Instant.now().plusSeconds(900));
        return new MediaRef(
                media.id(), media.blobId(), media.type(), media.url(),
                media.contentUrl() + "?token=" + token, media.objectKey(), media.fileName(),
                media.relativePath(), media.mimeType(), media.sizeBytes(), media.width(), media.height(),
                media.durationSeconds(), media.sortOrder(), media.role(), media.primary(), media.constraintText());
    }

    private AssetSummary withSignedCover(AssetSummary asset) {
        if (asset == null || asset.coverUrl() == null || asset.coverUrl().isBlank()) return asset;
        return new AssetSummary(
                asset.id(), asset.ownerUserId(), asset.scope(), asset.ownerProjectId(), asset.type(),
                asset.aiKind(), asset.name(), asset.description(), asset.aiDescription(), asset.manualOverride(),
                asset.metadataState(), asset.status(), fileAccessUrlSigner.signIfLocal(asset.coverUrl()),
                asset.version(), asset.tags(), asset.projects(), asset.folderId(), asset.folderPath(),
                asset.sourceType(), asset.sourceId(), asset.createdAt(), asset.updatedAt(),
                asset.conversationId(), asset.sceneId(), asset.rating(), asset.flag(), asset.colorLabel());
    }

    private String signCover(String coverUrl) {
        if (coverUrl == null || coverUrl.isBlank()) return coverUrl;
        return fileAccessUrlSigner.signIfLocal(coverUrl);
    }
}
