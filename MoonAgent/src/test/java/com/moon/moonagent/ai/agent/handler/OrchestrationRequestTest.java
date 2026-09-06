package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
