package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatServiceImplCheckpointSkipTest {

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentCheckpointMapper checkpointMapper;

    @InjectMocks
    private AgentChatServiceImpl service;

    @Test
    void skipPendingCheckpoints_marksPendingExpired() {
        AgentConversation conv = new AgentConversation();
        conv.setId("conv-1");
        conv.setUserId("user-1");
        lenient().when(conversationMapper.selectById("conv-1")).thenReturn(conv);
        when(checkpointMapper.update(eq(null), any())).thenReturn(2);

        service.skipPendingCheckpoints("user-1", "conv-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AgentCheckpoint>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(checkpointMapper).update(eq(null), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("status"));
    }
}
