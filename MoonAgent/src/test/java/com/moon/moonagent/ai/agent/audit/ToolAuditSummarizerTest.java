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
}
