package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecRequestMappingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsFlatNestedObjectAndArrayOfObject() throws Exception {
        String spec = """
                {"request":[
                  {"key":"size","type":"enum","options":["1K","2K"],"map":{"2K":"2048x2048"}},
                  {"key":"style","type":"object","fields":[
                    {"key":"type","type":"enum","options":["www","photo"],"map":{"www":"photo"}}
                  ]},
                  {"key":"refs","type":"arrayofobject","fields":[
                    {"key":"kind","type":"enum","options":["char","object"],"map":{"char":"character"}}
                  ]}
                ]}
                """;
        String body = """
                {"model":"m","size":"2K","style":{"type":"www"},
                 "refs":[{"kind":"char"},{"kind":"object"}]}
                """;
        JsonNode out = mapper.readTree(SpecRequestMapping.apply(body, spec));

        assertEquals("2048x2048", out.path("size").asText());
        assertEquals("photo", out.path("style").path("type").asText());
        assertEquals("character", out.path("refs").get(0).path("kind").asText());
        assertEquals("object", out.path("refs").get(1).path("kind").asText());
    }

    @Test
    void canonicalKeyIsRenamedToUpstreamKey() throws Exception {
        String spec = """
                {"request":[
                  {"key":"pictures","upstreamKey":"pics","type":"array"},
                  {"key":"size","upstreamKey":"image_size","map":{"2K":"2048x2048"}}
                ]}
                """;
        JsonNode out = mapper.readTree(SpecRequestMapping.apply(
                "{\"pictures\":[\"a\",\"b\"],\"size\":\"2K\"}", spec));

        assertTrue(out.has("pics"));
        assertEquals("a", out.path("pics").get(0).asText());
        assertEquals(false, out.has("pictures"));
        assertEquals("2048x2048", out.path("image_size").asText());
        assertEquals(false, out.has("size"));
    }

    @Test
    void aliasIsAcceptedAndNormalizedToUpstreamKey() throws Exception {
        String spec = """
                {"request":[
                  {"key":"pictures","upstreamKey":"pics","aliases":["photos","refs"]}
                ]}
                """;
        JsonNode out = mapper.readTree(SpecRequestMapping.apply("{\"photos\":[\"a\"]}", spec));

        assertTrue(out.has("pics"));
        assertEquals(false, out.has("photos"));
        assertEquals(false, out.has("refs"));
    }

    @Test
    void canonicalKeyWinsOverAliasAndAliasIsRemoved() throws Exception {
        String spec = """
                {"request":[
                  {"key":"pictures","upstreamKey":"pics","aliases":["photos"]}
                ]}
                """;
        JsonNode out = mapper.readTree(SpecRequestMapping.apply(
                "{\"pictures\":[\"canonical\"],\"photos\":[\"legacy\"]}", spec));

        assertEquals("canonical", out.path("pics").get(0).asText());
        assertEquals(false, out.has("photos"));
    }

    @Test
    void valuesOnlyDoesNotRenameCanonicalKeys() throws Exception {
        String spec = """
                {"request":[
                  {"key":"pictures","upstreamKey":"pics","map":{"2K":"2048x2048"}}
                ]}
                """;
        JsonNode out = mapper.readTree(SpecRequestMapping.applyValuesOnly("{\"pictures\":\"2K\"}", spec));
        assertEquals("2048x2048", out.path("pictures").asText());
        assertEquals(false, out.has("pics"));
    }

    @Test
    void injectsDefaultOnlyWhenAbsent() throws Exception {
        String spec = """
                {"request":[
                  {"key":"size","type":"enum","options":["1K","2K"],"default":"2K","injectDefault":true},
                  {"key":"watermark","type":"bool","default":false,"injectDefault":true},
                  {"key":"n","type":"range","default":1,"injectDefault":false}
                ]}
                """;
        SpecRequestMapping.Result result = SpecRequestMapping.applyWithTrace(
                "{\"model\":\"m\"}", spec, true);
        JsonNode out = mapper.readTree(result.body());

        assertEquals("2K", out.path("size").asText());
        assertEquals(false, out.path("watermark").asBoolean());
        assertEquals(false, out.has("n"));
        assertTrue(result.steps().stream().anyMatch(s -> s.contains("注入默认值 size")));
    }

    @Test
    void doesNotOverrideCallerValueWithDefault() throws Exception {
        String spec = "{\"request\":[{\"key\":\"size\",\"default\":\"2K\",\"injectDefault\":true}]}";
        JsonNode out = mapper.readTree(SpecRequestMapping.applyWithTrace(
                "{\"size\":\"4K\"}", spec, true).body());
        assertEquals("4K", out.path("size").asText());
    }

    @Test
    void doesNotInjectDefaultWhenAliasIsPresent() throws Exception {
        String spec = """
                {"request":[
                  {"key":"size","aliases":["image_size"],"default":"2K","injectDefault":true}
                ]}
                """;
        JsonNode out = mapper.readTree(SpecRequestMapping.applyWithTrace(
                "{\"image_size\":\"4K\"}", spec, true).body());
        assertEquals("4K", out.path("size").asText());
        assertEquals(false, out.has("image_size"));
    }

    @Test
    void unknownValueIsLeftUntouched() throws Exception {
        String spec = "{\"request\":[{\"key\":\"size\",\"map\":{\"2K\":\"2048x2048\"}}]}";
        JsonNode out = mapper.readTree(SpecRequestMapping.apply("{\"size\":\"4K\"}", spec));
        assertEquals("4K", out.path("size").asText());
    }

    @Test
    void noSpecOrInvalidSpecReturnsOriginalBody() {
        String body = "{\"size\":\"2K\"}";
        assertEquals(body, SpecRequestMapping.apply(body, null));
        assertEquals(body, SpecRequestMapping.apply(body, "{oops"));
        assertEquals(body, SpecRequestMapping.apply(body, "{\"request\":[]}"));
    }

    @Test
    void numericAndBooleanValuesMap() throws Exception {
        String spec = """
                {"request":[
                  {"key":"n","map":{"3":"4"}},
                  {"key":"watermark","map":{"false":"true"}}
                ]}
                """;
        JsonNode out = mapper.readTree(SpecRequestMapping.apply(
                "{\"n\":3,\"watermark\":false}", spec));
        assertEquals(4, out.path("n").asInt());
        assertEquals(true, out.path("watermark").asBoolean());
        assertEquals(true, out.path("watermark").isBoolean());
    }
}
