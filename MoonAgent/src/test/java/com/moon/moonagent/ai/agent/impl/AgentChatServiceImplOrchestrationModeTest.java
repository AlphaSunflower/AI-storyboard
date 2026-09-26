package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.agent.AgentAnswerService;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.ConversationLock;
import com.moon.moonagent.ai.agent.ConversationTitleService;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentChatServiceImplOrchestrationModeTest {

    @Test
    void newConversationsAlwaysUseUnifiedLoop() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        when(storyboardClient.getProject("project-1")).thenReturn(Map.of("userId", "user-1"));
        AgentChatServiceImpl service = service(conversationMapper, storyboardClient);
        service.createConversation("user-1", "project-1", "title");

        verify(conversationMapper).insert(any(AgentConversation.class));
        org.mockito.ArgumentCaptor<AgentConversation> captor =
                org.mockito.ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrchestrationMode()).isEqualTo("UNIFIED_LOOP");
        assertThat(captor.getValue().getEngineVersion()).isEqualTo("unified-loop-v1");
        assertThat(captor.getValue().getDebugComparisonEnabled()).isFalse();
    }

    @Test
    void requestedLegacyModeIsIgnored() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        when(storyboardClient.getProject("project-1")).thenReturn(Map.of("userId", "user-1"));
        AgentChatServiceImpl service = service(conversationMapper, storyboardClient);
        service.createConversation("user-1", "project-1", "title", "LEGACY");

        org.mockito.ArgumentCaptor<AgentConversation> captor =
                org.mockito.ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrchestrationMode()).isEqualTo("UNIFIED_LOOP");
    }

    @Test
    void explicitUnifiedLoopRemainsUnifiedLoop() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        StoryboardClient storyboardClient = mock(StoryboardClient.class);
        when(storyboardClient.getProject("project-1")).thenReturn(Map.of("userId", "user-1"));
        AgentChatServiceImpl service = service(conversationMapper, storyboardClient);
        service.createConversation("user-1", "project-1", "title", "UNIFIED_LOOP");

        org.mockito.ArgumentCaptor<AgentConversation> captor =
                org.mockito.ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getOrchestrationMode()).isEqualTo("UNIFIED_LOOP");
    }

    @Test
    void archivedConversationRejectsWrites() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentConversation conversation = writableConversation("UNIFIED_LOOP");
        conversation.setStatus("archived");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        AgentChatServiceImpl service = service(conversationMapper, mock(StoryboardClient.class));

        assertThatThrownBy(() -> service.sendMessage("user-1", "conversation-1", "继续"))
                .isInstanceOf(com.storyboard.common.BusinessException.class)
                .hasMessageContaining("已归档");
    }

    @Test
    void legacyConversationRejectsWrites() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentConversation conversation = writableConversation("LEGACY");
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);
        AgentChatServiceImpl service = service(conversationMapper, mock(StoryboardClient.class));

        assertThatThrownBy(() -> service.sendMessage("user-1", "conversation-1", "继续"))
                .isInstanceOf(com.storyboard.common.BusinessException.class)
                .hasMessageContaining("旧版会话已只读归档");
    }

    private static AgentConversation writableConversation(String mode) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setStatus("active");
        conversation.setOrchestrationMode(mode);
        conversation.setEngineVersion("unified-loop-v1");
        return conversation;
    }

    private static AgentChatServiceImpl service(AgentConversationMapper conversationMapper,
                                                StoryboardClient storyboardClient) {
        return new AgentChatServiceImpl(
                conversationMapper,
                mock(AgentGenerationService.class),
                mock(AgentMessageMapper.class),
                mock(AgentAssetMapper.class),
                mock(ConversationTitleService.class),
                mock(IntentRecognitionService.class),
                storyboardClient,
                mock(FileStorageService.class),
                mock(AiConfigProperties.class),
                mock(com.moon.moonagent.ai.agent.AgentOrchestrator.class),
                mock(AgentOrchestratorSupport.class),
                mock(AgentCheckpointMapper.class),
                mock(CheckpointResumeService.class),
                mock(ConversationLock.class),
                mock(AgentAnswerService.class),
                mock(PlatformTransactionManager.class));
    }
}
