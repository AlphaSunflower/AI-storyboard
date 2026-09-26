package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.ai.agent.task.AgentTaskView;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskControllerTest {

    @Test
    void listUsesAuthenticatedOwnerAndReturnsPagedTasks() {
        AgentScopeAsyncTaskService service = mock(AgentScopeAsyncTaskService.class);
        AgentTaskEventHub events = mock(AgentTaskEventHub.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentScopeAsyncTaskService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        AgentTaskController controller = new AgentTaskController(provider, events, mock(AgentChatService.class));
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user-1");
        when(service.listTasks("user-1", "conversation-1", null, "active", 1, 20))
                .thenReturn(Map.of("records", List.of(task()), "total", 1L));

        var response = controller.list(auth, "conversation-1", null, "active", 1, 20);

        assertThat(response.data()).containsEntry("total", 1L);
        verify(service).listTasks("user-1", "conversation-1", null, "active", 1, 20);
    }

    @Test
    void detailRejectsMissingOrUnownedTask() {
        AgentScopeAsyncTaskService service = mock(AgentScopeAsyncTaskService.class);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.getVideoTaskStatus("user-1", "missing"))
                .thenThrow(new BusinessException(40401, "任务不存在"));
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentScopeAsyncTaskService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        AgentTaskController controller = new AgentTaskController(
                provider, mock(AgentTaskEventHub.class), chatService);
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user-1");
        when(service.getStatus("user-1", "missing")).thenReturn(Map.of());

        assertThatThrownBy(() -> controller.detail(auth, "missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");
    }

    private static AgentTaskView task() {
        return new AgentTaskView(
                "task-1", "conversation-1", "project-1", "剧情诊断",
                "start_storyboard_production", "long-tool", "running",
                "plot_diagnosis", 20, "", "", "", "message-1", 2L,
                OffsetDateTime.now(), OffsetDateTime.now(), null,
                List.of(), List.of(), List.of(), "", true, false, true);
    }
}
