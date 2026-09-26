package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaToolPreflightServiceTest {

    @Test
    void simpleExplicitImageRunsWithoutModelPreflight() {
        StructuredDecisionService decisions = mock(StructuredDecisionService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        MediaToolPreflightService service = service(decisions, assets);
        Map<String, Object> input = Map.of("prompt", "生成一张海边日落电影海报");

        Map<String, Object> result = service.guard(
                AgentScopeImageTools.GENERATE_IMAGE, input,
                param("生成一张海边日落电影海报", input),
                ignored -> Map.of("ok", true, "imageUrls", List.of("/result.png")));

        assertThat(result).containsEntry("ok", true);
        verify(decisions, never()).reviewMediaAction(anyString(), any(), anyString(), anyList());
        verify(decisions, never()).selectReferences(anyString(), anyList());
    }

    @Test
    void repairIsAppliedOnceAndOriginalInvocationUsesRepairedInput() {
        StructuredDecisionService decisions = mock(StructuredDecisionService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        when(decisions.reviewMediaAction(anyString(), any(), anyString(), anyList()))
                .thenReturn(new AgentScopeStructuredModels.MediaActionReview(
                        "REPAIR",
                        Map.of("prompt", "生成一张海边日落电影海报，暖色逆光"),
                        "",
                        0.9,
                        List.of("提示词过短")));
        MediaToolPreflightService service = service(decisions, assets);
        Map<String, Object> input = Map.of("prompt", "做图");
        AtomicReference<Map<String, Object>> executed = new AtomicReference<>();

        Map<String, Object> result = service.guard(
                AgentScopeImageTools.GENERATE_IMAGE, input,
                param("做图", input),
                repaired -> {
                    executed.set(repaired);
                    return Map.of("ok", true);
                });

        assertThat(result).containsEntry("preflightDecision", "REPAIR");
        assertThat(executed.get()).containsEntry(
                "prompt", "生成一张海边日落电影海报，暖色逆光");
        verify(decisions).reviewMediaAction(anyString(), any(), anyString(), anyList());
    }

    @Test
    void lowConfidenceReferenceResolutionAsksOnceWithoutExecutingMedia() {
        StructuredDecisionService decisions = mock(StructuredDecisionService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        when(assets.list("c1", null, 8, true)).thenReturn(List.of(
                new SessionAssetRef("a1", "image", "/a1.png", "generated",
                        java.time.OffsetDateTime.now(), "上一张图"),
                new SessionAssetRef("a2", "image", "/a2.png", "generated",
                        java.time.OffsetDateTime.now(), "另一张图")));
        when(decisions.selectReferences(anyString(), anyList()))
                .thenReturn(new AgentScopeStructuredModels.ReferenceSelection(
                        List.of(), 0.2, "无法确定"));
        MediaToolPreflightService service = service(decisions, assets);
        Map<String, Object> input = Map.of("prompt", "继续完善刚才那张图");

        Map<String, Object> result = service.guard(
                AgentScopeImageTools.GENERATE_IMAGE, input,
                param("继续完善刚才那张图", input),
                ignored -> Map.of("ok", true));

        assertThat(result)
                .containsEntry("ok", false)
                .containsEntry("needsClarification", true)
                .containsEntry("preflightDecision", "ASK");
        verify(decisions, never()).reviewMediaAction(anyString(), any(), anyString(), anyList());
    }

    @Test
    void modelTimeoutFailsOpenForExplicitRequest() {
        StructuredDecisionService decisions = mock(StructuredDecisionService.class);
        SessionAssetService assets = mock(SessionAssetService.class);
        when(decisions.reviewMediaAction(anyString(), any(), anyString(), anyList()))
                .thenReturn(new AgentScopeStructuredModels.MediaActionReview(
                        "EXECUTE", Map.of(), "", 0.0, List.of("fallback")));
        MediaToolPreflightService service = service(decisions, assets);
        Map<String, Object> input = Map.of("prompt", "做图");

        Map<String, Object> result = service.guard(
                AgentScopeImageTools.GENERATE_IMAGE, input,
                param("做图", input),
                ignored -> Map.of("ok", true));

        assertThat(result).containsEntry("preflightDecision", "FAIL_OPEN");
    }

    private static MediaToolPreflightService service(
            StructuredDecisionService decisions, SessionAssetService assets) {
        return new MediaToolPreflightService(
                new AgentScopeProperties(), decisions, assets, new SimpleMeterRegistry());
    }

    private static ToolCallParam param(String requestText, Map<String, Object> input) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, requestText, List.of(), null);
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation, request, null, new AgentScopeTurnState());
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId("c1")
                .put(AgentScopeRunContext.class, runContext)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("test")
                        .input(input)
                        .build())
                .input(input)
                .runtimeContext(runtime)
                .build();
    }
}
