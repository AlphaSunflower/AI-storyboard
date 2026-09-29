package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptStreamExtractorTest {

    @Test
    void decodesEscapesAcrossChunkBoundaries() {
        ScriptStreamExtractor extractor = new ScriptStreamExtractor("optimizedScript");
        StringBuilder out = new StringBuilder();
        for (String chunk : List.of(
                "{\"optimizedScr", "ipt\":\"# 剧情稿", "\\n", "\\n第一段\\\"引用\\\"",
                "\",\"expansionScript\":\"x\"}")) {
            out.append(extractor.accept(chunk));
        }

        assertThat(out.toString()).isEqualTo("# 剧情稿\n\n第一段\"引用\"");
    }

    @Test
    void stopsAtFieldEndAndIgnoresFollowingFields() {
        ScriptStreamExtractor extractor = new ScriptStreamExtractor("optimizedScript");

        assertThat(extractor.accept("{\"optimizedScript\":\"甲\",\"expansionScript\":\"乙\"}"))
                .isEqualTo("甲");
        assertThat(extractor.accept("\"乙\"")).isEmpty();
    }

    @Test
    void emitsNothingBeforeTheFieldMarkerArrives() {
        ScriptStreamExtractor extractor = new ScriptStreamExtractor("optimizedScript");

        assertThat(extractor.accept("{\"storyBible\":")).isEmpty();
        assertThat(extractor.accept("\"logline\":\"x\"}")).isEmpty();
    }
}
