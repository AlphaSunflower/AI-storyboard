package com.moon.moonagent.ai.agent.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatServiceImplCanvasExitTest {

    @Mock
    private AgentConversationMapper conversationMapper;

    @InjectMocks
    private AgentChatServiceImpl service;

    @Test
    void exitCanvasModeExplicitlyClearsPersistedCanvasContext() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setCanvasContext("""
                {"mode":"canvas-edit","projectId":"project-1","canvasId":"canvas-1","mediaRefs":[]}
                """);
        when(conversationMapper.selectById("conversation-1")).thenReturn(conversation);

        service.exitCanvasMode("user-1", "conversation-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AgentConversation>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(conversationMapper).update(eq(null), captor.capture());
        verify(conversationMapper, never()).updateById(any(AgentConversation.class));
        assertThat(captor.getValue().getSqlSet()).contains("canvas_context");
    }
}
