package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.orchestration.MediaToolResult;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
public class MediaGenerationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MediaGenerationCoordinator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentAssetMapper assetMapper;
    private final FileStorageService fileStorageService;
    private final VideoGenerationService videoGenerationService;

    private final ExecutorService videoPoller = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.Set<String> pollingTasks = ConcurrentHashMap.newKeySet();

    @Value("${storyboard.internal-url:http://localhost:8082}")
    private String internalBaseUrl;

    public Prepared prepare(AgentConversation conversation, String toolName, String rawInput) {
        return prepare(conversation, toolName, rawInput, toolName + ":" + UUID.randomUUID());
    }

    public Prepared prepare(AgentConversation conversation, String toolName, String rawInput,
                            String idempotencyKey) {
        try {
            ObjectNode input = rawInput == null || rawInput.isBlank()
                    ? JSON.createObjectNode()
                    : (ObjectNode) JSON.readTree(rawInput);
            input.put("projectId", conversation.getProjectId());
            input.put("conversationId", conversation.getId());
            input.put("idempotencyKey", idempotencyKey);
            normalizeUrls(input);
            String normalizedInput = JSON.writeValueAsString(input);
            String fingerprint = sha256(normalizedInput);
            AgentAsset existing = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getIdempotencyKey, idempotencyKey)
                    .last("LIMIT 1"));
            if (existing != null) {
                if (!fingerprint.equals(existing.getRequestFingerprint())) {
                    throw new IllegalArgumentException("idempotencyKey 已被不同媒体请求使用");
                }
                return new Prepared(existing, normalizedInput);
            }

            String mediaType = "generate_video".equals(toolName) ? "video" : "image";
            AgentAsset record = new AssetRecordFactory(
                    conversation, toolName, input, idempotencyKey, fingerprint, mediaType).create();
            try {
                assetMapper.insert(record);
            } catch (DuplicateKeyException race) {
                existing = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                        .eq(AgentAsset::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1"));
                if (existing == null || !fingerprint.equals(existing.getRequestFingerprint())) {
                    throw race;
                }
                return new Prepared(existing, normalizedInput);
            }
            return new Prepared(record, normalizedInput);
        } catch (Exception e) {
            throw new IllegalArgumentException("媒体工具入参解析失败: " + e.getMessage(), e);
        }
    }

    public MediaToolResult complete(Prepared prepared, String rawResult) {
        try {
            Map<String, Object> result = parseToolResult(rawResult);
            AgentAsset record = assetMapper.selectById(prepared.record().getId());
            if (record == null) return toResult(Map.of());
            String status = stringValue(result.getOrDefault("status",
                    "video".equals(record.getType()) ? "queued" : "completed"));
            String taskId = stringValue(result.get("taskId"));
            String actualModel = stringValue(result.get("model"));
            List<String> urls = extractUrls(result);
            if (!urls.isEmpty() && "image".equals(record.getType())) {
                urls = urls.stream().map(this::materializeImage).toList();
            }
            if ("image".equals(record.getType()) && urls.isEmpty() && "completed".equals(status)) {
                throw new IllegalArgumentException("图片生成结果为空");
            }
            record.setStatus("queued".equals(status) ? "queued"
                    : "processing".equals(status) ? "running" : status);
            record.setTaskId(taskId.isBlank() ? null : taskId);
            if (!actualModel.isBlank()) record.setModel(actualModel);
            if (!urls.isEmpty()) {
                record.setUrl(urls.getFirst());
                record.setResultUrls(JSON.writeValueAsString(urls));
            }
            record.setDurationMs(java.time.Duration.between(
                    prepared.record().getCreatedAt() == null ? OffsetDateTime.now() : prepared.record().getCreatedAt(),
                    OffsetDateTime.now()).toMillis());
            assetMapper.updateById(record);
            if ("video".equals(record.getType()) && !taskId.isBlank()) {
                startVideoPolling(taskId);
            }
            Map<String, Object> normalizedResult = new LinkedHashMap<>(result);
            if (!urls.isEmpty()) normalizedResult.put("resultUrls", urls);
            return toResult(normalizedResult);
        } catch (Exception e) {
            log.warn("媒体工具结果更新失败 recordId={}, error={}", prepared.record().getId(), e.getMessage());
            fail(prepared, e);
            throw new IllegalArgumentException("媒体结果保存失败: " + e.getMessage(), e);
        }
    }

    private String materializeImage(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.startsWith("/api/files/")) return value;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return fileStorageService.saveImage(value);
        }
        return fileStorageService.saveImageFromBase64(value);
    }

    private void startVideoPolling(String taskId) {
        if (!pollingTasks.add(taskId)) return;
        videoPoller.submit(() -> {
            try {
                for (int attempt = 0; attempt < 720; attempt++) {
                    try {
                        Map<String, String> status = videoGenerationService.pollVideoTask(taskId);
                        String state = status.getOrDefault("status", "processing");
                        if ("completed".equals(state)) return;
                        if ("failed".equals(state)
                                && !status.getOrDefault("error", "").contains("视频任务查询失败")) return;
                    } catch (Exception e) {
                        log.warn("视频轮询单次失败，继续重试: taskId={}, error={}", taskId, e.getMessage());
                    }
                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                log.warn("视频轮询超过上限: taskId={}", taskId);
            } finally {
                pollingTasks.remove(taskId);
            }
        });
    }

    public void fail(Prepared prepared, Throwable error) {
        try {
            AgentAsset record = assetMapper.selectById(prepared.record().getId());
            if (record == null) return;
            record.setStatus("failed");
            record.setError(error == null ? "unknown error" : error.getMessage());
            assetMapper.updateById(record);
        } catch (Exception e) {
            log.warn("媒体工具失败记录更新失败 recordId={}, error={}", prepared.record().getId(), e.getMessage());
        }
    }

    private void normalizeUrls(ObjectNode input) {
        for (String key : List.of("sourceImages", "referenceImages", "picUrls")) {
            JsonNode node = input.get(key);
            if (node == null || !node.isArray()) continue;
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    // handled below
                } else if (item.isObject()) {
                    String url = item.path("url").asText("");
                    if (url.startsWith("/")) ((ObjectNode) item).put("url", internalBaseUrl + url);
                }
            }
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    JsonNode item = node.get(i);
                    if (item.isTextual() && item.asText("").startsWith("/")) {
                        ((com.fasterxml.jackson.databind.node.ArrayNode) node).set(i,
                                JSON.getNodeFactory().textNode(internalBaseUrl + item.asText()));
                    }
                }
            }
        }
    }

    private static MediaToolResult toResult(Map<String, Object> result) {
        return new MediaToolResult(
                stringValue(result.get("jobId")),
                stringValue(result.get("taskId")),
                stringValue(result.get("mediaType")),
                stringValue(result.get("status")),
                extractUrls(result),
                stringValue(result.get("error")),
                result);
    }

    private static Map<String, Object> parseToolResult(String rawResult) throws Exception {
        if (rawResult == null || rawResult.isBlank()) return Map.of();
        JsonNode root = unwrapMcpContent(JSON.readTree(rawResult));
        if (root == null || root.isNull()) return Map.of();
        if (root.isObject()) {
            return JSON.convertValue(root, new TypeReference<Map<String, Object>>() {});
        }
        if (root.isArray()) {
            return Map.of("data", JSON.convertValue(root, new TypeReference<List<Object>>() {}));
        }
        return Map.of("raw", root.asText());
    }

    /**
     * Spring AI MCP ToolCallback 返回的是 CallToolResult.content 数组，
     * 业务结果位于首个 text item 的 JSON 字符串中。
     */
    private static JsonNode unwrapMcpContent(JsonNode root) throws Exception {
        if (root == null || !root.isArray()) return root;
        for (JsonNode item : root) {
            if (!item.isObject() || !item.hasNonNull("text")) continue;
            String text = item.path("text").asText("");
            if (text.isBlank()) continue;
            String trimmed = text.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return JSON.readTree(trimmed);
            }
        }
        return root;
    }

    private static List<String> extractUrls(Map<String, Object> result) {
        Object resultUrls = result.get("resultUrls");
        if (resultUrls instanceof List<?> list) {
            List<String> normalized = list.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(String::valueOf)
                    .toList();
            if (!normalized.isEmpty()) return normalized;
        }
        List<String> urls = new ArrayList<>();
        Object direct = result.get("url");
        if (direct != null && !String.valueOf(direct).isBlank()) urls.add(String.valueOf(direct));
        Object data = result.get("data");
        if (data instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object url = map.get("url");
                    Object b64 = map.get("b64_json");
                    if (url != null && !String.valueOf(url).isBlank()) urls.add(String.valueOf(url));
                    else if (b64 != null && !String.valueOf(b64).isBlank()) urls.add(String.valueOf(b64));
                }
            }
        }
        return urls.stream().distinct().toList();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    public record Prepared(AgentAsset record, String toolInput) {}

    private static final class AssetRecordFactory {
        private final AgentConversation conversation;
        private final String toolName;
        private final ObjectNode input;
        private final String idempotencyKey;
        private final String fingerprint;
        private final String mediaType;

        private AssetRecordFactory(AgentConversation conversation, String toolName,
                                   ObjectNode input, String idempotencyKey,
                                   String fingerprint, String mediaType) {
            this.conversation = conversation;
            this.toolName = toolName;
            this.input = input;
            this.idempotencyKey = idempotencyKey;
            this.fingerprint = fingerprint;
            this.mediaType = mediaType;
        }

        private AgentAsset create() throws Exception {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setUserId(conversation.getUserId());
            asset.setProjectId(conversation.getProjectId());
            asset.setType(mediaType);
            asset.setPrompt(input.path("prompt").asText(""));
            asset.setModel(input.path("model").asText(""));
            asset.setStatus("generate_video".equals(toolName) ? "queued" : "generating");
            asset.setGenType(genType());
            asset.setParams(JSON.writeValueAsString(params()));
            asset.setReferenceUrls(JSON.writeValueAsString(references()));
            asset.setIdempotencyKey(idempotencyKey);
            asset.setRequestFingerprint(fingerprint);
            asset.setEngineVersion(conversation.getEngineVersion());
            return asset;
        }

        private String genType() {
            if ("generate_video".equals(toolName)) {
                return input.path("referenceImages").isArray() && !input.path("referenceImages").isEmpty()
                        ? "ref_to_video" : "text_to_video";
            }
            String mode = input.path("mode").asText("");
            return "IMAGE_EDIT".equalsIgnoreCase(mode) || !input.path("sourceImages").isEmpty()
                    ? "image_to_image" : "text_to_image";
        }

        private Map<String, Object> params() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String field : List.of("size", "quality", "n", "duration", "aspectRatio", "resolution")) {
                JsonNode value = input.get(field);
                if (value != null && !value.isNull()) out.put(field, value.asText());
            }
            return out;
        }

        private List<Map<String, Object>> references() {
            List<Map<String, Object>> out = new ArrayList<>();
            JsonNode images = input.has("referenceImages") ? input.get("referenceImages") : input.get("sourceImages");
            if (images != null && images.isArray()) {
                for (JsonNode image : images) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "image");
                    if (image.isTextual()) {
                        item.put("url", image.asText());
                    } else {
                        item.put("url", image.path("url").asText(""));
                        item.put("role", image.path("role").asText("REFERENCE"));
                    }
                    out.add(item);
                }
            }
            return out;
        }
    }
}
