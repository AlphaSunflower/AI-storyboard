package com.moon.moonagent.orchestration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accepts both the documented parameters object and the string form LLMs
 * occasionally emit. Embedded JSON objects are expanded; plain text becomes
 * the node's goal so the task can still execute.
 */
public class TaskParametersDeserializer extends JsonDeserializer<Map<String, Object>> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_TYPE =
            new TypeReference<>() {};

    @Override
    public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return Map.of();
        }
        if (token == JsonToken.START_OBJECT) {
            return withoutNullValues(parser.readValueAs(OBJECT_TYPE));
        }
        if (token == JsonToken.VALUE_STRING) {
            return parseString(parser.getText());
        }
        return Map.of();
    }

    private static Map<String, Object> parseString(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Map.of();
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node != null && node.isObject()) {
                return withoutNullValues(JSON.convertValue(node, OBJECT_TYPE));
            }
        } catch (Exception ignored) {
            // Plain text is a valid fallback for older model outputs.
        }
        return Map.of("goal", text);
    }

    private static Map<String, Object> withoutNullValues(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && value != null) normalized.put(key, value);
        });
        return normalized;
    }
}
