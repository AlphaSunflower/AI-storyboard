package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 从上游响应里提取 token 用量（OpenAI usage / Gemini usageMetadata），解析失败返回 null */
public final class UsageExtractor {

    private static final ObjectMapper M = new ObjectMapper();

    private UsageExtractor() {}

    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}

    public static Usage fromBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = M.readTree(body);
            JsonNode usage = root.path("usage");
            if (!usage.isObject() || usage.isEmpty()) {
                usage = root.path("usageMetadata");
            }
            if (!usage.isObject() || usage.isEmpty()) return null;
            Integer prompt = intOrNull(usage, "prompt_tokens", "promptTokenCount");
            Integer completion = intOrNull(usage, "completion_tokens", "candidatesTokenCount");
            Integer total = intOrNull(usage, "total_tokens", "totalTokenCount");
            if (total == null && (prompt != null || completion != null)) {
                total = (prompt == null ? 0 : prompt) + (completion == null ? 0 : completion);
            }
            if (prompt == null && completion == null && total == null) return null;
            return new Usage(prompt, completion, total);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) return value.asInt();
        }
        return null;
    }
}
