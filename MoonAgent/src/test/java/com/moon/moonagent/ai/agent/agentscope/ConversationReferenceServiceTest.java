package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationReferenceServiceTest {

    private final AgentConversationMapper conversations = mock(AgentConversationMapper.class);
    private final AgentMessageMapper messages = mock(AgentMessageMapper.class);
    private final ConversationReferenceService service =
            new ConversationReferenceService(conversations, messages);

    private AgentConversation conversation(String userId, String projectId, String status) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("other-conversation");
        conversation.setUserId(userId);
        conversation.setProjectId(projectId);
        conversation.setTitle("分镜重做");
        conversation.setStatus(status);
        return conversation;
    }

    private AgentMessage message(String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(OffsetDateTime.now());
        return message;
    }

    @Test
    void sameUserAndProjectResolvesRecentTranscript() {
        when(conversations.selectById("other-conversation"))
                .thenReturn(conversation("user-1", "project-1", "active"));
        when(messages.selectList(any())).thenReturn(List.of(
                message("assistant", "第二版脚本"),
                message("user", "把第三镜改成推镜")));
        when(messages.selectCount(any())).thenReturn(2L);

        var view = service.resolve("user-1", "project-1", "other-conversation");

        assertThat(view.resolved()).isTrue();
        assertThat(view.title()).isEqualTo("分镜重做");
        assertThat(view.messageCount()).isEqualTo(2);
        assertThat(view.excerpt()).contains("【用户】把第三镜改成推镜");
        assertThat(view.excerpt()).contains("【助手】第二版脚本");
    }

    @Test
    void foreignUserOrProjectIsRejected() {
        when(conversations.selectById("other-conversation"))
                .thenReturn(conversation("user-2", "project-1", "active"));
        assertThat(service.resolve("user-1", "project-1", "other-conversation").reason())
                .contains("无权");

        when(conversations.selectById("other-conversation"))
                .thenReturn(conversation("user-1", "project-2", "active"));
        assertThat(service.resolve("user-1", "project-1", "other-conversation").reason())
                .contains("当前项目");
    }

    @Test
    void temporaryConversationIsRejected() {
        when(conversations.selectById("other-conversation"))
                .thenReturn(conversation("user-1", "project-1", "temporary"));

        assertThat(service.resolve("user-1", "project-1", "other-conversation").resolved())
                .isFalse();
    }
}
