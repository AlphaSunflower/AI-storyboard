package com.moon.moonagent.ai.agent.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentDocumentIndex;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentDocumentIndexMapper;
import com.moon.moonagent.service.FileStorageService;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AgentDocumentService {

    private static final String FILE_PREFIX = "/api/files/files/";

    private final AgentAssetMapper assetMapper;
    private final AgentDocumentIndexMapper indexMapper;
    private final FileStorageService storageService;
    private final DocumentParsingService parsingService;
    private final DocumentSummaryService summaryService;
    private final DocumentProperties properties;
    private final ExecutorService summaryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Cache<String, ParsedDocument> parsedCache;

    public AgentDocumentService(AgentAssetMapper assetMapper,
                                AgentDocumentIndexMapper indexMapper,
                                FileStorageService storageService,
                                DocumentParsingService parsingService,
                                DocumentSummaryService summaryService,
                                DocumentProperties properties) {
        this.assetMapper = assetMapper;
        this.indexMapper = indexMapper;
        this.storageService = storageService;
        this.parsingService = parsingService;
        this.summaryService = summaryService;
        this.properties = properties;
        this.parsedCache = Caffeine.newBuilder()
                .maximumWeight(Math.max(1, properties.getCacheMaxBytes()))
                .weigher((String key, ParsedDocument value) ->
                        Math.max(1, value.charCount() * 2))
                .expireAfterAccess(Duration.ofMinutes(
                        Math.max(1, properties.getCacheExpireMinutes())))
                .build();
    }

    public DocumentReadResult read(String conversationId, String assetId,
                                   int requestedOffset, int requestedMaxChars) {
        AgentAsset asset = requireDocument(conversationId, assetId);
        String fileName = fileName(asset.getUrl());
        ParsedDocument parsed = parsedCache.get(assetId, ignored -> {
            byte[] bytes = storageService.readFile(fileName);
            if (bytes == null || bytes.length == 0) {
                throw new DocumentParseException("not_found", "文档文件不存在或已经删除");
            }
            return parsingService.parse(bytes, fileName);
        });
        if (parsed == null) {
            throw new DocumentParseException("corrupted", "文档解析失败");
        }

        int total = parsed.markdown().length();
        int offset = Math.max(0, Math.min(requestedOffset, total));
        int maxChars = requestedMaxChars <= 0
                ? properties.getDefaultReadChars()
                : Math.min(requestedMaxChars, properties.getMaxReadChars());
        maxChars = Math.max(1, maxChars);
        int end = Math.min(total, offset + maxChars);
        String markdown = parsed.markdown().substring(offset, end);

        AgentDocumentIndex index = indexMapper.selectOne(new LambdaQueryWrapper<AgentDocumentIndex>()
                .eq(AgentDocumentIndex::getAssetId, assetId)
                .eq(AgentDocumentIndex::getConversationId, conversationId)
                .last("LIMIT 1"));
        if (index == null) {
            AgentDocumentIndex created = createIndex(asset, parsed);
            try {
                indexMapper.insert(created);
                index = created;
                scheduleSummary(index.getId(), parsed);
            } catch (DuplicateKeyException ignored) {
                index = indexMapper.selectOne(new LambdaQueryWrapper<AgentDocumentIndex>()
                        .eq(AgentDocumentIndex::getAssetId, assetId)
                        .eq(AgentDocumentIndex::getConversationId, conversationId)
                        .last("LIMIT 1"));
            }
        }

        return new DocumentReadResult(
                assetId,
                documentName(asset, index),
                parsed.mimeType(),
                index.getSummary(),
                index.getStatus(),
                offset,
                end,
                total,
                parsed.truncated(),
                markdown);
    }

    private static String documentName(AgentAsset asset, AgentDocumentIndex index) {
        if (index != null && index.getOriginalName() != null
                && !index.getOriginalName().isBlank()) {
            return index.getOriginalName();
        }
        if (asset.getOriginalName() != null && !asset.getOriginalName().isBlank()) {
            return asset.getOriginalName();
        }
        return fileName(asset.getUrl());
    }

    public List<DocumentSummaryView> recentSummaries(String conversationId, int requestedLimit) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        int limit = requestedLimit <= 0
                ? properties.getRecentSummaryLimit()
                : Math.min(requestedLimit, properties.getRecentSummaryLimit());
        List<AgentAsset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getConversationId, conversationId)
                        .eq(AgentAsset::getType, "reference")
                        .likeRight(AgentAsset::getUrl, FILE_PREFIX)
                        .orderByDesc(AgentAsset::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)));
        if (assets == null || assets.isEmpty()) return List.of();

        List<String> assetIds = assets.stream().map(AgentAsset::getId).toList();
        List<AgentDocumentIndex> indexes = indexMapper.selectList(
                new LambdaQueryWrapper<AgentDocumentIndex>()
                        .eq(AgentDocumentIndex::getConversationId, conversationId)
                        .in(AgentDocumentIndex::getAssetId, assetIds));
        java.util.Map<String, AgentDocumentIndex> indexByAsset = new java.util.HashMap<>();
        if (indexes != null) {
            indexes.forEach(index -> indexByAsset.put(index.getAssetId(), index));
        }

        int remaining = Math.max(0, properties.getRecentSummaryMaxChars());
        java.util.ArrayList<DocumentSummaryView> result = new java.util.ArrayList<>();
        for (AgentAsset asset : assets) {
            if (remaining <= 0) break;
            AgentDocumentIndex index = indexByAsset.get(asset.getId());
            String summary = index == null || index.getSummary() == null
                    ? "" : index.getSummary().strip();
            if (summary.length() > remaining) {
                summary = summary.substring(0, remaining).strip() + "...";
            }
            result.add(new DocumentSummaryView(
                    asset.getId(),
                    documentName(asset, index),
                    asset.getMimeType(),
                    summary,
                    index == null ? "unread"
                            : (index.getStatus() == null ? "unread" : index.getStatus())));
            remaining -= summary.length();
        }
        return List.copyOf(result);
    }

    @PreDestroy
    void shutdown() {
        summaryExecutor.shutdownNow();
    }

    private AgentAsset requireDocument(String conversationId, String assetId) {
        if (conversationId == null || conversationId.isBlank()
                || assetId == null || assetId.isBlank()) {
            throw new DocumentParseException("not_found", "文档不存在");
        }
        AgentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getId, assetId)
                .eq(AgentAsset::getConversationId, conversationId)
                .eq(AgentAsset::getType, "reference")
                .last("LIMIT 1"));
        if (asset == null || asset.getUrl() == null
                || !asset.getUrl().startsWith(FILE_PREFIX)) {
            throw new DocumentParseException("not_found", "文档不存在或不属于当前会话");
        }
        return asset;
    }

    private AgentDocumentIndex createIndex(AgentAsset asset, ParsedDocument parsed) {
        AgentDocumentIndex index = new AgentDocumentIndex();
        index.setId(UUID.randomUUID().toString());
        index.setAssetId(asset.getId());
        index.setConversationId(asset.getConversationId());
        index.setOriginalName(asset.getOriginalName() == null || asset.getOriginalName().isBlank()
                ? fileName(asset.getUrl()) : asset.getOriginalName());
        index.setMimeType(parsed.mimeType());
        index.setContentHash(sha256(parsed.markdown()));
        index.setCharCount(parsed.charCount());
        index.setStatus("summarizing");
        index.setParserVersion(DocumentParsingService.PARSER_VERSION);
        index.setCreatedAt(OffsetDateTime.now());
        index.setUpdatedAt(OffsetDateTime.now());
        return index;
    }

    private void scheduleSummary(String indexId, ParsedDocument parsed) {
        summaryExecutor.submit(() -> {
            SummaryResult result = summaryService.summarize(parsed.markdown());
            AgentDocumentIndex update = new AgentDocumentIndex();
            update.setId(indexId);
            update.setSummary(result.summary());
            update.setStatus(result.status());
            update.setUpdatedAt(OffsetDateTime.now());
            indexMapper.updateById(update);
        });
    }

    private static String fileName(String url) {
        String path = url == null ? "" : url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if (!path.startsWith(FILE_PREFIX) || path.length() <= FILE_PREFIX.length()) {
            throw new DocumentParseException("unsupported", "文档存储路径不合法");
        }
        return path.substring(FILE_PREFIX.length());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
