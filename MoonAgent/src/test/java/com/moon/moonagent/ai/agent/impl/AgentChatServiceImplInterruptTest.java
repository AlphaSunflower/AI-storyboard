package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.AgentAnswerService;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatServiceImplInterruptTest {

    @Test
    void interruptedTurnIsNotPersistedAsAssistantMessage() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        com.moon.moonagent.ai.agent.AgentOrchestrator orchestrator =
                mock(com.moon.moonagent.ai.agent.AgentOrchestrator.class);
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        ConversationLock lock = mock(ConversationLock.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setStatus("active");
        conversation.setOrchestrationMode("UNIFIED_LOOP");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        when(lock.tryAcquire("conversation-1")).thenReturn(true);
        when(support.isInterrupted("conversation-1")).thenReturn(true);
        when(orchestrator.runTurn(
                any(), eq("停止后不要落库"), any(), any(), any(), anyBoolean(),
                any(), any(), any(), any()))
                .thenReturn(new AgentTurnResult(
                        "这轮回复不应落库",
                        Map.of("_terminalEventSent", true),
                        List.of(),
                        null,
                        List.of(),
                        false,
                        ""));

        AgentChatServiceImpl service = new AgentChatServiceImpl(
                conversationMapper,
                mock(AgentGenerationService.class),
                messageMapper,
                mock(AgentAssetMapper.class),
                mock(ConversationTitleService.class),
                mock(IntentRecognitionService.class),
                mock(StoryboardClient.class),
                mock(FileStorageService.class),
                mock(AiConfigProperties.class),
                orchestrator,
                support,
                mock(AgentCheckpointMapper.class),
                mock(CheckpointResumeService.class),
                lock,
                mock(AgentAnswerService.class),
                transactionManager);

        service.streamMessage(
                "user-1",
                "conversation-1",
                "停止后不要落库",
                List.of(),
                List.of(),
                new SseEmitter(0L),
                null,
                false,
                null,
                List.of(),
                List.of());

        verify(lock, timeout(2_000)).release("conversation-1");
        ArgumentCaptor<AgentMessage> messages = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, times(1)).insert(messages.capture());
        assertThat(messages.getValue().getRole()).isEqualTo("user");
    }
}
