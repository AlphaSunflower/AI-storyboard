package com.moon.moonagent.ai.agent.specialist.impl;

import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerDelegatingSpecialistAssetIdsTest {

    @Test
    void handleBranchCarriesAssetIdsIntoHandlerRequest() {
        AtomicReference<OrchestrationRequest> captured = new AtomicReference<>();
        IntentHandler handler = new IntentHandler() {
            @Override public String intentType() { return "intent-aisplit"; }
            @Override public java.util.Set<String> resumeActions() { return java.util.Set.of(); }
            @Override public String handle(OrchestrationRequest request) {
                captured.set(request);
                return "ok";
            }
            @Override public String resume(OrchestrationRequest request,
                                           com.moon.moonagent.entity.AgentCheckpoint checkpoint) {
                return "resume";
            }
        };
        AgentConversation conversation = new AgentConversation();
        SpecialistContext context = new SpecialistContext(
                conversation, "根据资产做转场", List.of(), new SseEmitter(),
                null, null, "", "", Map.of(),
                List.of("asset-1"), "", "");

        SpecialistResult result = new HandlerDelegatingSpecialist(handler).execute(context);

        assertTrue(!result.needsHandoff());
        assertEquals("ok", result.message());
        assertEquals(List.of("asset-1"), captured.get().getAssetIds());
    }
}
