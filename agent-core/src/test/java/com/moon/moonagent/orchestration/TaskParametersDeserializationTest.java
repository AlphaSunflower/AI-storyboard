package com.moon.moonagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskParametersDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expandsEmbeddedJsonObjectParameters() throws Exception {
        TaskPlan plan = mapper.readValue("""
                {
                  "planId": "plan-1",
                  "conversationId": "conversation-1",
                  "goal": "生成三层别墅漫游分镜",
                  "state": "READY",
                  "version": 1,
                  "maxNodes": 20,
                  "maxAttemptsPerNode": 3,
                  "maxReplans": 2,
                  "replanCount": 0,
                  "nodes": [{
                    "nodeId": "storyboard",
                    "capabilityKey": "intent-storyboard",
                    "title": "生成分镜",
                    "parameters": "{\\"style\\":\\"电影感\\",\\"duration\\":30}",
                    "dependencies": []
                  }],
                  "edges": [],
                  "error": "",
                  "planSignature": ""
                }
                """, TaskPlan.class);

        assertThat(plan.nodes().getFirst().parameters())
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
