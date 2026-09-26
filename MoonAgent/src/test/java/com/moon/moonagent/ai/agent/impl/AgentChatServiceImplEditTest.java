package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.AgentAnswerService;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeMediaRuntime;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.ai.agent.turn.AgentScopeEventMapper;
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
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatServiceImplEditTest {

    @Test
    void rejectsEditingAnythingExceptTheLastUserMessage() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentChatServiceImpl service = service(conversationMapper, messageMapper,
                mock(com.moon.moonagent.ai.agent.AgentOrchestrator.class),
                mock(AgentOrchestratorSupport.class), mock(ConversationLock.class),
                mock(PlatformTransactionManager.class));
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user-1", "user", "第一条"),
                message("assistant-1", "assistant", "旧回复"),
                message("user-2", "user", "最后一条"),
                message("assistant-2", "assistant", "最新回复")));

        assertThatThrownBy(() -> service.editAndResend(
                "user-1", "conversation-1", "user-1", "修改第一条", new SseEmitter(0L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能编辑最后一条");
    }

    @Test
    void replacesTargetTurnDeletesLaterMessagesAndReplaysEditedContent() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        com.moon.moonagent.ai.agent.AgentOrchestrator orchestrator =
                mock(com.moon.moonagent.ai.agent.AgentOrchestrator.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AgentScopeMediaRuntime mediaRuntime = mock(AgentScopeMediaRuntime.class);
        AgentTurnEventStore turnEventStore = mock(AgentTurnEventStore.class);
        AgentScopeEventMapper eventMapper = mock(AgentScopeEventMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(lock.tryAcquire("conversation-1")).thenReturn(true);
        AgentMessage target = message("user-2", "user", "原问题");
        target.setMetadata(Map.of("messageInput", Map.of(
                "picUrls", List.of("/api/files/images/ref.png"),
                "assetIds", List.of("asset-1"))));
        AgentMessage lateReply = message("assistant-2", "assistant", "旧回复");
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user-1", "user", "第一条"),
                message("assistant-1", "assistant", "第一条回复"),
                target,
                lateReply));
        when(mediaRuntime.enabled()).thenReturn(true);
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        when(turnEventStore.start(any(), eq("修改后的问题"))).thenReturn(turn);
        when(mediaRuntime.runTurn(any(), any(), any(), any()))
                .thenReturn(new AgentTurnResult(
                        "新回复",
                        Map.of(),
                        List.of(),
                        null,
                        List.of(),
                        false,
                        ""));

        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, orchestrator, support, lock, transactionManager);
        injectTurnRuntime(service, mediaRuntime, turnEventStore, eventMapper);
        service.editAndResend(
                "user-1", "conversation-1", "user-2", "修改后的问题", new SseEmitter(0L));

        verify(lock, timeout(2_000).atLeastOnce()).release("conversation-1");
        ArgumentCaptor<AgentMessage> updated = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo("user-2");
        assertThat(updated.getValue().getContent()).isEqualTo("修改后的问题");
        verify(messageMapper).deleteBatchIds(List.of("assistant-2"));
        verify(mediaRuntime).rewindLastTurn("user-1", "conversation-1", "原问题");
        verify(mediaRuntime, timeout(2_000)).runTurn(any(), any(), any(), any());
    }

    @Test
    void storesOriginalInputSnapshotOnTheUserMessage() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        com.moon.moonagent.ai.agent.AgentOrchestrator orchestrator =
                mock(com.moon.moonagent.ai.agent.AgentOrchestrator.class);
        ConversationLock lock = mock(ConversationLock.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(lock.tryAcquire("conversation-1")).thenReturn(true);
        when(orchestrator.runTurn(
                any(), eq("带参考图的问题"), any(), any(), any(), anyBoolean(),
                any(), any(), any(), any()))
                .thenReturn(new AgentTurnResult(
                        "回复",
                        Map.of("_terminalEventSent", true),
                        List.of(),
                        null,
                        List.of(),
                        false,
                        ""));
        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, orchestrator,
                mock(AgentOrchestratorSupport.class), lock, transactionManager);

        service.streamMessage(
                "user-1", "conversation-1", "带参考图的问题",
                List.of("/api/files/images/ref.png"), List.of("asset-1"),
                new SseEmitter(0L), null, false, null, List.of(), List.of());

        verify(lock, timeout(2_000).atLeastOnce()).release("conversation-1");
        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, times(2)).insert(messages.capture());
        AgentMessage userMessage = messages.getAllValues().stream()
                .filter(message -> "user".equals(message.getRole()))
                .findFirst()
                .orElseThrow();
        assertThat(userMessage.getMetadata()).containsKey("messageInput");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) userMessage.getMetadata().get("messageInput");
        assertThat(input.get("picUrls")).isEqualTo(List.of("/api/files/images/ref.png"));
        assertThat(input.get("assetIds")).isEqualTo(List.of("asset-1"));
    }

    @Test
    void interruptAndSendWaitsForTheConversationLockBeforeStartingTheNewTurn() throws Exception {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        com.moon.moonagent.ai.agent.AgentOrchestrator orchestrator =
                mock(com.moon.moonagent.ai.agent.AgentOrchestrator.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AgentScopeMediaRuntime mediaRuntime = mock(AgentScopeMediaRuntime.class);
        AgentTurnEventStore turnEventStore = mock(AgentTurnEventStore.class);
        AgentScopeEventMapper eventMapper = mock(AgentScopeEventMapper.class);
        ConversationLock lock = mock(ConversationLock.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation());
        when(lock.acquireWithTimeout(eq("conversation-1"), any(Duration.class))).thenReturn(true);
        when(lock.tryAcquire("conversation-1")).thenReturn(true);
        when(mediaRuntime.enabled()).thenReturn(true);
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-2");
        when(turnEventStore.start(any(), eq("新的指导"))).thenReturn(turn);
        when(mediaRuntime.runTurn(any(), any(), any(), any()))
                .thenReturn(new AgentTurnResult(
                        "新回复",
                        Map.of(),
                        List.of(),
                        null,
                        List.of(),
                        false,
                        ""));
        AgentChatServiceImpl service = service(
                conversationMapper, messageMapper, orchestrator, support, lock, transactionManager);
        injectTurnRuntime(service, mediaRuntime, turnEventStore, eventMapper);

        service.interruptAndStream(
                "user-1", "conversation-1", "新的指导",
                List.of(), List.of(), new SseEmitter(0L), null, true, null, List.of(), List.of());

        verify(support, timeout(2_000)).interrupt("conversation-1");
        verify(lock, timeout(2_000)).acquireWithTimeout(eq("conversation-1"), any(Duration.class));
        ArgumentCaptor<AgentMessage> inserted = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, timeout(2_000).atLeastOnce()).insert(inserted.capture());
        assertThat(inserted.getAllValues()).anyMatch(message ->
                "user".equals(message.getRole()) && "新的指导".equals(message.getContent()));
        verify(mediaRuntime, timeout(2_000)).runTurn(any(), any(), any(), any());
        verify(lock, timeout(2_000).atLeastOnce()).release("conversation-1");
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setStatus("active");
        conversation.setOrchestrationMode("UNIFIED_LOOP");
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

    private static AgentChatServiceImpl service(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            com.moon.moonagent.ai.agent.AgentOrchestrator orchestrator,
            AgentOrchestratorSupport support,
            ConversationLock lock,
            PlatformTransactionManager transactionManager) {
        return new AgentChatServiceImpl(
                conversationMapper,
                mock(AgentGenerationService.class),
                messageMapper,
                null,
                mock(ConversationTitleService.class),
                mock(IntentRecognitionService.class),
                null,
                mock(FileStorageService.class),
                mock(AiConfigProperties.class),
                orchestrator,
                support,
                mock(AgentCheckpointMapper.class),
                mock(CheckpointResumeService.class),
                lock,
                mock(AgentAnswerService.class),
                transactionManager);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = AgentChatServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void injectTurnRuntime(Object target, AgentScopeMediaRuntime runtime,
                                          AgentTurnEventStore store,
                                          AgentScopeEventMapper mapper) throws Exception {
        inject(target, "agentScopeMediaRuntime", runtime);
        inject(target, "turnEventStore", store);
        inject(target, "agentScopeEventMapper", mapper);
    }
}
