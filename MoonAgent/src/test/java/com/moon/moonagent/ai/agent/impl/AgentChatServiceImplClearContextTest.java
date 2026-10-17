package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.ClearContextResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.context.ConversationContextCleanupService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.orchestration.OrchestrationMode;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「/清空上下文」的编排语义：会话锁保护、AgentScope 会话重置、
 * 标题回到「新对话」、初始化时间戳置空、画布上下文清空。
 */
@ExtendWith(MockitoExtension.class)
class AgentChatServiceImplClearContextTest {

    @Mock private AgentConversationMapper conversationMapper;
    @Mock private ConversationContextCleanupService contextCleanupService;
    @Mock private ConversationLock conversationLock;
    @Mock private AgentRuntimeSupport orchestratorSupport;
    @Mock private MoonAgentRuntime agentScopeMediaRuntime;
    @Mock private com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient sandboxClient;
    @Mock private com.moon.moonagent.ai.agent.sandbox.SandboxSessionRegistry sandboxSessionRegistry;

    @InjectMocks private AgentChatServiceImpl service;

    private static final ConversationContextCleanupService.CleanupReport REPORT =
            new ConversationContextCleanupService.CleanupReport(
                    3, 1, 2, 5, 1, 1, 2, 1, 1, 0, 0, 0, 0, 0);

    @org.junit.jupiter.api.BeforeEach
    void stubOptionalInfrastructure() {
        // 可选注入（@Autowired(required=false)）在纯 Mockito 下不保证落位，显式装一次
        ReflectionTestUtils.setField(service, "agentScopeMediaRuntime", agentScopeMediaRuntime);
        ReflectionTestUtils.setField(service, "sandboxClient", sandboxClient);
        ReflectionTestUtils.setField(service, "sandboxSessionRegistry", sandboxSessionRegistry);
        // 默认沙箱未启用：cleanupSandbox 只做登记；需要验证真调用时在用例里覆盖
        org.mockito.Mockito.lenient().when(sandboxClient.enabled()).thenReturn(false);
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        conversation.setStatus("active");
        conversation.setOrchestrationMode(OrchestrationMode.AGENTSCOPE.name());
        conversation.setTitle("改过名的会话");
        return conversation;
    }

    @Test
    void hardDeletesContextAndResetsConversationToFreshState() {
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(conversationLock.isLocked("conversation-1")).thenReturn(false);
        when(conversationLock.tryAcquire("conversation-1")).thenReturn(true);
        when(contextCleanupService.deleteConversationContext("conversation-1")).thenReturn(REPORT);

        ClearContextResult result = service.clearContext("user-1", "conversation-1");

        verify(contextCleanupService).deleteConversationContext("conversation-1");
        // AgentScope 运行时上下文必须走 resetSession（清上下文 + 删持久化状态 + 清缓存），
        // 只删 store 会让本地 stateCache 里的旧上下文继续生效。
        verify(agentScopeMediaRuntime).resetSession("user-1", "conversation-1");
        verify(orchestratorSupport).cleanupOnDelete("conversation-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AgentConversation>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(conversationMapper).update(eq(null), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertThat(sqlSet).contains("title");
        assertThat(sqlSet).contains("canvas_context");
        assertThat(sqlSet).contains("agentscope_session_initialized_at");

        assertThat(result.title()).isEqualTo("新对话");
        assertThat(result.deletedRows()).isEqualTo(17);
        verify(conversationLock).release("conversation-1");
    }

    @Test
    void clearsConversationEvenWhenNoAgentScopeSessionWasConfigured() {
        ReflectionTestUtils.setField(service, "agentScopeMediaRuntime", null);
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(conversationLock.tryAcquire("conversation-1")).thenReturn(true);
        when(contextCleanupService.deleteConversationContext("conversation-1")).thenReturn(REPORT);
        when(sandboxClient.enabled()).thenReturn(true);

        assertThat(service.clearContext("user-1", "conversation-1").title()).isEqualTo("新对话");

        verify(contextCleanupService).deleteConversationContext("conversation-1");
        // 没有 AgentScope 运行时也要把沙箱工作区清掉
        verify(sandboxClient).cleanup("conversation-1");
        verify(sandboxSessionRegistry).markCleaned("conversation-1");
        verify(conversationLock).release("conversation-1");
    }

    @Test
    void interruptsInFlightTurnBeforeTakingTheLock() {
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(conversationLock.isLocked("conversation-1")).thenReturn(true);
        when(conversationLock.tryAcquire("conversation-1")).thenReturn(true);
        when(contextCleanupService.deleteConversationContext("conversation-1")).thenReturn(REPORT);

        service.clearContext("user-1", "conversation-1");

        verify(orchestratorSupport).interrupt("conversation-1");
    }

    @Test
    void refusesWhenAnotherTurnStillHoldsTheConversationLock() {
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(conversationLock.isLocked("conversation-1")).thenReturn(true);
        when(conversationLock.tryAcquire("conversation-1")).thenReturn(false);

        assertThatThrownBy(() -> service.clearContext("user-1", "conversation-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仍在处理中");

        verify(contextCleanupService, never()).deleteConversationContext(any());
        verify(conversationMapper, never()).update(any(), any());
    }

    @Test
    void rejectsArchivedConversation() {
        AgentConversation archived = conversation();
        archived.setStatus("archived");
        when(conversationMapper.selectById("conversation-1")).thenReturn(archived);

        assertThatThrownBy(() -> service.clearContext("user-1", "conversation-1"))
                .isInstanceOf(BusinessException.class);

        verify(contextCleanupService, never()).deleteConversationContext(any());
    }
}
