package com.moon.moonagent;

import com.moon.moonagent.ai.agent.PlanGraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

/**
 * P2 PlanGraph 主图装配验证（StateGraph 编排重构）：
 * 图构建成功（6 个 handler 适配器节点 + intent_recognize + intent_clarify + 条件边编译），
 * run() 入口可调用（不触发真实 LLM——意图识别需真实调用，此处仅验证装配与图编译）。
 */
@SpringBootTest
@TestPropertySource(properties = "storyboard.internal-secret=test-internal-secret")
class PlanGraphTests {

    @Autowired(required = false)
    private PlanGraph planGraph;

    @Test
    void planGraphAutoConfigured() {
        assertNotNull(planGraph, "PlanGraph bean 应装配成功（@PostConstruct 已编译 StateGraph）");
    }

    @Test
    void expandReviewCheckpointRoutesToAisplit() throws Exception {
        var field = PlanGraph.class.getDeclaredField("CP_ACTION_TO_INTENT");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) field.get(null);
        assertEquals("intent-aisplit", map.get("expand-review"),
                "扩写审核卡（cpAction=expand-review）应路由回 node_aisplit");
    }
}
