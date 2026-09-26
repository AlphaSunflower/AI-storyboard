package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.dto.request.AgentTaskPlanActionRequest;
import com.moon.moonagent.dto.response.AgentTaskPlanView;
import com.moon.moonagent.entity.AgentTaskPlan;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentTaskPlanMapper;
import com.storyboard.common.ApiResponse;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentTaskPlanControllerTest {

    @Test
    void legacyPlanActionsReturnGone() {
        AgentTaskPlanController controller = controller();

        assertThatThrownBy(() -> controller.action(
                auth(), "conversation-1", "plan-1",
                new AgentTaskPlanActionRequest("approve")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(41001);
    }

    @Test
    void historicalPlansAreReadableWithoutLegacyExecution() {
        AgentChatService chatService = mock(AgentChatService.class);
        AgentTaskPlanMapper mapper = mock(AgentTaskPlanMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        AgentTaskPlan row = new AgentTaskPlan();
        row.setId("plan-1");
        row.setConversationId("conversation-1");
        row.setGoal("old goal");
        row.setStatus("CANCELLED");
        row.setVersion(1);
        row.setReplanCount(0);
        when(chatService.getOwnedConversation("user-1", "conversation-1"))
                .thenReturn(conversation);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        AgentTaskPlanController controller =
                new AgentTaskPlanController(chatService, mapper);

        ApiResponse<List<AgentTaskPlanView>> response =
                controller.list(auth(), "conversation-1");

        assertThat(response.data()).singleElement()
                .extracting(AgentTaskPlanView::state)
                .isEqualTo("CANCELLED");
    }

    private static AgentTaskPlanController controller() {
        return new AgentTaskPlanController(
                mock(AgentChatService.class), mock(AgentTaskPlanMapper.class));
    }

    private static Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user-1");
        return auth;
    }
}
