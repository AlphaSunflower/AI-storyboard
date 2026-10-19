package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamOverrideSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void setForcesAndDeleteRemoves() throws Exception {
        String body = "{\"model\":\"public\",\"temperature\":0.2,\"watermark\":true}";
        String overrides = "{\"set\":{\"temperature\":0.9,\"response_format\":\"url\"},\"delete\":[\"watermark\"]}";
        JsonNode out = mapper.readTree(ParamOverrideSupport.apply(body, overrides, "upstream-model"));

        assertEquals("upstream-model", out.path("model").asText());
        assertEquals(0.9, out.path("temperature").asDouble(), 1e-9);
        assertEquals("url", out.path("response_format").asText());
        assertFalse(out.has("watermark"));
    }

    @Test
    void setIfAbsentOnlyFillsMissing() throws Exception {
        String body = "{\"model\":\"m\",\"size\":\"1024x1024\"}";
        String overrides = "{\"setIfAbsent\":{\"size\":\"2K\",\"quality\":\"hd\"}}";
        JsonNode out = mapper.readTree(ParamOverrideSupport.apply(body, overrides, null));

        assertEquals("1024x1024", out.path("size").asText());
        assertEquals("hd", out.path("quality").asText());
    }

    @Test
    void dotPathWritesNestedObject() throws Exception {
        String body = "{\"model\":\"m\"}";
        String overrides = "{\"set\":{\"style.type\":\"photo\",\"generation_config.seed\":7}}";
        JsonNode out = mapper.readTree(ParamOverrideSupport.apply(body, overrides, null));

        assertEquals("photo", out.path("style").path("type").asText());
        assertEquals(7, out.path("generation_config").path("seed").asInt());
    }

    @Test
    void legacyTopLevelObjectIsTreatedAsSet() throws Exception {
        String body = "{\"model\":\"m\",\"size\":\"1K\"}";
        JsonNode out = mapper.readTree(ParamOverrideSupport.apply(body, "{\"size\":\"2K\"}", null));
        assertEquals("2K", out.path("size").asText());
    }

    @Test
    void invalidOverrideShapeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ParamOverrideSupport.parse("{\"set\":[]}"));
        assertThrows(IllegalArgumentException.class,
                () -> ParamOverrideSupport.parse("{\"delete\":\"watermark\"}"));
    }

    @Test
    void renameMovesKeyForPassthroughProtocols() throws Exception {
        String body = "{\"model\":\"m\",\"pictures\":[\"a\"],\"size\":\"2K\"}";
        String overrides = "{\"rename\":{\"pictures\":\"pics\"}}";
        JsonNode out = mapper.readTree(ParamOverrideSupport.apply(body, overrides, null, true));

        assertEquals("a", out.path("pics").get(0).asText());
        assertFalse(out.has("pictures"));
    }

    @Test
    void renameIsSkippedForAdapterProtocols() throws Exception {
        String body = "{\"model\":\"m\",\"pictures\":[\"a\"]}";
        String overrides = "{\"rename\":{\"pictures\":\"pics\"}}";
        JsonNode out = mapper.readTree(ParamOverrideSupport.apply(body, overrides, null, false));

        assertTrue(out.has("pictures"));
        assertFalse(out.has("pics"));
    }

    @Test
    void invalidRenameShapeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ParamOverrideSupport.parse("{\"rename\":[]}"));
        assertThrows(IllegalArgumentException.class,
                () -> ParamOverrideSupport.parse("{\"rename\":{\"pictures\":\"\"}}"));
    }

    @Test
    void invalidJsonFallsBackToOriginalBody() {
        String body = "{\"model\":\"m\"}";
        assertEquals(body, ParamOverrideSupport.apply(body, "{oops", null));
    }

    @Test
    void deleteDotPathRemovesNestedField() throws Exception {
        String body = "{\"model\":\"m\",\"style\":{\"type\":\"photo\",\"weight\":5}}";
        JsonNode out = mapper.readTree(ParamOverrideSupport.apply(
                body, "{\"delete\":[\"style.weight\"]}", null));
        assertEquals("photo", out.path("style").path("type").asText());
        assertFalse(out.path("style").has("weight"));
    }
}
