package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** OpenAI 生图格式 ↔ Gemini generateContent 格式互转 */
@Component
public class GeminiFormatConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OpenAI 请求 → Gemini 请求（aspect_ratio 优先，size 回退；两者皆无不附带 generationConfig） */
    public String toGeminiRequest(String openAiBodyJson) throws Exception {
        JsonNode src = objectMapper.readTree(openAiBodyJson);
        ObjectNode out = objectMapper.createObjectNode();

        // contents: [{parts:[{text: prompt}]}]
        ArrayNode contents = out.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", src.path("prompt").asText(""));

        // generationConfig
        String aspectRatio = src.path("aspect_ratio").asText("");
        String size = src.path("size").asText("");
        if (!aspectRatio.isBlank()) {
            ObjectNode gc = out.putObject("generationConfig");
            gc.put("aspectRatio", aspectRatio);
        } else if (!size.isBlank()) {
            ObjectNode gc = out.putObject("generationConfig");
            gc.put("imageSize", size);
        }
        return objectMapper.writeValueAsString(out);
    }

    /** Gemini 响应 → OpenAI 响应（b64_json）；candidates 缺失返回空 data */
    public String toOpenAiResponse(String geminiRawJson) throws Exception {
        JsonNode src = objectMapper.readTree(geminiRawJson);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("created", System.currentTimeMillis() / 1000);
        ArrayNode data = out.putArray("data");

        JsonNode candidates = src.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode cand : candidates) {
                JsonNode parts = cand.path("content").path("parts");
                if (!parts.isArray()) continue;
                for (JsonNode part : parts) {
                    String b64 = part.path("inlineData").path("data").asText("");
                    if (!b64.isBlank()) {
                        data.addObject().put("b64_json", b64);
                    }
                }
            }
        }
        return objectMapper.writeValueAsString(out);
    }
}
