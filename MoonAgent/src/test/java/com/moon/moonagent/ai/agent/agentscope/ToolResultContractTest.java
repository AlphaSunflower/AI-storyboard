package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.audit.ToolAuditSummarizer;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolAuditSummarizer summarizer = new ToolAuditSummarizer();

    /** 要求 1：null → ok=false + EMPTY_TOOL_RESULT（含可读中文 message 与 hint）。 */
    @Test
    void nullResultBecomesEmptyToolResultError() {
        Map<String, Object> normalized = ToolResultContract.normalize("read_file", null);

        assertThat(normalized)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "error")
                .containsEntry("tool", "read_file");
        assertThat(error(normalized)).containsEntry("code", "EMPTY_TOOL_RESULT");
        assertThat(String.valueOf(error(normalized).get("message"))).isNotBlank();
        assertThat(String.valueOf(error(normalized).get("hint"))).isNotBlank();
        assertThat(summarizer.status(normalized)).isEqualTo("FAILED");
        assertThat(summarizer.errorCode(normalized)).isEqualTo("EMPTY_TOOL_RESULT");
    }

    /** 要求 2 + 要求 7：{ok:true,result:{ok:false,message:"x"}} 必须提升为外层失败，且审计与模型一致。 */
    @Test
    void disguisedNestedFailureIsPromotedAndAuditedAsFailed() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("ok", true);
        raw.put("result", Map.of("ok", false, "message", "x"));

        Map<String, Object> normalized = ToolResultContract.normalize("start_storyboard", raw);

        assertThat(normalized)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "error")
                .containsEntry("message", "x");
        assertThat(error(normalized))
                .containsEntry("code", "TOOL_FAILED")
                .containsEntry("message", "x");
        // 内层原始明细仍保留在 result
        assertThat(normalized.get("result")).isEqualTo(Map.of("ok", false, "message", "x"));
        assertThat(ToolResultContract.resultStatusOf(normalized)).isEqualTo("error");

        assertThat(summarizer.status(normalized)).isEqualTo("FAILED");
        assertThat(summarizer.errorCode(normalized)).isEqualTo("TOOL_FAILED");
        assertThat(summarizer.errorMessage(normalized)).isEqualTo("x");
    }

    /** 要求 3：两层嵌套（中间层 ok:true）也必须被提升。 */
    @Test
    void twoLevelNestedFailureIsPromoted() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("ok", true);
        raw.put("result", Map.of("ok", true, "result", Map.of("ok", false, "reason", "深层失败")));

        Map<String, Object> normalized = ToolResultContract.normalize("tool", raw);

        assertThat(normalized)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "error")
                .containsEntry("reason", "深层失败");
        assertThat(error(normalized)).containsEntry("message", "深层失败");
        assertThat(summarizer.status(normalized)).isEqualTo("FAILED");
    }

    /** 要求 4：显式 error Map 的 code/message 必须原样保留。 */
    @Test
    void explicitErrorMapStaysIntact() {
        Map<String, Object> normalized = ToolResultContract.normalize("tool",
                Map.of("ok", false, "error", Map.of("code", "A", "message", "B")));

        assertThat(normalized)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "error");
        assertThat(error(normalized))
                .containsEntry("code", "A")
                .containsEntry("message", "B");
        assertThat(summarizer.errorCode(normalized)).isEqualTo("A");
        assertThat(summarizer.errorMessage(normalized)).isEqualTo("B");
    }

    /** 要求 5：不可序列化值必须给出合法 JSON，绝不出现 Object@hash。 */
    @Test
    void unserializableValueNeverDegradesToObjectToString() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("ok", true);
        raw.put("result", new Object());

        String json = ToolResultContract.serialize(ToolResultContract.normalize("tool", raw));

        assertThat(json).contains("SERIALIZATION_FAILED");
        assertThat(json).doesNotContain("@");
        assertThat(json).doesNotContain("java.util.LinkedHashMap");
        Map<String, Object> parsed = parse(json);
        assertThat(parsed)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "error")
                .containsEntry("failedValueClass", "java.lang.Object");
        assertThat(error(parsed)).containsEntry("code", "SERIALIZATION_FAILED");
    }

    /** 要求 6：超长结果必须仍然是合法 JSON，带 truncated/previewChars/ref 与头尾预览。 */
    @Test
    void oversizedResultKeepsValidJsonWithHeadTailPreview() throws Exception {
        String big = "HEAD" + "x".repeat(200_000) + "TAIL";
        Map<String, Object> normalized = ToolResultContract.normalize("tool",
                Map.of("ok", true, "result", big));

        String json = ToolResultContract.serialize(normalized, 5_000);

        assertThat(json.length()).isLessThanOrEqualTo(5_000);
        Map<String, Object> parsed = parse(json);
        assertThat(parsed)
                .containsEntry("truncated", true)
                .containsEntry("ok", true)
                .containsEntry("resultStatus", "ok");
        assertThat(parsed.get("previewChars")).isEqualTo(big.length());
        assertThat(String.valueOf(parsed.get("ref"))).startsWith("tool-result://tool/");
        String preview = String.valueOf(parsed.get("result"));
        assertThat(preview).startsWith("HEAD").endsWith("TAIL");
        assertThat(preview.length()).isLessThan(big.length());
        assertThat(preview).contains("已截断");
    }

    /** CLAUDE.md 红线 §3：工具结果里的时间字段必须按 ISO-8601 文本输出并可 round-trip。 */
    @Test
    void timeValuesAreSerializedAsIsoTextAndRoundTrip() throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-10-14T10:15:30+08:00");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("ok", true);
        raw.put("startedAt", startedAt);

        String json = ToolResultContract.serialize(ToolResultContract.normalize("tool", raw));

        assertThat(json).contains("2026-10-14T10:15:30+08:00");
        assertThat(parse(json).get("startedAt")).isEqualTo("2026-10-14T10:15:30+08:00");
    }

    /** 纯字符串返回值保持原样文本，但进入契约信封。 */
    @Test
    void stringResultKeepsVerbatimText() {
        Map<String, Object> normalized = ToolResultContract.normalize("tool", "纯文本结果");

        assertThat(normalized)
                .containsEntry("ok", true)
                .containsEntry("resultStatus", "ok")
                .containsEntry("result", "纯文本结果");
        assertThat(summarizer.status(normalized)).isEqualTo("COMPLETED");
    }

    /** 状态词表与审计词表的映射保持一致。 */
    @Test
    void statusVocabularyMapsToAuditVocabulary() {
        Map<String, Object> queued = ToolResultContract.normalize("tool",
                Map.of("ok", true, "status", "queued"));
        assertThat(queued).containsEntry("ok", true).containsEntry("resultStatus", "queued");
        assertThat(summarizer.status(queued)).isEqualTo("COMPLETED");

        Map<String, Object> waiting = ToolResultContract.normalize("tool",
                Map.of("needsClarification", true));
        assertThat(waiting).containsEntry("ok", false).containsEntry("resultStatus", "needs_confirmation");
        assertThat(summarizer.status(waiting)).isEqualTo("WAITING");

        Map<String, Object> rejected = ToolResultContract.normalize("tool",
                Map.of("rejected", true));
        assertThat(rejected).containsEntry("ok", false).containsEntry("resultStatus", "rejected");
        assertThat(summarizer.status(rejected)).isEqualTo("REJECTED");

        Map<String, Object> failed = ToolResultContract.normalize("tool",
                Map.of("error", "磁盘只读"));
        assertThat(failed).containsEntry("ok", false).containsEntry("resultStatus", "error");
        assertThat(error(failed)).containsEntry("message", "磁盘只读");
        assertThat(summarizer.status(failed)).isEqualTo("FAILED");
    }

    /**
     * needsChoice 是"等用户选择"的暂停态，不是失败。
     * 否则每一次"已有分镜，请选择追加/覆盖/删除"都会在审计里记成 FAILED，污染失败率口径。
     */
    @Test
    void needsChoiceIsAwaitingUserNotFailure() {
        Map<String, Object> normalized = ToolResultContract.normalize("start_storyboard",
                Map.of("ok", false, "needsChoice", true, "existingSceneCount", 1));

        assertThat(normalized)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "needs_confirmation")
                .containsEntry("existingSceneCount", 1);
        assertThat(error(normalized)).containsEntry("code", "NEEDS_CHOICE");
        assertThat(summarizer.status(normalized)).isEqualTo("WAITING");
        assertThat(summarizer.errorCode(normalized)).isEqualTo("NEEDS_CHOICE");
    }

    /** 归一必须幂等：链路里 executeMap 与 callAsync 各过一道也不能变形。 */
    @Test
    void normalizationIsIdempotent() {
        Map<String, Object> once = ToolResultContract.normalize("tool",
                Map.of("ok", true, "result", Map.of("ok", false, "message", "x")));
        Map<String, Object> twice = ToolResultContract.normalize("tool", once);

        assertThat(twice).isEqualTo(once);
        assertThat(ToolResultContract.serialize(twice))
                .isEqualTo(ToolResultContract.serialize(once));
        assertThat(ToolResultContract.resultStatusOf(once)).isEqualTo("error");
    }

    /** 未归一的旧嵌套形状也能被审计识别为失败（历史数据/旁路）。 */
    @Test
    void legacyNestedShapeIsStillAuditedAsFailed() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("ok", true);
        legacy.put("result", Map.of("ok", false, "message", "旧形状"));

        assertThat(ToolResultContract.resultStatusOf(legacy)).isEqualTo("error");
        assertThat(summarizer.status(legacy)).isEqualTo("FAILED");
    }

    /** isCanonical：只认结构，且对“像信封但不是”的输入绝不放行。 */
    @Test
    void isCanonicalOnlyAcceptsRealEnvelopes() {
        for (String status : new String[]{"ok", "queued", "needs_confirmation", "rejected", "partial", "error"}) {
            Map<String, Object> envelope = ToolResultContract.normalize("tool", Map.of("resultStatus", status));
            assertThat(ToolResultContract.isCanonical(envelope))
                    .as("status=%s", status)
                    .isTrue();
            // 已规范信封跳过再次归一：序列化必须逐字节一致
            assertThat(ToolResultContract.serialize(envelope))
                    .isEqualTo(ToolResultContract.serialize(ToolResultContract.normalize("tool", envelope)));
        }
    }

    @Test
    void isCanonicalRejectsIncompleteOrMistypedEnvelopes() {
        assertThat(ToolResultContract.isCanonical(null)).isFalse();
        assertThat(ToolResultContract.isCanonical(Map.of())).isFalse();
        assertThat(ToolResultContract.isCanonical(Map.of("ok", true))).isFalse();
        assertThat(ToolResultContract.isCanonical(Map.of("resultStatus", "ok"))).isFalse();
        // ok 不是布尔
        assertThat(ToolResultContract.isCanonical(Map.of("ok", "true", "resultStatus", "ok"))).isFalse();
        // resultStatus 不在契约词表内
        assertThat(ToolResultContract.isCanonical(Map.of("ok", true, "resultStatus", "bogus"))).isFalse();
        assertThat(ToolResultContract.isCanonical(Map.of("ok", true, "resultStatus", 1))).isFalse();
        // 未归一形状：仍然是合法的判定拒绝
        assertThat(ToolResultContract.isCanonical(Map.of("ok", true, "result", Map.of("ok", false)))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> normalized) {
        Object error = normalized.get("error");
        assertThat(error).isInstanceOf(Map.class);
        return (Map<String, Object>) error;
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
    }
}
