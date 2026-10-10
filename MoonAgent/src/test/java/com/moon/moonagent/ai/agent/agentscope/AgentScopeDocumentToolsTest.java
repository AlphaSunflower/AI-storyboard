package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.document.DocumentProperties;
import com.moon.moonagent.ai.agent.document.DocumentReadResult;
import com.moon.moonagent.ai.agent.document.DocumentSummaryView;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeDocumentToolsTest {

    @Test
    void readsDocumentForCurrentConversation() {
        AgentDocumentService documents = mock(AgentDocumentService.class);
        when(documents.read("c1", "a1", 10, 100)).thenReturn(new DocumentReadResult(
                "a1", "brief.md", "text/markdown", "摘要", "ready",
                10, 20, 50, false, "正文"));
        AgentScopeDocumentTools tools = new AgentScopeDocumentTools(
                documents, new DocumentProperties());
        AgentScopeTool tool = tools.tools().stream()
                .filter(item -> item.getName().equals(AgentScopeDocumentTools.READ_DOCUMENT))
                .findFirst()
                .orElseThrow();

        Object result = tool.invoke(
                Map.of("assetId", "a1", "offset", "10", "maxChars", "100"),
                param("c1"));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result;
        assertThat(output)
                .containsEntry("assetId", "a1")
                .containsEntry("markdown", "正文")
                .containsEntry("nextOffset", 20);
    }

    @Test
    void listsRecentDocumentSummariesForCurrentConversation() {
        AgentDocumentService documents = mock(AgentDocumentService.class);
        when(documents.recentSummaries("c1", 5)).thenReturn(List.of(
                new DocumentSummaryView("a1", "brief.md", "text/markdown", "摘要", "ready")));
        AgentScopeDocumentTools tools = new AgentScopeDocumentTools(
                documents, new DocumentProperties());
        AgentScopeTool tool = tools.tools().stream()
                .filter(item -> item.getName().equals(
                        AgentScopeDocumentTools.LIST_CONVERSATION_DOCUMENTS))
                .findFirst()
                .orElseThrow();

        Object result = tool.invoke(Map.of("limit", "5"), param("c1"));

        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).hasSize(1);
    }

    private static ToolCallParam param(String conversationId) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(conversationId);
        conversation.setUserId("u1");
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation,
                new OrchestrationRequest(conversation, "读取文档", List.of(), null),
                null,
                new AgentScopeTurnState());
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId(conversationId)
                .put(AgentScopeRunContext.class, runContext)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("read_document")
                        .input(Map.of())
                        .build())
                .input(Map.of())
                .runtimeContext(runtime)
                .build();
    }
}
