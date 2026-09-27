package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.agentscope.MoonAgentRuntime;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.turn.AgentScopeEventMapper;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStore;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatServiceImplTurnTest {

    @Test
    void editTurnUpdatesTargetDeletesLaterMessagesAndRunsAgentScope() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        AgentScopeEventMapper eventMapper = mock(AgentScopeEventMapper.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        CheckpointResumeService checkpoints = mock(CheckpointResumeService.class);

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.tryAcquire("conversation-1")).thenReturn(true);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        AgentMessage target = message("user-2", "user", "原问题");
        target.setMetadata(Map.of("messageInput", Map.of(
                "picUrls", List.of("/api/files/images/ref.png"),
                "assetIds", List.of("asset-1"))));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user-1", "user", "第一条"),
                message("assistant-1", "assistant", "第一条回复"),
                target,
                message("assistant-2", "assistant", "旧回复")));
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnStore.start(any(), eq("修改后的问题"))).thenReturn(turn);
        AtomicInteger sequence = new AtomicInteger();
        when(turnStore.append(anyString(), anyString(), any())).thenAnswer(invocation ->
                new AgentTurnEventEnvelope(
                        invocation.getArgument(0),
                        sequence.incrementAndGet(),
                        invocation.getArgument(1),
                        Map.of("ok", true)));
        when(runtime.runTurn(any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("新回复", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support, checkpoints, lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", eventMapper);

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "修改后的问题", List.of(),
                List.of(), null, "user-2", null, List.of(), List.of(),
                new SseEmitter(0L));

        verify(messageMapper, timeout(2_000)).updateById(any(AgentMessage.class));
        ArgumentCaptor<AgentMessage> updated = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo("user-2");
        assertThat(updated.getValue().getContent()).isEqualTo("修改后的问题");
        verify(messageMapper).deleteBatchIds(List.of("assistant-2"));
        verify(runtime).rewindLastTurn("user-1", "conversation-1", "原问题");
        verify(runtime, timeout(2_000)).runTurn(any(), any(), any(), any());
        verify(lock, timeout(2_000).atLeastOnce()).release("conversation-1");
    }

    @Test
    void newTurnPersistsUserMessageWithItsInputSnapshot() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(lock.tryAcquire("conversation-1")).thenReturn(true);
        when(runtime.enabled()).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(false);
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnStore.start(any(), eq("带参考图的问题"))).thenReturn(turn);
        when(turnStore.append(anyString(), anyString(), any())).thenReturn(
                new AgentTurnEventEnvelope("turn-1", 1, "turn_started", Map.of()));
        when(runtime.runTurn(any(), any(), any(), any())).thenReturn(
                new AgentTurnResult("回复", Map.of(), List.of(), null,
                        List.of(), false, ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "带参考图的问题",
                List.of("/api/files/images/ref.png"), List.of("asset-1"),
                null, null, null, List.of(), List.of(), new SseEmitter(0L));

        ArgumentCaptor<AgentMessage> inserted = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, timeout(2_000).atLeastOnce()).insert(inserted.capture());
        AgentMessage userMessage = inserted.getAllValues().stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> input =
                (Map<String, Object>) userMessage.getMetadata().get("messageInput");
        assertThat(input.get("picUrls")).isEqualTo(List.of("/api/files/images/ref.png"));
        assertThat(input.get("assetIds")).isEqualTo(List.of("asset-1"));
    }

    @Test
    void turnCreationFailureReturnsAnErrorEnvelopeWithoutPersistingUserContent()
            throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AgentTurnEventStore turnStore = mock(AgentTurnEventStore.class);
        MoonAgentRuntime runtime = mock(MoonAgentRuntime.class);
        AgentRuntimeSupport support = mock(AgentRuntimeSupport.class);
        SseEmitter emitter = mock(SseEmitter.class);

        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(lock.tryAcquire("conversation-1")).thenReturn(true);
        when(runtime.enabled()).thenReturn(true);
        when(support.friendlyErrorText(any(), any())).thenReturn("运行时暂不可用");
        when(turnStore.start(any(), any())).thenThrow(
                new IllegalStateException("agent_turns missing"));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, support,
                mock(CheckpointResumeService.class), lock, transactionManager);
        inject(service, "agentScopeMediaRuntime", runtime);
        inject(service, "turnEventStore", turnStore);
        inject(service, "agentScopeEventMapper", mock(AgentScopeEventMapper.class));

        service.streamAgentScopeTurn(
                "user-1", "conversation-1", "会失败的问题", List.of(),
                List.of(), null, null, null, List.of(), List.of(), emitter);

        verify(emitter, timeout(2_000).atLeastOnce())
                .send(any(SseEmitter.SseEventBuilder.class));
        verify(messageMapper, never()).insert(any(AgentMessage.class));
        verify(lock, timeout(2_000).atLeastOnce()).release("conversation-1");
    }

    private static AgentChatServiceImpl service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentRuntimeSupport support,
            CheckpointResumeService checkpoints,
            ConversationLock lock,
            PlatformTransactionManager transactionManager) {
        return new AgentChatServiceImpl(
                conversationMapper,
                mock(AgentGenerationService.class),
                messageMapper,
                mock(AgentAssetMapper.class),
                mock(ConversationTitleService.class),
                mock(StoryboardClient.class),
                mock(FileStorageService.class),
                mock(AiConfigProperties.class),
                support,
                mock(AgentCheckpointMapper.class),
                checkpoints,
                lock,
                transactionManager);
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setStatus("active");
        conversation.setOrchestrationMode("AGENTSCOPE");
        return conversation;
    }

    private static AgentMessage message(String id, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setConversationId("conversation-1");
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = AgentChatServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
