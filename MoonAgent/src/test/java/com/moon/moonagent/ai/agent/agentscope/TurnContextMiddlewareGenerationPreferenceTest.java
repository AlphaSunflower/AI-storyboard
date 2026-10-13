package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnContextMiddlewareGenerationPreferenceTest {

    private final AgentWorkflowService workflows = mock(AgentWorkflowService.class);
    private final ProjectCreativeContextService creativeContext = mock(ProjectCreativeContextService.class);
    private final ConstraintExtractionService constraints = mock(ConstraintExtractionService.class);
    private final AgentDocumentService documents = mock(AgentDocumentService.class);

    private TurnContextMiddleware middleware() {
        when(constraints.extract(any())).thenReturn(
                new ConstraintLedger(CreativeMode.BALANCED, List.of(), List.of(), List.of()));
        when(workflows.activeContext(any())).thenReturn("");
        when(creativeContext.promptContext(any(), any())).thenReturn("");
        return new TurnContextMiddleware(workflows, creativeContext, constraints, documents);
    }

    private static String systemPromptFor(TurnContextMiddleware middleware,
                                          AgentGenerationPreferences preferences) {
        AgentConversation conversation = mock(AgentConversation.class);
        OrchestrationRequest request = new OrchestrationRequest(conversation, "来张图", List.of(), null);
        request.setGenerationPreferences(preferences);
        RuntimeContext context = RuntimeContext.builder()
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(conversation, request, null, new AgentScopeTurnState()))
                .build();
        return middleware.onSystemPrompt(null, context, "SYSTEM").block();
    }

    @Test
    void automaticModeInjectsNoGenerationPreferenceContext() {
        String prompt = systemPromptFor(middleware(), AgentGenerationPreferences.none());

        assertThat(prompt).doesNotContain("generation-preferences");
        assertThat(prompt).doesNotContain("skip generation plans");
    }

    @Test
    void customModeDeclaresTheFixedImageAndVideoSettings() {
        String prompt = systemPromptFor(middleware(), AgentGenerationPreferences.of(
                false, false,
                Map.of("model", "doubao-seedream-4.0", "aspectRatio", "16:9", "size", "2K"),
                Map.of("resolution", "1080P")));

        assertThat(prompt).contains("<generation-preferences>");
        assertThat(prompt).contains("Image: model=doubao-seedream-4.0, aspect ratio=16:9, size=2K");
        assertThat(prompt).contains("Video: resolution=1080P");
        assertThat(prompt).contains("Omit any parameter the user left on automatic");
    }

    @Test
    void ignoreGenerationPlanDeclaresTheMediaOnlyExemption() {
        String prompt = systemPromptFor(middleware(),
                AgentGenerationPreferences.of(true, true, Map.of(), Map.of()));

        assertThat(prompt).contains("skip generation plans for media");
        assertThat(prompt).contains("media generation tools only");
        assertThat(prompt).doesNotContain("<generation-preferences>");
    }
}
