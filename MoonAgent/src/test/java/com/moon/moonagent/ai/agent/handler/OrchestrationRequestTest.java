package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestrationRequestTest {

    @Test
    void resumePicUrlsOverrideConstructorValue() {
        AgentConversation conv = new AgentConversation();
        OrchestrationRequest request = new OrchestrationRequest(conv, "故事", List.of("orig"), new SseEmitter());
        assertEquals("orig", request.getPicUrl());

        request.setPicUrls(List.of("restored-1", "restored-2"));

        assertEquals(List.of("restored-1", "restored-2"), request.getPicUrls());
        assertEquals("restored-1", request.getPicUrl());
    }

    @Test
    void emptyConstructorPicUrlsRemainsNullSafe() {
        AgentConversation conv = new AgentConversation();
        OrchestrationRequest request = new OrchestrationRequest(conv, "故事", null, new SseEmitter());
        assertEquals(null, request.getPicUrl());
    }

    @Test
    void handoffStateMergesFilesAssetsAndParams() {
        AgentConversation conv = new AgentConversation();
        OrchestrationRequest request = new OrchestrationRequest(
                conv, "故事", List.of("original.png"), new SseEmitter());
        request.setAssetIds(List.of("asset-1"));
        request.setParams(Map.of("model", "a"));

        request.applyHandoffState(Map.of(
                "picUrls", List.of("generated.png"),
                "referenceImages", List.of(Map.of("url", "reference.png")),
                "assetIds", List.of("asset-2"),
                "params", Map.of("duration", "8")));

        assertEquals(List.of("original.png", "generated.png", "reference.png"), request.getPicUrls());
        assertEquals(List.of("asset-1", "asset-2"), request.getAssetIds());
        assertEquals(Map.of("model", "a", "duration", "8"), request.getParams());
        assertTrue(request.getHandoffState().containsKey("picUrls"));
    }
}
