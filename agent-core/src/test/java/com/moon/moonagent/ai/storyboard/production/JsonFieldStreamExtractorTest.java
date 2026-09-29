package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFieldStreamExtractorTest {

    @Test
    void extractsDecodedFieldAcrossChunkBoundaries() {
        JsonFieldStreamExtractor extractor =
                new JsonFieldStreamExtractor("optimizedScript", "优化后的剧情");

        assertThat(extractor.accept("{\"optimizedScript\":\"第一行\\n"))
                .isEqualTo("优化后的剧情\n第一行\n");
        assertThat(extractor.accept("第二行")).isEqualTo("第二行");
        assertThat(extractor.accept("\",\"other\":\"ignored\"}")).isEmpty();
    }

    @Test
    void emitsRepeatedArrayStringFields() {
        JsonFieldStreamExtractor extractor =
                new JsonFieldStreamExtractor("replacement", "修订内容");

        assertThat(extractor.accept(
                "{\"changes\":[{\"replacement\":\"甲\"},{\"replacement\":\"乙\"}]}"))
                .isEqualTo("修订内容\n甲乙");
    }
}
