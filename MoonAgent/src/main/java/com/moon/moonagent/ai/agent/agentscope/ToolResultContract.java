package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具结果输出契约：所有工具返回值都必须在唯一收口点被归一为固定信封结构，
 * 避免“逻辑失败被 {@code ok:true} 伪装成成功”。
 *
 * <p>信封固定字段：
 * <ul>
 *   <li>{@code ok}：布尔，始终存在；只有 {@code ok|queued|partial} 三种状态为 {@code true}。</li>
 *   <li>{@code resultStatus}：{@code ok|queued|needs_confirmation|rejected|partial|error} 之一，始终存在。</li>
 *   <li>{@code error}：仅当 {@code ok=false} 时存在，结构为 {@code {code, message, hint?}}。</li>
 *   <li>工具原有 payload 键全部保留（{@code ok}/{@code resultStatus}/{@code error} 由契约接管），
 *       保证既有调用方与模型可见字段不回归。</li>
 * </ul>
 *
 * <p>本类不引用任何 AgentScope 类型，纯静态方法，可直接单元测试。
 */
public final class ToolResultContract {

    /** 状态：成功。 */
    public static final String OK = "ok";
    /** 状态：已受理/排队中（异步任务）。 */
    public static final String QUEUED = "queued";
    /** 状态：需要用户确认（HITL 或澄清）。 */
    public static final String NEEDS_CONFIRMATION = "needs_confirmation";
    /** 状态：已被拒绝。 */
    public static final String REJECTED = "rejected";
    /** 状态：部分完成。 */
    public static final String PARTIAL = "partial";
    /** 状态：失败。 */
    public static final String ERROR = "error";

    /** 工具无输出。 */
    public static final String CODE_EMPTY_TOOL_RESULT = "EMPTY_TOOL_RESULT";
    /** 结果无法序列化。 */
    public static final String CODE_SERIALIZATION_FAILED = "SERIALIZATION_FAILED";
    /** 结果看起来是信封 JSON，但解析失败。 */
    public static final String CODE_UNPARSEABLE_TOOL_RESULT = "UNPARSEABLE_TOOL_RESULT";
    /** 通用工具失败。 */
    public static final String CODE_TOOL_FAILED = "TOOL_FAILED";
    /** 需要澄清（沿用既有审计错误码）。 */
    public static final String CODE_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    /** 让用户做选择的暂停态（如"已有分镜，请选择追加/覆盖/删除"）——不是失败。 */
    public static final String CODE_NEEDS_CHOICE = "NEEDS_CHOICE";
    /** 已被拒绝（沿用既有审计错误码）。 */
    public static final String CODE_REJECTED = "REJECTED";

    /** 序列化上限，与 {@code AgentScopeProperties.toolResultEvictionMaxChars} 默认值保持一致。 */
    public static final int DEFAULT_MAX_CHARS = 80_000;

    private static final String RESULT_STATUS = "resultStatus";
    private static final int MAX_NESTING_DEPTH = 3;
    private static final Set<String> STATUS_VOCABULARY = Set.of(
            OK, QUEUED, NEEDS_CONFIRMATION, REJECTED, PARTIAL, ERROR);
    private static final Set<String> QUEUED_STATUSES = Set.of(
            "queued", "pending", "running", "processing", "accepted", "submitted", "in_progress", "in-progress");
    private static final Set<String> PARTIAL_STATUSES = Set.of(
            "partial", "partially_completed", "incomplete");
    /** 嵌套失败被提升时向外层复制的可读字段。 */
    private static final List<String> PROMOTED_KEYS = List.of(
            "error", "message", "reason", "hint", "code", "errorCode");
    private static final Set<String> CANONICAL_KEYS = Set.of("ok", RESULT_STATUS, "error");
    private static final List<String> MINIMAL_KEYS = List.of(
            "tool", "ok", RESULT_STATUS, "error", "truncated", "previewChars", "ref", "result");
    private static final String TRUNCATION_MARKER = "\n……（已截断 %d 字符，完整结果见 ref）……\n";

