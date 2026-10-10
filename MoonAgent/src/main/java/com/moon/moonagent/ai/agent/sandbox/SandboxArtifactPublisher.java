package com.moon.moonagent.ai.agent.sandbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class SandboxArtifactPublisher {

    private static final Logger log = LoggerFactory.getLogger(SandboxArtifactPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED = Set.of(
            "md", "markdown", "txt", "log", "csv", "json", "docx", "xlsx", "pdf",
            "png", "jpg", "jpeg", "svg");
    private static final long MAX_ARTIFACT_BYTES = 50L * 1024 * 1024;

    private final MoonSandboxClient client;
    private final FileStorageService storage;
    private final AgentAssetMapper assetMapper;

    public SandboxArtifactPublisher(MoonSandboxClient client,
                                    FileStorageService storage,
                                    AgentAssetMapper assetMapper) {
        this.client = client;
        this.storage = storage;
        this.assetMapper = assetMapper;
    }

    public PublishedArtifacts publish(AgentConversation conversation,
                                      String taskId,
                                      SandboxExecutionResult execution) {
        if (conversation == null || execution == null || execution.files() == null) {
            return PublishedArtifacts.EMPTY;
        }
        List<String> assetIds = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (SandboxFileView file : execution.files()) {
            PublishedArtifacts published = publishPath(
                    conversation, taskId, file.path(), file.sizeBytes(), file.mimeType());
            assetIds.addAll(published.assetIds());
            urls.addAll(published.urls());
        }
        return new PublishedArtifacts(List.copyOf(assetIds), List.copyOf(urls));
    }

    public PublishedArtifacts publishPath(AgentConversation conversation,
                                          String taskId,
                                          String path,
                                          long sizeBytes,
                                          String suppliedMimeType) {
        if (conversation == null || path == null || path.isBlank()) {
            return PublishedArtifacts.EMPTY;
        }
        String extension = extension(path);
        if (!ALLOWED.contains(extension)) {
            log.debug("Skip unsupported sandbox artifact {}", path);
            return PublishedArtifacts.EMPTY;
        }
        if (sizeBytes > MAX_ARTIFACT_BYTES) {
            log.warn("Skip oversized sandbox artifact {} from task {}", path, taskId);
            return PublishedArtifacts.EMPTY;
        }
        try {
            byte[] bytes = client.download(conversation.getId(), path);
            String contentHash = sha256(bytes);
            String idempotencyKey = UUID.nameUUIDFromBytes(
                    (conversation.getId() + "|" + path + "|" + contentHash)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString();
            AgentAsset existing = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getIdempotencyKey, idempotencyKey)
                    .last("LIMIT 1"));
            if (existing != null) {
                return new PublishedArtifacts(
                        List.of(existing.getId()),
                        existing.getUrl() == null ? List.of() : List.of(existing.getUrl()));
            }
            String name = fileName(path);
            String mime = suppliedMimeType == null || suppliedMimeType.isBlank()
                    ? FileStorageService.contentType(name) : suppliedMimeType;
            String url = storage.saveGeneratedFile(bytes, name, mime);
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setUserId(conversation.getUserId());
            asset.setProjectId(conversation.getProjectId());
            asset.setType(isImage(extension) ? "image" : "document");
            asset.setUrl(url);
            asset.setOriginalName(name);
            asset.setMimeType(mime);
            asset.setPrompt("由 AgentScope 沙箱生成");
            asset.setStatus("completed");
            asset.setTaskId(taskId);
            asset.setGenType("sandbox_generated");
            asset.setIdempotencyKey(idempotencyKey);
            asset.setRequestFingerprint(contentHash);
            asset.setParams(json(Map.of(
                    "sandboxPath", path,
                    "sizeBytes", bytes.length)));
            asset.setResultUrls(json(List.of(url)));
            assetMapper.insert(asset);
            return new PublishedArtifacts(List.of(asset.getId()), List.of(url));
        } catch (RuntimeException error) {
            log.warn("Unable to publish sandbox artifact {}: {}", path, error.getMessage());
            return PublishedArtifacts.EMPTY;
        }
    }

    public static Map<String, Object> executionResult(SandboxExecutionResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("exitCode", result.exitCode());
        value.put("stdout", result.stdout());
        value.put("stderr", result.stderr());
        value.put("stdoutTruncated", result.stdoutTruncated());
        value.put("stderrTruncated", result.stderrTruncated());
        value.put("timedOut", result.timedOut());
        value.put("durationMs", result.durationMs());
        value.put("files", result.files());
        value.put("content", result.stdout());
        return value;
    }

    private static boolean isImage(String extension) {
        return Set.of("png", "jpg", "jpeg", "svg").contains(extension);
    }

    private static String extension(String path) {
        String name = fileName(path).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String fileName(String path) {
        String value = path == null ? "artifact.bin" : path.replace('\\', '/');
        return value.substring(value.lastIndexOf('/') + 1);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public record PublishedArtifacts(List<String> assetIds, List<String> urls) {
        public static final PublishedArtifacts EMPTY = new PublishedArtifacts(List.of(), List.of());
    }
}
