package com.moon.moonagent.service.asset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AssetOutboxEvent;
import com.moon.moonagent.mapper.AssetOutboxMapper;
import com.storyboard.asset.client.AssetContracts;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssetOutputOutboxServiceImpl implements AssetOutputOutboxService {

    private static final Logger log = LoggerFactory.getLogger(AssetOutputOutboxServiceImpl.class);

    /** 沙箱产物统一记为 document，需按 MIME/扩展名再细分库内媒体类型。 */
    private static final Set<String> DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf", "application/json", "application/xml", "application/yaml",
            "application/rtf", "text/rtf", "application/msword", "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/epub+zip"
    );
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "tsv",
            "md", "markdown", "mdown", "mkd", "txt", "text", "rtf", "odt", "ods", "odp",
            "json", "jsonl", "ndjson", "yaml", "yml", "xml", "html", "htm", "log",
            "tex", "rst", "sql", "srt", "vtt", "epub"
    );

    private final AssetOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void enqueue(AgentAsset asset) {
        if (asset == null || asset.getId() == null || asset.getUserId() == null) return;
        if (!List.of("completed", "succeeded").contains(asset.getStatus())) return;
        if (!List.of("image", "video", "audio", "document").contains(asset.getType())) return;

        List<String> urls = resultUrls(asset);
        for (int index = 0; index < urls.size(); index++) {
            try {
                AssetOutboxEvent event = new AssetOutboxEvent();
                event.setEventId(UUID.randomUUID().toString());
                event.setProducer("moon-agent");
                event.setGenerationRecordId(asset.getId());
                event.setResultIndex(index);
                event.setResultAssetId(asset.getId());
                event.setPayloadJson(objectMapper.writeValueAsString(outputEvent(asset, urls.get(index), index)));
                outboxMapper.insertIgnore(event);
            } catch (Exception e) {
                log.warn("生成结果写入项目库 outbox 失败: recordId={}, resultIndex={}, 原因={}",
                        asset.getId(), index, e.getMessage());
            }
        }
    }

    private static AssetContracts.GenerationOutputEvent outputEvent(AgentAsset asset, String url, int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "genType", asset.getGenType());
        put(metadata, "sceneId", asset.getSceneId());
        put(metadata, "conversationId", asset.getConversationId());
        return new AssetContracts.GenerationOutputEvent(
                "moon-agent",
                asset.getUserId(),
                asset.getProjectId(),
                asset.getId(),
                index,
                null,
                asset.getId(),
                url,
                libraryMediaType(asset),
                asset.getPrompt(),
                asset.getModel(),
                metadata
        );
    }

    private static String libraryMediaType(AgentAsset asset) {
        String mime = asset.getMimeType() == null
                ? "" : asset.getMimeType().toLowerCase(Locale.ROOT).split(";", 1)[0].trim();
        if (mime.startsWith("image/")) return "IMAGE";
        if (mime.startsWith("video/")) return "VIDEO";
        if (mime.startsWith("audio/")) return "AUDIO";
        String extension = extensionOf(asset.getUrl());
        if (extension.isEmpty()) extension = extensionOf(asset.getOriginalName());
        if (List.of("png", "jpg", "jpeg", "webp", "gif").contains(extension)) return "IMAGE";
        if (List.of("mp4", "mov", "webm", "mkv").contains(extension)) return "VIDEO";
        if (List.of("mp3", "wav", "m4a", "aac", "ogg", "flac").contains(extension)) return "AUDIO";
        if (DOCUMENT_EXTENSIONS.contains(extension) || DOCUMENT_MIME_TYPES.contains(mime)) return "DOCUMENT";
        return "OTHER";
    }

    private static String extensionOf(String value) {
        if (value == null) return "";
        String clean = value.replace('\\', '/');
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private List<String> resultUrls(AgentAsset asset) {
        if (asset.getResultUrls() != null && !asset.getResultUrls().isBlank()) {
            try {
                return objectMapper.readValue(asset.getResultUrls(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("生成结果 URL 解析失败: recordId={}, 原因={}", asset.getId(), e.getMessage());
            }
        }
        return asset.getUrl() == null || asset.getUrl().isBlank() ? List.of() : List.of(asset.getUrl());
    }

    private static void put(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }
}
