package com.llmgateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UsageExtractorTest {

    @Test
    void openAiUsage() {
        UsageExtractor.Usage usage = UsageExtractor.fromBody(
                "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":30,\"total_tokens\":42}}");
        assertEquals(12, usage.promptTokens());
        assertEquals(30, usage.completionTokens());
        assertEquals(42, usage.totalTokens());
    }

    @Test
    void geminiUsageMetadata() {
        UsageExtractor.Usage usage = UsageExtractor.fromBody(
                "{\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":9}}");
        assertEquals(7, usage.promptTokens());
        assertEquals(9, usage.completionTokens());
        assertEquals(16, usage.totalTokens());
    }

    @Test
    void noUsageReturnsNull() {
        assertNull(UsageExtractor.fromBody("{\"data\":[]}"));
        assertNull(UsageExtractor.fromBody("not json"));
        assertNull(UsageExtractor.fromBody(null));
    }
}