    /** 严格 ObjectMapper：不接受降级序列化，时间类型按 ISO-8601 输出（不可回归红线 §3）。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private ToolResultContract() {
    }

    /**
     * 把任意工具返回值归一为规范信封。
     *
     * @param toolName  工具名，仅用于信封的 {@code tool} 字段与截断定位符；可为空
     * @param rawOutput 工具原始返回值，可为 {@code null}
     * @return 始终非 {@code null} 的规范信封
     */
    public static Map<String, Object> normalize(String toolName, Object rawOutput) {
        if (rawOutput == null) {
            return errorEnvelope(toolName, CODE_EMPTY_TOOL_RESULT, "工具没有返回任何结果。",
                    "请检查入参后重试；若该工具本应返回内容，请向用户说明工具无输出并改用其它工具。");
        }
        if (rawOutput instanceof CharSequence text) {
            String value = text.toString();
            if (value.isBlank()) {
                return errorEnvelope(toolName, CODE_EMPTY_TOOL_RESULT, "工具返回了空文本结果。",
                        "请检查入参后重试；若该工具本应返回内容，请改用其它工具或直接向用户说明。");
            }
            Map<String, Object> out = envelope(toolName, OK);
            out.put("result", value);
            return out;
        }
        if (rawOutput instanceof Map<?, ?> map) {
            return normalizeMap(toolName, stringKeys(map));
        }
        Map<String, Object> out = envelope(toolName, OK);
        out.put("result", rawOutput);
        return out;
    }

    /**
     * 严格序列化（封顶 {@link #DEFAULT_MAX_CHARS}）。
     *
     * <p>序列化失败时绝不退化到 {@code String.valueOf}，而是返回带
     * {@link #CODE_SERIALIZATION_FAILED} 的合法 JSON；超长时把 {@code result}
     * 换成头尾预览并保留合法 JSON，同时写入 {@code truncated}/{@code previewChars}/{@code ref}。
     *
     * @param normalized 已归一（或未归一）的结果信封
     * @return 始终合法的 JSON 文本
     */
    public static String serialize(Map<String, Object> normalized) {
        return serialize(normalized, DEFAULT_MAX_CHARS);
    }

    /**
     * 严格序列化（自定义上限），语义同 {@link #serialize(Map)}。
     *
     * @param normalized 已归一（或未归一）的结果信封
     * @param maxChars   结果字符上限，小于 1000 时按 1000 处理
     * @return 始终合法的 JSON 文本
     */
    public static String serialize(Map<String, Object> normalized, int maxChars) {
        Map<String, Object> normal = normalized == null
                ? normalize(null, null) : new LinkedHashMap<>(normalized);
        if (!normal.containsKey(OK) || !normal.containsKey(RESULT_STATUS)) {
            normal = normalize(nameOf(normal), normal);
        }
        int cap = Math.max(1_000, maxChars);
        String json = writeJson(normal);
        if (json == null) return serializationFailure(normal);
        if (json.length() <= cap) return json;
        return serializeTruncated(normal, cap);
    }

    /**
     * 从未归一或已归一的结果中解出规范状态，供审计与模型口径一致。
     *
     * @param output 工具结果（原始 map、已归一信封或其它类型）
     * @return {@code ok|queued|needs_confirmation|rejected|partial|error} 之一
     */
    public static String resultStatusOf(Object output) {
        if (output instanceof Map<?, ?> map) return resolveStatus(stringKeys(map));
        Object status = normalize(null, output).get(RESULT_STATUS);
        return status == null ? ERROR : String.valueOf(status);
    }

    /**
     * 廉价判定：该 Map 是否已经是本契约的规范信封，可安全跳过再次 {@link #normalize}。
     *
     * <p>只认结构，不做任何拷贝或归一：{@code ok} 必须是布尔、{@code resultStatus} 必须是
     * 契约词表内的字符串。任一不满足都返回 {@code false}，调用方必须继续走 {@code normalize}，
     * 因此“看起来像信封但不是”的输入不会被放行成非法结果。
     *
     * @param output 候选结果，可为 {@code null}
     * @return {@code true} 表示已是规范信封，无需再归一
     */
    public static boolean isCanonical(Map<?, ?> output) {
        if (output == null) return false;
        if (!(output.get(OK) instanceof Boolean)) return false;
        return statusValue(output.get(RESULT_STATUS)) != null;
    }

