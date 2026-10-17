package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.document.LibraryReferenceAssetService;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.scene.SceneReferenceService;
import com.moon.moonagent.dto.request.AgentAttachmentRequest;
import com.moon.moonagent.dto.request.AgentReferenceRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目库文件引用的**转存接线**：中间件必须主动把 {@code kind=library_file} 转存成会话内 reference 资产，
 * 并以**会话所属用户**的身份调用（缺 userId 会被 asset-service 鉴权成 {@code internal}，
 * {@code WHERE owner_user_id='internal'} 永远查不到 → 每次引用都静默降级）。
 *
 * <p>成功 → 交出发给 {@code read_document} 的 assetId；失败/抛异常 → 降级成「正文不可用 + 元数据」，
 * 绝不因为一条引用打断整轮。
 */
class TurnContextMiddlewareLibraryReferenceTest {

    private final AgentWorkflowService workflows = mock(AgentWorkflowService.class);
    private final ProjectCreativeContextService creativeContext = mock(ProjectCreativeContextService.class);
    private final ConstraintExtractionService constraints = mock(ConstraintExtractionService.class);
    private final AgentDocumentService documents = mock(AgentDocumentService.class);
    private final SceneReferenceService sceneReferences = mock(SceneReferenceService.class);
    private final LibraryReferenceAssetService libraryAssets = mock(LibraryReferenceAssetService.class);

    private TurnContextMiddleware middleware() {
        when(constraints.extract(any())).thenReturn(
                new ConstraintLedger(CreativeMode.BALANCED, List.of(), List.of(), List.of()));
        when(workflows.activeContext(any())).thenReturn("");
        when(creativeContext.promptContext(any(), any())).thenReturn("");
        when(documents.recentSummaries("c1", 5)).thenReturn(List.of());
        when(sceneReferences.resolve(any(), anyList())).thenReturn(List.of());
        return new TurnContextMiddleware(workflows, creativeContext, constraints, documents,
                null, sceneReferences, libraryAssets);
    }

    @Test
    void transfersAsTheRealConversationUserAndHandsOutTheConversationAsset() {
        when(libraryAssets.ensureConversationAsset(any(), any(), any(), any())).thenReturn(
                new AgentAttachmentRequest("/api/files/files/converted.docx", "项目库.docx",
                        "application/msword", null, "file", "library", "asset-77", null));

        String prompt = systemPromptFor(middleware(), List.of(libraryReference()));

        // 身份三件套必须来自会话本身：conversationId / userId / projectId
        ArgumentCaptor<AgentReferenceRequest> captor =
                ArgumentCaptor.forClass(AgentReferenceRequest.class);
        verify(libraryAssets).ensureConversationAsset(eq("c1"), eq("u1"), eq("project-1"),
                captor.capture());
        assertThat(captor.getValue().token()).isEqualTo("@项目库.docx");
        assertThat(captor.getValue().mediaId()).isEqualTo("media-1");

        assertThat(prompt).contains("kind=library_file mediaId=media-1");
        assertThat(prompt).contains("read_document(assetId=asset-77)");
        assertThat(prompt).doesNotContain("正文不可用");
    }

    @Test
    void fallsBackToUnavailableWhenTransferFails() {
        when(libraryAssets.ensureConversationAsset(any(), any(), any(), any())).thenReturn(null);

        String prompt = systemPromptFor(middleware(), List.of(libraryReference()));

        assertThat(prompt).contains("kind=library_file mediaId=media-1");
        assertThat(prompt).contains("正文不可用");
        assertThat(prompt).doesNotContain("read_document");
        assertThat(prompt).contains("</turn-references>");
    }

    @Test
    void transferExceptionDoesNotBreakTheTurn() {
        when(libraryAssets.ensureConversationAsset(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        String prompt = systemPromptFor(middleware(), List.of(libraryReference()));

        assertThat(prompt).contains("kind=library_file mediaId=media-1");
        assertThat(prompt).contains("正文不可用");
        assertThat(prompt).contains("</turn-references>");
    }

    @Test
    void withoutWiringLibraryFileStillRendersMetadataOnly() {
        // 未接线（libraryAssets == null）时行为与接入前一致：只有元数据，没有 read_document 指引
        when(constraints.extract(any())).thenReturn(
                new ConstraintLedger(CreativeMode.BALANCED, List.of(), List.of(), List.of()));
        when(workflows.activeContext(any())).thenReturn("");
        when(creativeContext.promptContext(any(), any())).thenReturn("");
        when(documents.recentSummaries("c1", 5)).thenReturn(List.of());
        when(sceneReferences.resolve(any(), anyList())).thenReturn(List.of());
        TurnContextMiddleware unwired = new TurnContextMiddleware(
                workflows, creativeContext, constraints, documents, null, sceneReferences);

        String prompt = systemPromptFor(unwired, List.of(libraryReference()));

        assertThat(prompt).contains("kind=library_file mediaId=media-1");
        assertThat(prompt).contains("正文不可用");
    }

    private String systemPromptFor(TurnContextMiddleware middleware,
                                   List<AgentReferenceRequest> references) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("project-1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "看看 @项目库.docx 里的设定", List.of(), null);
        request.setReferences(references);
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation, request, null, new AgentScopeTurnState());
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId("c1")
                .put(AgentScopeRunContext.class, runContext)
                .build();
        return middleware.onSystemPrompt(null, runtime, "base").block();
    }

    private static AgentReferenceRequest libraryReference() {
        return new AgentReferenceRequest("library_file", "media-1", "@项目库.docx", "项目库.docx",
                "asset-2", "media-1", null, "application/msword", 100L, null);
    }
}
