package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorSupportMaterialContextTest {

    @Test
    void enrichMaterialContext_addsPicUrlsAndAssetIdsToEveryCard() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "转场需求", List.of("img1.png", "img2.png"), new SseEmitter());
        request.setAssetIds(List.of("asset-1", "asset-2"));

        var enriched = AgentOrchestratorSupport.enrichMaterialContext(
                request, List.of(Map.of("content", "转场需求"), Map.of("content", "second")));

        assertEquals(2, enriched.size());
        assertEquals(List.of("img1.png", "img2.png"), enriched.get(0).get("picUrls"));
        assertEquals(List.of("asset-1", "asset-2"), enriched.get(0).get("assetIds"));
        assertEquals(List.of("img1.png", "img2.png"), enriched.get(1).get("picUrls"));
        assertEquals(List.of("asset-1", "asset-2"), enriched.get(1).get("assetIds"));
    }

    @Test
    void enrichMaterialContext_preservesExistingFieldsWithoutOverwriting() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "x", List.of("img"), new SseEmitter());

        var enriched = AgentOrchestratorSupport.enrichMaterialContext(
                request, List.of(Map.of("content", "x", "picUrls", List.of("kept"))));

        assertEquals(List.of("kept"), enriched.get(0).get("picUrls"));
        assertFalse(enriched.get(0).containsKey("assetIds"));
    }

    @Test
    void enrichMaterialContext_returnsOriginalWhenNoMaterials() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "x", List.of(), new SseEmitter());
        List<Map<String, Object>> payload = List.of(Map.of("content", "x"));

        var enriched = AgentOrchestratorSupport.enrichMaterialContext(request, payload);

        assertTrue(enriched == payload);
    }
}
