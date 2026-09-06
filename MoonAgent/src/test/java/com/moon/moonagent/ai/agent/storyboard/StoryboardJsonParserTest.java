package com.moon.moonagent.ai.agent.storyboard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryboardJsonParserTest {

    @Test
    void parseScenes_acceptsBareArrayAndStringSceneNumber() {
        String raw = """
                [{"sceneNumber":"37","scriptContent":"A","videoPrompt":"V","referenceBindings":[]}]
                """;
        List<Map<String, Object>> scenes = StoryboardJsonParser.parseScenes(raw);
        assertEquals(1, scenes.size());
        assertEquals(37, scenes.get(0).get("sceneNumber"));
        assertEquals("A", scenes.get(0).get("scriptContent"));
    }

    @Test
    void parseScenes_acceptsWrapperFenceAndTrailingComma() {
        String raw = """
                ```json
                {"scenes":[{"sceneNumber":1,"scriptContent":"A","videoPrompt":"V","cameraMovement":"pan",},]}
                ```
                """;
        List<Map<String, Object>> scenes = StoryboardJsonParser.parseScenes(raw);
        assertEquals(1, scenes.size());
        assertEquals("pan", scenes.get(0).get("cameraMovement"));
    }

    @Test
    void parseScenes_fillsMissingFieldsAndNormalizesRole() {
        String raw = """
                {"scenes":[{"sceneNumber":"分镜2","scriptContent":"B","referenceBindings":[{"assetId":"图1","role":"bogus"}]}]}
                """;
        List<Map<String, Object>> scenes = StoryboardJsonParser.parseScenes(raw);
        assertEquals(1, scenes.size());
        assertEquals(2, scenes.get(0).get("sceneNumber"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) scenes.get(0).get("referenceBindings");
        assertEquals("REFERENCE", refs.get(0).get("role"));
    }

    @Test
    void parseScenes_keepsValidSceneWhenOneInvalid() {
        String raw = """
                [{"sceneNumber":1,"scriptContent":"OK"},{"sceneNumber":2}]
                """;
        List<Map<String, Object>> scenes = StoryboardJsonParser.parseScenes(raw);
        assertEquals(1, scenes.size());
        assertEquals("OK", scenes.get(0).get("scriptContent"));
    }

    @Test
    void parseScenes_throwsWhenNoValidScene() {
        assertThrows(IllegalArgumentException.class,
                () -> StoryboardJsonParser.parseScenes("没有 JSON"));
    }

    @Test
    void parseBindAndDesignResults() {
        Map<String, Object> bind = StoryboardJsonParser.parseBindResult(
                "{\"bindings\":[{\"assetId\":\"图1\",\"kind\":\"reference\"}],\"message\":\"ok\"}");
        assertEquals("ok", bind.get("message"));
        assertEquals(1, ((List<?>) bind.get("bindings")).size());

        Map<String, Object> design = StoryboardJsonParser.parseDesignResult(
                "{\"scenes\":[{\"sceneNumber\":1,\"scriptContent\":\"A\"}],\"aspectRatio\":\"9:16\"}");
        assertEquals("9:16", design.get("aspectRatio"));
        assertEquals(1, ((List<?>) design.get("scenes")).size());
    }
}
