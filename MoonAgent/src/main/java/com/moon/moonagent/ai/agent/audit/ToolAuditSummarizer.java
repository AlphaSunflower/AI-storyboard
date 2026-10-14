package com.moon.moonagent.ai.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.agentscope.ToolResultContract;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolAuditSummarizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SAFE_ID_KEYS = List.of(
            "taskId", "assetId", "assetIds", "resultAssetIds", "providerTaskId",
            "runId", "run_id", "shotId", "shot_id", "sceneId", "scene_id",
            "conversationId", "conversation_id", "projectId", "project_id",
            "externalActionKey", "actionKey", "planId", "nodeId");
    private static final List<String> LIST_COUNT_KEYS = List.of(
            "source_asset_ids", "reference_asset_ids", "source_urls", "reference_urls",
            "picUrls", "prompts", "asset_ids", "referenceAssetIds", "referenceUrls");

    public String hash(Object value) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(value == null ? Map.of() : value);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public String inputSummary(String toolName, Object input) {
        Map<String, Object> summary = baseSummary(toolName);
        Map<String, Object> values = asMap(input);
        putSafeValues(summary, values);
        putReferenceCounts(summary, values);
        putPromptSummary(summary, values);
        return json(summary);
    }

    public String outputSummary(String toolName, Object output) {
        Map<String, Object> summary = baseSummary(toolName);
        Map<String, Object> values = asMap(output);
        putSafeValues(summary, values);
        putReferenceCounts(summary, values);
        copy(summary, values, "ok");
        copy(summary, values, "resultStatus");
        copy(summary, values, "truncated");
        copy(summary, values, "status");
        copy(summary, values, "replayed");
        copy(summary, values, "needsClarification");
        copy(summary, values, "rejected");
        copy(summary, values, "preflightDecision");
        copy(summary, values, "preflightTrigger");
        copy(summary, values, "error");
        copy(summary, values, "message");
        copy(summary, values, "referenceFallback");
        copy(summary, values, "referenceAudit");
        return json(summary);
    }

    /**
     * 审计状态。数据库词表保持不变（COMPLETED|WAITING|REJECTED|FAILED|UNKNOWN），
     * 但口径改为与模型一致：契约 {@code ok=false} 一律 FAILED，包括原本被
     * {@code {ok:true, result:{ok:false}}} 掩盖的嵌套失败。
     */
    public String status(Object output) {
        Map<String, Object> values = asMap(output);
        if ("unknown".equalsIgnoreCase(String.valueOf(values.get("status")))) return "UNKNOWN";
        String canonical = ToolResultContract.resultStatusOf(values);
        if (ToolResultContract.REJECTED.equals(canonical)
                || Boolean.TRUE.equals(values.get("rejected"))) {
            return "REJECTED";
        }
        if (ToolResultContract.NEEDS_CONFIRMATION.equals(canonical)
                || Boolean.TRUE.equals(values.get("needsClarification"))) {
            return "WAITING";
        }
        if (Boolean.FALSE.equals(values.get("ok"))
                || ToolResultContract.ERROR.equals(canonical)) {
            return "FAILED";
        }
        return "COMPLETED";
    }

    public String errorCode(Object output) {
        Map<String, Object> values = asMap(output);
        if ("unknown".equalsIgnoreCase(String.valueOf(values.get("status")))) return "EXTERNAL_ACTION_UNKNOWN";
        Object error = values.get("error");
        if (error instanceof Map<?, ?> map) {
            String code = scalar(map.get("code"));
            if (!code.isBlank()) return code;
        }
        if (Boolean.TRUE.equals(values.get("needsClarification"))) return "NEEDS_CLARIFICATION";
        if (Boolean.TRUE.equals(values.get("rejected"))) return "REJECTED";
        if (values.containsKey("ok") && !Boolean.TRUE.equals(values.get("ok"))) return "TOOL_FAILED";
        return "";
    }

    public String errorMessage(Object output) {
        Map<String, Object> values = asMap(output);
        Object error = values.get("error");
        // 契约错误是 Map：只读 message，绝不 String.valueOf 整个 Map
        if (error instanceof Map<?, ?> map) {
            String message = scalar(map.get("message"));
            if (message.isBlank()) message = scalar(values.get("message"));
            return truncate(message, 2000);
        }
        if (error == null || String.valueOf(error).isBlank()) error = values.get("message");
        return truncate(error == null ? "" : String.valueOf(error), 2000);
    }

    public String providerTaskId(Object output) {
        Map<String, Object> values = asMap(output);
        Object taskId = values.get("taskId");
        return taskId == null ? "" : String.valueOf(taskId);
    }

    public List<String> resultAssetIds(Object output) {
        Map<String, Object> values = asMap(output);
        Object raw = values.get("assetIds");
        if (!(raw instanceof Collection<?> collection)) raw = values.get("resultAssetIds");
        if (!(raw instanceof Collection<?> collection)) return List.of();
        return collection.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf)
                .distinct()
                .toList();
    }

    public ReferenceCounts referenceCounts(Object input, Object output) {
        Map<String, Object> out = asMap(output);
        Object audit = out.get("referenceAudit");
        if (audit instanceof Map<?, ?> map) {
            return new ReferenceCounts(
                    integer(map.get("expectedReferenceCount")),
                    integer(map.get("resolvedReferenceCount")),
                    integer(map.get("appliedReferenceCount")));
        }
        Map<String, Object> in = asMap(input);
        int resolved = countList(in, "reference_asset_ids")
                + countList(in, "source_asset_ids")
                + countList(in, "referenceAssetIds");
        int applied = resolved + countList(in, "reference_urls") + countList(in, "source_urls");
        if (resolved == 0 && applied == 0) return new ReferenceCounts(null, null, null);
        return new ReferenceCounts(resolved, resolved, applied);
    }

    private static Map<String, Object> baseSummary(String toolName) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tool", toolName == null ? "" : toolName);
        return summary;
    }

    private static void putSafeValues(Map<String, Object> summary, Map<String, Object> values) {
        for (String key : SAFE_ID_KEYS) copy(summary, values, key);
    }

    private static void putReferenceCounts(Map<String, Object> summary, Map<String, Object> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : LIST_COUNT_KEYS) {
            if (values.containsKey(key)) counts.put(key, countList(values, key));
        }
        if (!counts.isEmpty()) summary.put("referenceCounts", counts);
        Object audit = values.get("referenceAudit");
        if (audit instanceof Map<?, ?> map) {
            summary.put("referenceAudit", sanitizeAuditMap(map));
        }
    }

    private static Map<String, Object> sanitizeAuditMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of(
                "expectedReferenceCount", "resolvedReferenceCount", "appliedReferenceCount",
                "fallbackUsed", "finalRecordFound", "pseudoRecordFound", "mismatch")) {
            if (map.containsKey(key)) out.put(key, map.get(key));
        }
        return out;
    }

    private static void putPromptSummary(Map<String, Object> summary, Map<String, Object> values) {
        Object prompt = values.get("prompt");
        if (prompt == null) return;
        String text = String.valueOf(prompt);
        summary.put("promptPresent", !text.isBlank());
        summary.put("promptLength", text.length());
        summary.put("promptHash", sha256(text));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int countList(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Collection<?> collection) {
            return (int) collection.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .count();
        }
        return 0;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void copy(Map<String, Object> target, Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) return;
        target.put(key, value instanceof CharSequence text ? truncate(text.toString(), 2000) : value);
    }

    /** 只取标量文本，Map / Collection 一律返回空串，避免把容器渲染成 {k=v} 污染审计。 */
    private static String scalar(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) return "";
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return Map.of();
    }

    private static String json(Map<String, Object> summary) {
        try {
            return JSON.writeValueAsString(summary);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record ReferenceCounts(
            Integer expectedReferenceCount,
            Integer resolvedReferenceCount,
            Integer appliedReferenceCount
    ) {
    }
}
