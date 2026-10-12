package com.moon.moonagent.ai.agent.sandbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.asset.AssetOutputOutboxService;
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
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "svg");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "webm", "mov", "mkv");
    private static final long MAX_ARTIFACT_BYTES = 50L * 1024 * 1024;

    private final MoonSandboxClient client;
    private final FileStorageService storage;
    private final AgentAssetMapper assetMapper;
    private final AssetOutputOutboxService outboxService;

    public SandboxArtifactPublisher(MoonSandboxClient client,
                                    FileStorageService storage,
                                    AgentAssetMapper assetMapper,
                                    AssetOutputOutboxService outboxService) {
        this.client = client;
        this.storage = storage;
        this.assetMapper = assetMapper;
        this.outboxService = outboxService;
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
        if (sizeBytes > MAX_ARTIFACT_BYTES) {
            log.warn("Skip oversized sandbox artifact {} from task {}", path, taskId);
            return PublishedArtifacts.EMPTY;
        }
        try {
            byte[] bytes = client.download(conversation.getId(), path);
            String contentHash = sha256(bytes);
            String idempotencyKey = idempotencyKey(conversation.getId(), path, contentHash);
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
            asset.setType(assetType(extension));
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
            outboxService.enqueue(asset);
            return new PublishedArtifacts(List.of(asset.getId()), List.of(url));
        } catch (RuntimeException error) {
            log.warn("Unable to publish sandbox artifact {}: {}", path, error.getMessage());
            return PublishedArtifacts.EMPTY;
        }
    }

    /**
     * Publishes bytes supplied directly by the AgentScope {@code deliver_artifact} tool.
     *
     * <p>Uses the same idempotency key as {@link #publishPath} so the explicit delivery path and
     * the turn-end reconciler collapse onto a single {@code agent_assets} row per content.
     */
    public DeliveryOutcome deliver(AgentConversation conversation,
                                   String taskId,
                                   String sandboxPath,
                                   String fileName,
                                   byte[] bytes,
                                   String suppliedMimeType,
                                   boolean force) {
        if (conversation == null || sandboxPath == null || sandboxPath.isBlank()) {
            return DeliveryOutcome.fail("conversation and sandbox path are required");
        }
        String extension = extension(sandboxPath);
        if (bytes == null) {
            return DeliveryOutcome.fail("artifact content is missing");
        }
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            return DeliveryOutcome.fail("artifact exceeds the size limit");
        }
        try {
            String contentHash = sha256(bytes);
            String idempotencyKey = idempotencyKey(conversation.getId(), sandboxPath, contentHash);
            AgentAsset existing = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getIdempotencyKey, idempotencyKey)
                    .last("LIMIT 1"));
            if (existing != null) {
                return DeliveryOutcome.of(new PublishedArtifacts(
                        List.of(existing.getId()),
                        existing.getUrl() == null ? List.of() : List.of(existing.getUrl())));
            }
            String name = fileName == null || fileName.isBlank() ? fileName(sandboxPath) : fileName;
            if (!force && nameExists(conversation.getId(), name)) {
                return DeliveryOutcome.conflict("artifact '" + name + "' already exists");
            }
            String mime = suppliedMimeType == null || suppliedMimeType.isBlank()
                    ? FileStorageService.contentType(name) : suppliedMimeType;
            String url = storage.saveGeneratedFile(bytes, name, mime);
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setUserId(conversation.getUserId());
            asset.setProjectId(conversation.getProjectId());
            asset.setType(assetType(extension));
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
                    "sandboxPath", sandboxPath,
                    "sizeBytes", bytes.length)));
            asset.setResultUrls(json(List.of(url)));
            assetMapper.insert(asset);
            outboxService.enqueue(asset);
            return DeliveryOutcome.of(
                    new PublishedArtifacts(List.of(asset.getId()), List.of(url)));
        } catch (RuntimeException error) {
            log.warn("Unable to deliver sandbox artifact {}: {}", sandboxPath, error.getMessage());
            return DeliveryOutcome.fail(error.getMessage());
        }
    }

    private boolean nameExists(String conversationId, String name) {
        return assetMapper.selectCount(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, conversationId)
                .eq(AgentAsset::getOriginalName, name)) > 0;
    }

    private static String idempotencyKey(String conversationId, String path, String contentHash) {
        return UUID.nameUUIDFromBytes(
                (conversationId + "|" + path + "|" + contentHash)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
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

    /**
     * Maps a sandbox artifact extension onto the {@code agent_assets.type} vocabulary used by the
     * asset panel. Everything that is not a browser-playable image or video stays a document,
     * including audio, archives and code files.
     */
    static String assetType(String extension) {
        if (IMAGE_EXTENSIONS.contains(extension)) return "image";
        if (VIDEO_EXTENSIONS.contains(extension)) return "video";
        return "document";
    }

    /**
     * Maps a stored artifact MIME type onto the attachment kind understood by the chat UI.
     * Audio, archives and code stay {@code file} so their chips remain clickable downloads.
     */
    public static String attachmentKind(String mimeType) {
        String value = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (value.startsWith("image/")) return "image";
        if (value.startsWith("video/")) return "video";
        return "file";
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

    public record DeliveryOutcome(PublishedArtifacts artifacts, boolean conflict, String error) {
        static DeliveryOutcome of(PublishedArtifacts artifacts) {
            return new DeliveryOutcome(artifacts, false, null);
        }

        static DeliveryOutcome conflict(String error) {
            return new DeliveryOutcome(PublishedArtifacts.EMPTY, true, error);
        }

        static DeliveryOutcome fail(String error) {
            return new DeliveryOutcome(PublishedArtifacts.EMPTY, false, error);
        }
    }
}
