package com.moon.moonagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskParametersDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expandsEmbeddedJsonObjectParameters() throws Exception {
        TaskPlanDraft draft = mapper.readValue("""
                {
                  "goal": "生成三层别墅漫游分镜",
                  "nodes": [{
                    "nodeId": "storyboard",
                    "capabilityKey": "intent-aisplit",
                    "title": "生成分镜",
                    "parameters": "{\\"style\\":\\"电影感\\",\\"duration\\":30}",
                    "dependencies": []
                  }],
                  "edges": []
                }
                """, TaskPlanDraft.class);

        assertThat(draft.nodes().getFirst().parameters())
                .containsEntry("style", "电影感")
                .containsEntry("duration", 30);
    }

    @Test
    void convertsPlainTextParametersToGoal() throws Exception {
        TaskNode node = mapper.readValue("""
                {
                  "nodeId": "storyboard",
                  "capabilityKey": "intent-aisplit",
                  "title": "生成分镜",
                  "parameters": "生成三层别墅漫游分镜",
                  "dependencies": []
                }
                """, TaskNode.class);

        assertThat(node.parameters()).containsOnlyKeys("goal");
        assertThat(node.parameters().get("goal")).isEqualTo("生成三层别墅漫游分镜");
    }
}
