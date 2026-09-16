package com.moon.moonagent.ai.agent.human;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HumanInputRequestTest {

    @Test
    void parsesToolResultAndKeepsHiddenValues() {
        String raw = """
                {"moonHumanInput":true,"prompt":"请选择画布","allowCustom":true,
                 "options":[
                   {"id":"human-option-1","title":"项目一 / 分镜画布",
                    "value":{"projectId":"project-1","canvasId":"canvas-1"}}
                 ]}
                """;

        HumanInputRequest request = HumanInputRequest.parse(raw).orElseThrow();

        assertThat(request.prompt()).isEqualTo("请选择画布");
        assertThat(request.allowCustom()).isTrue();
        assertThat(request.options()).hasSize(1);
        assertThat(request.options().getFirst())
                .containsEntry("id", "human-option-1")
                .containsEntry("title", "项目一 / 分镜画布");
        assertThat(request.options().getFirst().get("value"))
                .isEqualTo(Map.of("projectId", "project-1", "canvasId", "canvas-1"));
    }

    @Test
    void ignoresOrdinaryAssistantText() {
        assertThat(HumanInputRequest.parse("已经完成")).isEmpty();
        assertThat(HumanInputRequest.parse(null)).isEmpty();
    }

    @Test
    void normalizesOptionIdsAwayFromUserFacingIds() {
        HumanInputRequest request = HumanInputRequest.fromToolCall(
                "请选择项目",
                List.of(Map.of(
                        "id", "9312e9541998f751115bcc48dde1a99b",
                        "title", "项目一",
                        "value", "project-1")),
                false);

        assertThat(request.options().getFirst()).containsEntry("id", "human-option-1");
        assertThat(request.options().getFirst()).containsEntry("title", "项目一");
        assertThat(request.options().getFirst()).containsEntry("value", "project-1");
    }
}
