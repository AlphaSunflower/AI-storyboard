package com.llmgateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamsSchemaValidatorTest {

    @Test
    void emptySchema_isValid() {
        assertTrue(ParamsSchemaValidator.validate(null).valid());
        assertTrue(ParamsSchemaValidator.validate("  ").valid());
    }

    @Test
    void fullDesignSample_isValid() {
        String schema = """
                {
                  "schemaVersion": 2,
                  "modes": ["text-to-video", "image-to-video"],
                  "promptKey": "prompt",
                  "request": [
                    {"key":"duration","label":"时长","type":"range","min":4,"max":15,"default":6,"show":true},
                    {"key":"resolution","label":"分辨率","type":"enum","options":["768P","2K"],"default":"768P","show":true},
                    {"key":"prompt_optimizer","label":"提示词优化","type":"bool","default":true,"show":false},
                    {"key":"style","label":"风格","type":"object","fields":[
                      {"key":"style_type","label":"类型","type":"enum","options":["www","photo"],"default":"www"}
                    ]},
                    {"key":"image","label":"参考图","type":"string","role":"image_upload","cardinality":"multi","show":true}
                  ],
                  "response": {
                    "async": {
                      "taskIdPath":"task.task_id",
                      "statusPath":"task.status",
                      "stateMap":{"PENDING":"queued","PROCESSING":"running","SUCCESS":"succeeded","FAIL":"failed"},
                      "mediaPath":"task.content.url",
                      "mediaMode":"media_url",
                      "errorPath":"task.error.message",
                      "pollIntervalSec":5,
                      "timeoutSec":300
                    }
                  }
                }
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertTrue(result.valid(), () -> result.summary());
        assertNotNull(result.normalized());
    }

    @Test
    void malformedJson_reportsRootPath() {
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate("{oops}");
        assertFalse(result.valid());
        assertEquals("$", result.errors().get(0).path());
    }

    @Test
    void topLevelMustBeObject() {
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate("[]");
        assertFalse(result.valid());
        assertEquals("$", result.errors().get(0).path());
    }

    @Test
    void enumDefaultOutsideOptions_isRejected() {
        String schema = """
                {"request":[{"key":"size","type":"enum","options":["1K","2K"],"default":"4K"}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().equals("request[0].default")));
    }

    @Test
    void duplicateKeys_areRejected() {
        String schema = """
                {"request":[
                  {"key":"size","type":"string"},
                  {"key":"size","type":"string"}
                ]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().equals("request[1].key")));
    }

    @Test
    void imageUploadWithoutCardinality_isRejected() {
        String schema = """
                {"request":[{"key":"image","type":"string","role":"image_upload"}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().equals("request[0].cardinality")));
    }

    @Test
    void rangeWithMinGreaterThanMax_isRejected() {
        String schema = """
                {"request":[{"key":"n","type":"range","min":5,"max":3,"default":4}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().equals("request[0].min")));
    }

    @Test
    void rangeDefaultOutsideBounds_isRejected() {
        String schema = """
                {"request":[{"key":"n","type":"range","min":1,"max":3,"default":9}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().equals("request[0].default")));
    }

    @Test
    void invalidStateMapValue_isRejected() {
        String schema = """
                {"response":{"async":{"statusPath":"status","stateMap":{"SUCCESS":"ok"}}}}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream()
                .anyMatch(i -> i.path().equals("response.async.stateMap.SUCCESS")));
    }

    @Test
    void asyncWithoutStatusPath_isWarningOnly() {
        String schema = """
                {"response":{"async":{"taskIdPath":"task_id"}}}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertTrue(result.valid(), () -> result.summary());
        assertTrue(result.warnings().stream()
                .anyMatch(i -> i.path().equals("response.async.statusPath")));
    }

    @Test
    void invalidRole_isRejected() {
        String schema = """
                {"request":[{"key":"x","type":"string","role":"bogus"}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().equals("request[0].role")));
    }

    @Test
    void objectWithoutFields_isRejected() {
        String schema = """
                {"request":[{"key":"style","type":"object"}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().equals("request[0].fields")));
    }

    @Test
    void conditionsAndValueMapAreValid() {
        String schema = """
                {
                  "protocol": "openai_json",
                  "modes": ["text-to-image", "image-to-image"],
                  "request": [
                    {"key":"size","label":"尺寸","type":"enum","options":["1K","2K"],
                     "default":"2K","map":{"1K":"1024x1024","2K":"2048x2048"},
                     "visibleWhen":{"mode":"text-to-image"},
                     "requiredWhen":{"mode":"image-to-image"}},
                    {"key":"image","label":"参考图","type":"string","role":"image_upload",
                     "cardinality":"multi","maxItems":4,
                     "requiredWhen":{"mode":"image-to-image"}}
                  ]
                }
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertTrue(result.valid(), () -> result.summary());
    }

    @Test
    void invalidValueMapIsRejected() {
        String schema = """
                {"request":[{"key":"size","type":"enum","options":["1K"],
                 "map":{"1K":["bad"]}}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().contains("map")));
    }

    @Test
    void invalidConditionShapeIsRejected() {
        String schema = """
                {"request":[{"key":"image","type":"string","visibleWhen":"image-to-image"}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().contains("visibleWhen")));
    }

    @Test
    void invalidMaxItemsIsRejected() {
        String schema = """
                {"request":[{"key":"image","type":"string","role":"image_upload",
                 "cardinality":"multi","maxItems":0}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().contains("maxItems")));
    }

    @Test
    void keyAliasMappingIsValid() {
        String schema = """
                {"request":[
                  {"key":"pictures","upstreamKey":"pics","aliases":["photos","refs"],
                   "type":"array","role":"image_upload","cardinality":"multi"}
                ]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertTrue(result.valid(), () -> result.summary());
    }

    @Test
    void invalidAliasesAreRejected() {
        String schema = """
                {"request":[{"key":"pictures","aliases":"photos","type":"array"}]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(i -> i.path().contains("aliases")));
    }

    @Test
    void summary_isBoundedToOneLine() {
        String schema = """
                {"request":[
                  {"key":"a","type":"bogus"},
                  {"key":"b","type":"bogus"},
                  {"key":"c","type":"bogus"},
                  {"key":"d","type":"bogus"}
                ]}
                """;
        ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(schema);
        assertFalse(result.valid());
        assertTrue(result.summary().contains("等 4 项"));
    }
}