    private static Map<String, Object> normalizeMap(String toolName, Map<String, Object> source) {
        Map<String, Object> payload = new LinkedHashMap<>(source);
        // 未显式失败时才需要检查“被 ok:true 掩盖”的嵌套失败
        if (!Boolean.FALSE.equals(bool(payload.get(OK)))) {
            Map<String, Object> nested = firstNestedFailure(payload);
            if (nested != null) {
                payload.put(OK, Boolean.FALSE);
                promote(payload, nested);
            }
        }
        Boolean explicitOk = bool(payload.get(OK));
        String status = resolveStatus(payload);
        boolean ok = !Boolean.FALSE.equals(explicitOk) && isSuccess(status);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(OK, Boolean.valueOf(ok));
        String name = toolName == null || toolName.isBlank() ? nameOf(payload) : toolName;
        if (name != null && !name.isBlank()) out.put("tool", name);
        out.put(RESULT_STATUS, status);
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (CANONICAL_KEYS.contains(entry.getKey())) continue;
            out.put(entry.getKey(), entry.getValue());
        }
        if (!ok) out.put("error", errorMap(status, payload));
        return out;
    }

    /** 沿 {@code result} 链下钻，返回第一个显式 {@code ok:false} 的内层 map（最多 3 层）。 */
    private static Map<String, Object> firstNestedFailure(Map<String, Object> values) {
        Map<String, Object> cursor = values;
        for (int depth = 0; depth < MAX_NESTING_DEPTH; depth++) {
            Object nested = cursor.get("result");
            if (!(nested instanceof Map<?, ?> nestedMap)) return null;
            Map<String, Object> inner = stringKeys(nestedMap);
            if (Boolean.FALSE.equals(bool(inner.get(OK)))) return inner;
            cursor = inner;
        }
        return null;
    }

    /** 把内层失败的可读信息提升到外层，外层已有非空值时不覆盖。结果明细仍保留在 {@code result}。 */
    private static void promote(Map<String, Object> payload, Map<String, Object> failure) {
        for (String key : PROMOTED_KEYS) {
            Object value = failure.get(key);
            if (value == null || String.valueOf(value).isBlank()) continue;
            Object current = payload.get(key);
            if (current != null && !String.valueOf(current).isBlank()) continue;
            payload.put(key, value);
        }
        if (Boolean.TRUE.equals(bool(failure.get("rejected")))) payload.put("rejected", Boolean.TRUE);
        if (Boolean.TRUE.equals(bool(failure.get("needsClarification")))) {
            payload.put("needsClarification", Boolean.TRUE);
        }
    }

    private static String resolveStatus(Map<String, Object> values) {
        Boolean ok = bool(values.get(OK));
        String declared = statusValue(values.get(RESULT_STATUS));
        if (declared != null) {
            return Boolean.FALSE.equals(ok) && OK.equals(declared) ? ERROR : declared;
        }
        if (Boolean.TRUE.equals(bool(values.get("rejected")))) return REJECTED;
        if (Boolean.TRUE.equals(bool(values.get("needsClarification")))) return NEEDS_CONFIRMATION;
        // needsChoice 同样是"等用户选择"的暂停态，不是失败：否则审计会把每一次
        // "已有分镜，请选择追加/覆盖/删除"都记成 FAILED，污染失败率口径。
        if (Boolean.TRUE.equals(bool(values.get("needsChoice")))) return NEEDS_CONFIRMATION;
        if (!Boolean.FALSE.equals(ok)) {
            Map<String, Object> nested = firstNestedFailure(values);
            if (nested != null) return resolveStatus(nested);
        }
        String status = text(values.get("status"));
        String normalized = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (Boolean.TRUE.equals(ok)) {
            if (PARTIAL_STATUSES.contains(normalized)) return PARTIAL;
            if (QUEUED_STATUSES.contains(normalized)) return QUEUED;
            return OK;
        }
        if (Boolean.FALSE.equals(ok)) return ERROR;
        if (PARTIAL_STATUSES.contains(normalized)) return PARTIAL;
        if (QUEUED_STATUSES.contains(normalized)) return QUEUED;
        if (hasText(values.get("error"))) return ERROR;
        return OK;
    }

    private static Map<String, Object> errorMap(String status, Map<String, Object> values) {
        Map<String, Object> error = new LinkedHashMap<>();
        Object raw = values.get("error");
        if (raw instanceof Map<?, ?> map) {
            stringKeys(map).forEach((key, value) -> {
                if (value != null) error.put(key, value);
            });
        }
        String message = firstText(
                text(error.get("message")),
                text(raw instanceof Map ? null : raw),
                text(values.get("message")),
                text(values.get("reason")),
                text(error.get("hint")),
                text(values.get("hint")));
        String code = firstText(
                text(error.get("code")),
                text(values.get("code")),
                text(values.get("errorCode")),
                text(error.get("errorCode")),
                Boolean.TRUE.equals(bool(values.get("needsChoice")))
                        ? CODE_NEEDS_CHOICE : defaultCode(status));
        error.put("code", code);
        error.put("message", message == null ? defaultMessage(status) : message);
        if (!error.containsKey("hint")) {
            String hint = firstText(text(error.get("hint")), text(values.get("hint")));
            if (hint != null) {
                error.put("hint", hint);
            } else if (message == null) {
                error.put("hint", defaultHint(status));
            }
        }
        return error;
    }

    private static String defaultCode(String status) {
        if (NEEDS_CONFIRMATION.equals(status)) return CODE_NEEDS_CLARIFICATION;
        if (REJECTED.equals(status)) return CODE_REJECTED;
        return CODE_TOOL_FAILED;
    }

    private static String defaultMessage(String status) {
        if (NEEDS_CONFIRMATION.equals(status)) return "该操作需要用户先确认，尚未执行。";
        if (REJECTED.equals(status)) return "该操作已被用户拒绝，本轮不再执行。";
        return "工具执行失败，但未返回可读的错误说明。";
    }

    private static String defaultHint(String status) {
        if (NEEDS_CONFIRMATION.equals(status)) {
            return "请先向用户说明需要确认的内容，等用户确认后再调用该工具。";
        }
        if (REJECTED.equals(status)) {
            return "不要重试该工具，改用其它方案或直接向用户说明已取消。";
        }
        return "不要用相同入参重复提交；请检查入参、改用其它工具，或向用户说明失败原因。";
    }

    private static Map<String, Object> envelope(String toolName, String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(OK, Boolean.valueOf(isSuccess(status)));
        if (toolName != null && !toolName.isBlank()) out.put("tool", toolName);
        out.put(RESULT_STATUS, status);
        return out;
    }

    private static Map<String, Object> errorEnvelope(String toolName, String code,
                                                     String message, String hint) {
        Map<String, Object> out = envelope(toolName, ERROR);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (hint != null && !hint.isBlank()) error.put("hint", hint);
        out.put("error", error);
        return out;
    }

    private static String serializeTruncated(Map<String, Object> normal, int cap) {
        String full = rawText(normal.get("result"));
        if (full == null) return serializationFailure(normal);
        Map<String, Object> out = new LinkedHashMap<>(normal);
        out.put("truncated", Boolean.TRUE);
        out.put("previewChars", Integer.valueOf(full.length()));
        out.put("ref", locator(normal, full));
        out.put("result", "");
        String base = writeJson(out);
        if (base == null) return serializationFailure(normal);
        int budget = Math.max(0, cap - base.length() - 8);
        for (int attempt = 0; attempt < 8; attempt++) {
            out.put("result", preview(full, budget));
            String json = writeJson(out);
            if (json == null) return serializationFailure(normal);
            if (json.length() <= cap) return json;
            budget -= (json.length() - cap) + 16;
            if (budget <= 0) break;
        }
        return minimalTruncated(normal, out, full, cap);
    }

    /** 兜底：只保留契约关键字段，保证仍是合法 JSON 且不超限。 */
    private static String minimalTruncated(Map<String, Object> normal, Map<String, Object> current,
                                          String full, int cap) {
        Map<String, Object> minimal = new LinkedHashMap<>();
        for (String key : MINIMAL_KEYS) {
            if (current.containsKey(key)) minimal.put(key, current.get(key));
        }
        minimal.put("result", "");
        String empty = writeJson(minimal);
        int budget = Math.max(0, cap - (empty == null ? cap : empty.length()) - 8);
        while (budget > 0) {
            minimal.put("result", preview(full, budget));
            String json = writeJson(minimal);
            if (json != null && json.length() <= cap) return json;
            budget = budget / 2;
        }
        minimal.put("result", "");
        String json = writeJson(minimal);
        return json == null ? serializationFailure(normal) : json;
    }

    private static String serializationFailure(Map<String, Object> normal) {
        Map<String, Object> out = envelope(nameOf(normal), ERROR);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", CODE_SERIALIZATION_FAILED);
        error.put("message", "工具结果无法序列化为 JSON，已丢弃原始内容。");
        error.put("hint", "请改用其它工具或减少单次返回的数据量（例如分批返回），不要把不可序列化对象直接放进结果。");
        out.put("error", error);
        String failed = failedClass(normal);
        out.put("failedValueClass", failed == null ? "unknown" : failed);
        String json = writeJson(out);
        return json == null
                ? "{\"ok\":false,\"resultStatus\":\"error\",\"error\":{\"code\":\"SERIALIZATION_FAILED\"}}"
                : json;
    }

    /** 只返回第一个不可序列化值的类名，绝不返回该值本身。 */
    private static String failedClass(Object value) {
        if (value == null) return null;
        if (writeJson(value) != null) return null;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String nested = failedClass(entry.getValue());
                if (nested != null) return nested;
            }
            return value.getClass().getName();
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String nested = failedClass(item);
                if (nested != null) return nested;
            }
            return value.getClass().getName();
        }
        return value.getClass().getName();
    }

    private static String rawText(Object result) {
        if (result == null) return "";
        if (result instanceof CharSequence text) return text.toString();
        return writeJson(result);
    }

    private static String preview(String text, int budget) {
        if (text == null || text.isEmpty() || budget <= 0) return "";
        if (budget >= text.length()) return text;
        String marker = String.format(TRUNCATION_MARKER, text.length() - budget);
        int keep = budget - marker.length();
        if (keep <= 0) return "";
        int head = keep / 2;
        int tail = keep - head;
        return text.substring(0, head) + marker + text.substring(text.length() - tail);
    }

    private static String locator(Map<String, Object> normal, String full) {
        String name = nameOf(normal);
        if (name == null || name.isBlank()) name = "unknown-tool";
        String hash = digest(full);
        return "tool-result://" + name + "/" + full.length() + "chars#"
                + hash.substring(0, Math.min(16, hash.length()));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nameOf(Map<String, Object> values) {
        Object name = values == null ? null : values.get("tool");
        return name == null ? null : String.valueOf(name);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map == null) return out;
        map.forEach((key, value) -> out.put(key == null ? "null" : String.valueOf(key), value));
        return out;
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            if ("true".equalsIgnoreCase(normalized)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(normalized)) return Boolean.FALSE;
        }
        return null;
    }

    private static String statusValue(Object value) {
        if (!(value instanceof CharSequence text)) return null;
        String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
        return STATUS_VOCABULARY.contains(normalized) ? normalized : null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String out = String.valueOf(value).trim();
        return out.isEmpty() ? null : out;
    }

    private static boolean hasText(Object value) {
        return text(value) != null;
    }

    private static String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isSuccess(String status) {
        return OK.equals(status) || QUEUED.equals(status) || PARTIAL.equals(status);
    }
}
