package com.moon.moonagent.ai.agent.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAuditSummarizerTest {

    private final ToolAuditSummarizer summarizer = new ToolAuditSummarizer();

    @Test
    void unknownExternalActionKeepsUnknownStatus() {
        Map<String, Object> output = Map.of(
                "ok", false,
                "status", "unknown",
                "externalActionKey", "cp:call:tool",
                "error", "outcome unknown");

        assertThat(summarizer.status(output)).isEqualTo("UNKNOWN");
        assertThat(summarizer.errorCode(output)).isEqualTo("EXTERNAL_ACTION_UNKNOWN");
    }

    @Test
    void promptIsRepresentedByHashOnly() {
        String summary = summarizer.inputSummary("generate_image",
                Map.of("prompt", "secret prompt", "source_asset_ids", java.util.List.of("a", "b")));

        assertThat(summary).doesNotContain("secret prompt");
        assertThat(summary).contains("promptHash");
        assertThat(summary).contains("promptLength");
    }

    /** 旧形状 {ok:true,result:{ok:false}} 不能再被记成 COMPLETED。 */
    @Test
    void nestedFailureIsAuditedAsFailed() {
        Map<String, Object> output = Map.of(
                "ok", true,
                "result", Map.of("ok", false, "message", "x"));

        assertThat(summarizer.status(output)).isEqualTo("FAILED");
    }

    /** 契约错误 Map：errorCode/errorMessage 只能读字段，不能把 Map 渲染成 {code=..., message=...}。 */
    @Test
    void canonicalErrorMapProvidesCodeAndMessage() {
        Map<String, Object> output = Map.of(
                "ok", false,
                "resultStatus", "error",
                "error", Map.of("code", "EMPTY_TOOL_RESULT", "message", "工具没有返回任何结果。"));

        assertThat(summarizer.status(output)).isEqualTo("FAILED");
        assertThat(summarizer.errorCode(output)).isEqualTo("EMPTY_TOOL_RESULT");
        assertThat(summarizer.errorMessage(output)).isEqualTo("工具没有返回任何结果。");
        assertThat(summarizer.errorMessage(output)).doesNotContain("{code=");
        assertThat(summarizer.outputSummary("read_file", output))
                .contains("resultStatus")
                .contains("EMPTY_TOOL_RESULT");
    }

    @Test
    void canonicalQueuedAndRejectedStatusesKeepTheirAuditVocabulary() {
        assertThat(summarizer.status(Map.of(
                "ok", true, "resultStatus", "queued", "status", "queued")))
                .isEqualTo("COMPLETED");
        assertThat(summarizer.status(Map.of(
                "ok", false, "resultStatus", "needs_confirmation", "needsClarification", true)))
                .isEqualTo("WAITING");
        assertThat(summarizer.status(Map.of(
                "ok", false, "resultStatus", "rejected", "rejected", true)))
                .isEqualTo("REJECTED");
    }
}
