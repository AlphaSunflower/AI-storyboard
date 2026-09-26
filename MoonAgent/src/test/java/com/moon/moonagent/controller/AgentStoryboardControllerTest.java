package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStoryboardControllerTest {

    @Test
    void saveDraftUsesOwnedConversationProjectAndExpectedVersion() {
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentScopeAsyncTaskService> tasks = mock(ObjectProvider.class);
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentStoryboardController controller =
                new AgentStoryboardController(production, tasks, conversations);
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user-1");
        StoryboardRunSnapshot current = new StoryboardRunSnapshot(
                "run-1", "conversation-1", Stage.PSEUDO_REVIEW, 7,
                "", "", "", null, null, List.of(), List.of(), List.of(), List.of(), "");
        when(production.getOwned("run-1", "user-1", null)).thenReturn(current);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        when(conversations.selectById("conversation-1")).thenReturn(conversation);
        when(production.replaceDraft(
                "run-1", "user-1", "project-1", 7, List.of(), List.of()))
                .thenReturn(current);

        var response = controller.saveDraft(
                auth, "run-1", new AgentStoryboardController.DraftRequest(
                        7, List.of(), List.of()));

        assertThat(response.data()).isSameAs(current);
        verify(production).replaceDraft(
                "run-1", "user-1", "project-1", 7, List.of(), List.of());
    }
}
