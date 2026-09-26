package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatServiceImplCheckpointSkipTest {

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private CheckpointResumeService checkpointResumeService;

    @InjectMocks
    private AgentChatServiceImpl service;

    @Test
    void skipPendingCheckpoints_marksPendingExpired() {
        AgentConversation conv = new AgentConversation();
        conv.setId("conv-1");
        conv.setUserId("user-1");
        lenient().when(conversationMapper.selectById("conv-1")).thenReturn(conv);
        when(checkpointResumeService.expirePending("conv-1")).thenReturn(2);

        service.skipPendingCheckpoints("user-1", "conv-1");

        verify(checkpointResumeService).expirePending("conv-1");
    }
}
