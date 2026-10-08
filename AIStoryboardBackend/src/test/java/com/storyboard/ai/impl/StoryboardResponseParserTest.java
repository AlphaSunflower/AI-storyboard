package com.storyboard.ai.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryboardResponseParserTest {

    @Test
    void extractsScenesFromWrappedJsonObject() {
        String raw = """
                {
                  "scenes": [
                    {"sceneNumber": 1, "scriptContent": "镜头一"},
                    {"sceneNumber": 2, "scriptContent": "镜头二"}
                  ]
                }
                """;

        String json = StoryboardResponseParser.extractJsonArray(raw);

        assertThat(json).startsWith("[");
        assertThat(json).contains("镜头一", "镜头二");
    }

    @Test
    void extractsJsonArrayFromReasoningAndTrailingText() {
        String raw = """
                先分析剧本，再输出结果。
                ```json
                [{"sceneNumber": 1, "scriptContent": "镜头一"}]
                ```
                以上是分镜结果。
                """;

        String json = StoryboardResponseParser.extractJsonArray(raw);

        assertThat(json).isEqualTo("[{\"sceneNumber\": 1, \"scriptContent\": \"镜头一\"}]");
    }

    @Test
    void salvagesCompleteScenesFromTruncatedArray() {
        String raw = """
                [
                  {"sceneNumber": 1, "scriptContent": "镜头一"},
                  {"sceneNumber": 2, "scriptContent": "镜头二"},
                  {"sceneNumber": 3, "scriptContent": "镜头三
                """;

        String json = StoryboardResponseParser.extractJsonArray(raw);

        assertThat(json).startsWith("[");
        assertThat(json).contains("镜头一", "镜头二");
        assertThat(json).doesNotContain("镜头三");
    }
}
