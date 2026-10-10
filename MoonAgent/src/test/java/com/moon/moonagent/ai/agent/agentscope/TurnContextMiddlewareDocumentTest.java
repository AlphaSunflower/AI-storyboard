package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.document.DocumentSummaryView;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnContextMiddlewareDocumentTest {

    @Test
    void injectsDocumentMetadataAndSummaryPolicy() {
        AgentDocumentService documents = mock(AgentDocumentService.class);
        when(documents.recentSummaries("c1", 5)).thenReturn(List.of(
                new DocumentSummaryView("a1", "brief.md", "text/markdown", "已有摘要", "ready")));
        AgentWorkflowService workflows = mock(AgentWorkflowService.class);
        when(workflows.activeContext("c1")).thenReturn("");
        ProjectCreativeContextService creative = mock(ProjectCreativeContextService.class);
        when(creative.promptContext(null, "继续分析")).thenReturn("");
        ConstraintExtractionService constraints = mock(ConstraintExtractionService.class);
        when(constraints.extract("继续分析"))
                .thenReturn(new com.moon.moonagent.ai.agent.creative.ConstraintLedger(
                        com.moon.moonagent.ai.agent.creative.CreativeMode.BALANCED,
                        List.of(), List.of(), List.of()));
        TurnContextMiddleware middleware = new TurnContextMiddleware(
                workflows, creative, constraints, documents);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "继续分析", List.of(), null);
        request.setAttachments(List.of(new AgentAttachmentRequest(
                "/api/files/files/a1.md", "brief.md", "text/markdown",
                100L, "file", "upload", "a1", null)));
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation, request, null, new AgentScopeTurnState());
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId("c1")
                .put(AgentScopeRunContext.class, runContext)
                .build();

        String prompt = middleware.onSystemPrompt(null, runtime, "base").block();

        assertThat(prompt).contains("assetId=a1");
        assertThat(prompt).contains("已有摘要");
        assertThat(prompt).contains("常规追问使用摘要");
        assertThat(prompt).contains("read_document");
    }
}
