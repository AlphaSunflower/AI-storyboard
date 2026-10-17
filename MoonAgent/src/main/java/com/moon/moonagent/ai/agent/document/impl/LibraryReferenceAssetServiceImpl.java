package com.moon.moonagent.ai.agent.document.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.document.LibraryReferenceAssetService;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.storage.MinioStorage;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.ProjectLinkRef;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 项目库文件 → 会话内 reference 资产的转存实现。
 *
 * <p>实际调用链（每一步都先做归属校验，绝不先下载）：
 * <ol>
 *   <li>归属校验：{@link AssetClient#getAssetInternal(String, String)}（asset-service
 *       {@code GET /api/library/assets/{id}}）<b>带上真实 userId</b> → 服务端按
 *       {@code WHERE a.id = :id AND a.owner_user_id = :userId} 过滤（用户级防 IDOR）→
 *       校验 {@code mediaId} 确实挂在该资产下、且 {@code projectId} 命中
 *       {@code ownerProjectId} / {@code scope=GLOBAL} / {@code linkedProjects} 三选一；
 *       再 {@link AssetClient#getMediaInternal(String, String)}（同样带真实 userId）取
 *       该 mediaId 的对象键与元数据。</li>
 *   <li>取字节：用上一步拿到的 {@code objectKey} 直接读共享对象存储
 *       （{@link MinioStorage}，asset-service 与 MoonAgent 同 bucket {@code storyboard}），
 *       不经浏览器 URL、不走 loopback HTTP、也不需要 asset-service 的签名 token。</li>
 *   <li>落盘 + 落库：{@link FileStorageService#saveGeneratedFile(byte[], String, String)}
 *       写 MinIO {@code files/}，返回 {@code /api/files/files/xxx}；再插入一条
 *       {@code agent_assets}（{@code type=reference}、{@code kind=file}、
 *       {@code conversation_id=conversationId}、带 {@code original_name}/{@code mime_type}）。</li>
 * </ol>
 *
 * <p>幂等判据：{@code agent_assets.idempotency_key = uuid(conversationId + "|library-file|" + mediaId)}
 * 作为主判据（与 {@code SandboxArtifactPublisher} 的幂等键同一列），
 * 另以 {@code conversation_id + request_fingerprint(=内容 SHA-256) + type=reference + kind=file}
 * 兜底复用「同一份内容此前已从别的 mediaId 转存过」的资产行。详见完成说明中的选择理由。
 *
 * <p>失败降级：所有异常都被吞掉并转成 {@code null} + 可诊断 {@code warn} 日志，
 * 绝不中断整轮对话。
 */
@Service
@RequiredArgsConstructor
public class LibraryReferenceAssetServiceImpl implements LibraryReferenceAssetService {

    private static final Logger log = LoggerFactory.getLogger(LibraryReferenceAssetServiceImpl.class);

    /** 引用类型：项目库文件。 */
    private static final String KIND_LIBRARY_FILE = "library_file";
    /** 幂等键命名空间，避免与沙箱产物等其他来源的键相撞。 */
    private static final String IDEMPOTENCY_NAMESPACE = "library-file";
    private static final String LIBRARY_SOURCE = "library";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 允许转存的 MIME 白名单：与 Tika 文档链路（DOCX/XLSX/PPTX/PDF/TXT/MD/CSV/JSON + 图片）对齐。 */
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/rtf",
            "text/rtf",
            "application/epub+zip",
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/tab-separated-values",
            "application/json",
            "application/x-ndjson",
            "application/yaml",
            "application/xml",
            "text/xml",
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
            "image/bmp",
            "image/tiff");

    /** 扩展名兜底：MIME 缺失或不可信时按扩展名判定（判定失败即拒绝转存）。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "epub",
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "ndjson",
            "yaml", "yml", "xml", "log",
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff");

    private final AgentAssetMapper assetMapper;
    private final FileStorageService storageService;
    private final MinioStorage minioStorage;
    private final AssetClient assetClient;

    /** 单个项目库文件可转存的最大字节数；默认 20MB，超限拒绝并记日志。 */
    @Value("${ai.agent.documents.library-reference-max-bytes:20971520}")
    private long maxBytes = 20L * 1024 * 1024;

    @Override
    public AgentAttachmentRequest ensureConversationAsset(String conversationId,
                                                          String userId,
                                                          String projectId,
                                                          AgentReferenceRequest reference) {
        if (conversationId == null || conversationId.isBlank()) {
            log.warn("项目库文件转存跳过: 会话 ID 为空, token={}", token(reference));
            return null;
        }
        if (userId == null || userId.isBlank()) {
            log.warn("项目库文件转存跳过: 操作用户 ID 为空, conversationId={}, token={}",
                    conversationId, token(reference));
            return null;
        }
        if (projectId == null || projectId.isBlank()) {
            log.warn("项目库文件转存跳过: 项目 ID 为空, conversationId={}, token={}",
                    conversationId, token(reference));
            return null;
        }
        if (reference == null || !KIND_LIBRARY_FILE.equals(normalizeKind(reference.kind()))) {
            return null;
        }
        String mediaId = firstNonBlank(reference.mediaId(), reference.id());
        if (mediaId == null) {
            log.warn("项目库文件转存跳过: 缺少 mediaId, conversationId={}, token={}",
                    conversationId, token(reference));
            return null;
        }
        try {
            return transfer(conversationId, userId, projectId, reference, mediaId);
        } catch (Exception error) {
            log.warn("项目库文件转存失败(降级为正文不可用): conversationId={}, projectId={}, mediaId={}, "
                            + "token={}, error={}",
                    conversationId, projectId, mediaId, token(reference), error.toString());
            return null;
        }
    }

    private AgentAttachmentRequest transfer(String conversationId,
                                            String userId,
                                            String projectId,
                                            AgentReferenceRequest reference,
                                            String mediaId) {
        // 1) 归属校验：先确认 assetId 属于该用户、mediaId 确实挂在该资产下、且资产属于该 projectId；
        //    校验不通过绝不下载。
        AssetDetail detail;
        try {
            // 必须带真实 userId（X-User-Id）：asset-service 缺该头时会把调用者鉴权成 "internal"，
            // 于是 `WHERE owner_user_id = 'internal'` 必然查不到，整条转存会静默失效。
            detail = assetClient.getAssetInternal(referenceAssetId(reference), userId);
        } catch (Exception error) {
            log.warn("项目库文件归属校验失败(assetId={}, userId={}, projectId={}): {}",
                    referenceAssetId(reference), userId, projectId, error.toString());
            return null;
        }
        AssetSummary asset = detail == null ? null : detail.asset();
        if (asset == null) {
            log.warn("项目库文件归属校验失败: 资产不存在或不属于该用户, assetId={}, userId={}, projectId={}",
                    referenceAssetId(reference), userId, projectId);
            return null;
        }
        if (!ownsProject(detail, asset, projectId)) {
            log.warn("项目库文件归属校验失败: 资产不属于该项目, assetId={}, userId={}, projectId={}, "
                            + "scope={}, ownerProjectId={}, linked={}",
                    asset.id(), userId, projectId, asset.scope(), asset.ownerProjectId(),
                    linkedProjectIds(detail, asset));
            return null;
        }
        List<MediaRef> assetMedia = detail.media() == null ? List.of() : detail.media();
        if (assetMedia.stream().noneMatch(item -> item != null && mediaId.equals(item.id()))) {
            log.warn("项目库文件归属校验失败: mediaId 不在该资产下, assetId={}, projectId={}, mediaId={}",
                    asset.id(), projectId, mediaId);
            return null;
        }

        // 2) 取项目库素材元数据（objectKey 即共享对象存储里的真实对象键）。
        MediaRef media = assetClient.getMediaInternal(mediaId, userId);
        if (media == null || media.objectKey() == null || media.objectKey().isBlank()) {
            log.warn("项目库文件转存跳过: 素材元数据缺失, conversationId={}, projectId={}, mediaId={}",
                    conversationId, projectId, mediaId);
            return null;
        }

        String mimeType = firstNonBlank(media.mimeType(), reference.mimeType());
        String fileName = resolveFileName(reference, media);
        if (!isSupported(mimeType, fileName)) {
            log.warn("项目库文件转存跳过: MIME/扩展名不支持, conversationId={}, mediaId={}, mimeType={}, name={}",
                    conversationId, mediaId, mimeType, fileName);
            return null;
        }
        long limit = maxBytes > 0 ? maxBytes : 20L * 1024 * 1024;
        if (media.sizeBytes() > limit) {
            log.warn("项目库文件转存跳过: 文件超过上限, conversationId={}, mediaId={}, sizeBytes={}, limit={}",
                    conversationId, mediaId, media.sizeBytes(), limit);
            return null;
        }

        // 3) 取字节：直接读共享对象存储（不经浏览器 URL、不走 loopback HTTP）。
        byte[] bytes = minioStorage.get(media.objectKey());
        if (bytes == null || bytes.length == 0) {
            log.warn("项目库文件转存跳过: 对象不存在或为空, conversationId={}, mediaId={}, objectKey={}",
                    conversationId, mediaId, media.objectKey());
            return null;
        }
        if (bytes.length > limit) {
            log.warn("项目库文件转存跳过: 实际字节超过上限, conversationId={}, mediaId={}, sizeBytes={}, limit={}",
                    conversationId, mediaId, bytes.length, limit);
            return null;
        }

        // 4) 幂等：同一会话 + 同一 mediaId 已有资产时直接复用。
        String contentHash = sha256(bytes);
        String idempotencyKey = idempotencyKey(conversationId, mediaId);
        AgentAsset existing = findExisting(conversationId, idempotencyKey, contentHash);
        if (existing != null) {
            log.debug("项目库文件转存复用既有会话资产: conversationId={}, mediaId={}, assetId={}",
                    conversationId, mediaId, existing.getId());
            return attachment(existing);
        }

        // 5) 落盘 + 落库。
        String assetId = referenceAssetId(reference);
        String url = storageService.saveGeneratedFile(bytes, fileName, mimeType);
        AgentAsset stored = newAsset(conversationId, projectId, mediaId, assetId,
                fileName, mimeType, url, contentHash, idempotencyKey, bytes.length);
        try {
            assetMapper.insert(stored);
        } catch (DataIntegrityViolationException conflict) {
            // 并发引用同一 mediaId 时唯一约束会拦下第二条；复用已存在的行而不是抛出去。
            AgentAsset raced = findExisting(conversationId, idempotencyKey, contentHash);
            if (raced != null) {
                log.debug("项目库文件转存并发冲突后复用既有资产: conversationId={}, mediaId={}, assetId={}",
                        conversationId, mediaId, raced.getId());
                return attachment(raced);
            }
            log.warn("项目库文件转存落库冲突且无法复用: conversationId={}, mediaId={}, error={}",
                    conversationId, mediaId, conflict.getMessage());
            return null;
        }
        log.info("项目库文件已转存为会话资产: conversationId={}, projectId={}, mediaId={}, assetId={}, url={}",
                conversationId, projectId, mediaId, stored.getId(), url);
        return attachment(stored);
    }

    /** 引用里给出的项目库资产 ID（缺省回退引用自身的 id）。 */
    private static String referenceAssetId(AgentReferenceRequest reference) {
        return firstNonBlank(reference.assetId(), reference.id());
    }

    /**
     * 项目归属判据（三选一）：项目级资产命中 {@code ownerProjectId}、本人<b>全局</b>素材、或项目链接列表命中。
     *
     * <p>为什么必须放行 {@code GLOBAL}：前端从不调用 {@code libraryApi.linkProject}
     * （除 {@code api/library.ts} 的定义外零调用点），asset-service 也只在创建
     * {@code scope=PROJECT} 资产时才 link（{@code AssetCatalogServiceImpl#create}），
     * 所以<b>全局素材永远不会出现在 {@code linkedProjects} 里</b>；若不放行，
     * 用户引用自己项目库里的全局文档会被误判越权。
     * 由于上一步已由 asset-service 按 {@code owner_user_id} 过滤保证资产属于本人，
     * 放行 GLOBAL 不引入任何跨用户读取。
     */
    private static boolean ownsProject(AssetDetail detail, AssetSummary asset, String projectId) {
        if (projectId.equals(asset.ownerProjectId())) {
            return true;
        }
        if (asset.scope() == AssetScope.GLOBAL) {
            return true;
        }
        List<ProjectLinkRef> links = detail.linkedProjects() != null
                ? detail.linkedProjects() : asset.projects();
        return links != null && links.stream()
                .anyMatch(link -> link != null && projectId.equals(link.projectId()));
    }

    private static List<String> linkedProjectIds(AssetDetail detail, AssetSummary asset) {
        List<ProjectLinkRef> links = detail.linkedProjects() != null
                ? detail.linkedProjects() : asset.projects();
        return links == null ? List.of()
                : links.stream().filter(java.util.Objects::nonNull)
                        .map(ProjectLinkRef::projectId).toList();
    }

    private static boolean isSupported(String mimeType, String fileName) {
        if (mimeType != null && !mimeType.isBlank()
                && SUPPORTED_MIME_TYPES.contains(mimeType.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        String extension = extensionOf(fileName);
        return !extension.isEmpty() && SUPPORTED_EXTENSIONS.contains(extension);
    }

    private AgentAsset findExisting(String conversationId, String idempotencyKey, String contentHash) {
        AgentAsset byKey = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                .eq(AgentAsset::getType, "reference")
                .eq(AgentAsset::getKind, "file")
                .eq(AgentAsset::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (byKey != null) {
            return byKey;
        }
        if (contentHash == null || contentHash.isBlank()) {
            return null;
        }
        return assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                .eq(AgentAsset::getType, "reference")
                .eq(AgentAsset::getKind, "file")
                .eq(AgentAsset::getRequestFingerprint, contentHash)
                .last("LIMIT 1"));
    }

    private static AgentAsset newAsset(String conversationId,
                                       String projectId,
                                       String mediaId,
                                       String assetId,
                                       String fileName,
                                       String mimeType,
                                       String url,
                                       String contentHash,
                                       String idempotencyKey,
                                       int sizeBytes) {
        AgentAsset asset = new AgentAsset();
        // 显式生成 ID：MyBatis-Plus 的 ASSIGN_UUID 只在真实 insert 时回填，而调用方
        // 需要立刻拿到 assetId 去 read_document，不能依赖落库回填的时机。
        asset.setId(UUID.randomUUID().toString());
        asset.setConversationId(conversationId);
        asset.setProjectId(projectId);
        asset.setType("reference");
        asset.setKind("file");
        asset.setUrl(url);
        asset.setOriginalName(fileName);
        asset.setMimeType(mimeType);
        asset.setStatus("completed");
        asset.setIdempotencyKey(idempotencyKey);
        asset.setRequestFingerprint(contentHash);
        asset.setParams(json(java.util.Map.of(
                "source", LIBRARY_SOURCE,
                "libraryAssetId", assetId,
                "libraryMediaId", mediaId,
                "sizeBytes", sizeBytes)));
        return asset;
    }

    private static AgentAttachmentRequest attachment(AgentAsset asset) {
        String name = asset.getOriginalName() == null || asset.getOriginalName().isBlank()
                ? fileName(asset.getUrl()) : asset.getOriginalName();
        return new AgentAttachmentRequest(
                asset.getUrl(),
                name,
                asset.getMimeType(),
                null,
                "file",
                LIBRARY_SOURCE,
                asset.getId(),
                null);
    }

    private static String idempotencyKey(String conversationId, String mediaId) {
        return UUID.nameUUIDFromBytes(
                (conversationId + "|" + IDEMPOTENCY_NAMESPACE + "|" + mediaId)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String resolveFileName(AgentReferenceRequest reference, MediaRef media) {
        String name = firstNonBlank(media.fileName(), reference.name(), reference.token());
        if (name != null && name.startsWith("@")) {
            name = name.substring(1).strip();
        }
        if (name == null || name.isBlank()) {
            name = "library-file.bin";
        }
        return name;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String fileName(String url) {
        String path = url == null ? "" : url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static String normalizeKind(String kind) {
        return kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
    }

    private static String token(AgentReferenceRequest reference) {
        return reference == null ? "" : String.valueOf(reference.token());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
