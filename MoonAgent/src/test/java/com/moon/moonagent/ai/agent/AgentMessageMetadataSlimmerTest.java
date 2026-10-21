package com.moon.moonagent.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列表瘦身的行为锁：删无用字段、思考全文与轨迹只留标记、详情接口能拿回全文。
 */
class AgentMessageMetadataSlimmerTest {

    @Test
    void dropsUnusedFieldsAndReplacesHeavyBlocksWithFlags() {
        Map<String, Object> reasoning = new LinkedHashMap<>();
        reasoning.put("details", "x".repeat(10_000));
        reasoning.put("summary", "不要暴露 ID");
        reasoning.put("source", "agentscope");
        reasoning.put("durationMs", 1234);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reasoning", reasoning);
        metadata.put("agentTrace", List.of(Map.of("title", "模型推理"), Map.of("title", "工具调用")));
        metadata.put("referenceAssets", List.of("a1"));
        metadata.put("agentRuntime", Map.of("engine", "agentscope"));
        metadata.put("checkpointOwner", "moon");
        metadata.put("metrics", Map.of("inputTokens", 100));

        Map<String, Object> slim = AgentMessageMetadataSlimmer.slim(metadata);

        assertThat(slim).doesNotContainKeys("referenceAssets", "agentRuntime", "checkpointOwner");
        assertThat(slim).containsKey("metrics");

        @SuppressWarnings("unchecked")
        Map<String, Object> slimReasoning = (Map<String, Object>) slim.get("reasoning");
        assertThat(slimReasoning).containsEntry("detailsAvailable", true);
        assertThat(slimReasoning).containsEntry("durationMs", 1234);
        assertThat(slimReasoning).doesNotContainKeys("details", "summary");

        assertThat(slim).containsEntry("traceAvailable", true);
        assertThat(slim).doesNotContainKey("agentTrace");
    }

    @Test
    void detailsEndpointPayloadCarriesBackTheHeavyBlocks() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reasoning", Map.of("details", "完整思考", "durationMs", 10));
        metadata.put("agentTrace", List.of(Map.of("title", "模型推理")));

        Map<String, Object> details = AgentMessageMetadataSlimmer.details(metadata);

        assertThat(details).containsEntry("reasoningDetails", "完整思考");
        assertThat(details.get("agentTrace")).isInstanceOf(List.class);
    }

    @Test
    void leavesEmptyOrMissingMetadataAlone() {
        assertThat(AgentMessageMetadataSlimmer.slim(null)).isNull();
        assertThat(AgentMessageMetadataSlimmer.slim(Map.of())).isEmpty();
        assertThat(AgentMessageMetadataSlimmer.details(null)).isEmpty();
    }
}
