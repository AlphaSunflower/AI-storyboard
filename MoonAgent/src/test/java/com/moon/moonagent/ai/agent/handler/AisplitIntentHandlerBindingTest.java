package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.entity.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AisplitIntentHandlerBindingTest {

    @Test
    void resolvePlanSceneMap_replacedScenesRenumbered_usesPlanOrder() {
        AgentSceneItem planItem = new AgentSceneItem(
                7, "连续转场", "画面提示词", "视频提示词", "", "", "", "");
        Scene persisted = new Scene();
        persisted.setId("persisted-scene-1");
        persisted.setSceneNumber(1);

        Map<Integer, Scene> resolved = AisplitIntentHandler.resolvePlanSceneMap(
                List.of(planItem), List.of(persisted));

        assertEquals("persisted-scene-1", resolved.get(7).getId());
        assertEquals(1, resolved.get(7).getSceneNumber());
    }
}
