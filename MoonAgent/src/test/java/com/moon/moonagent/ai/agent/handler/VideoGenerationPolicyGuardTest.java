package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VideoGenerationPolicyGuardTest {

    @Test
    void genericVideoEntryRejectsLongOriginalRequestBeforeCreatingTask() {
        AgentGenerationService generation = mock(AgentGenerationService.class);
        AgentRuntimeSupport support = support(generation);
        AgentConversation conversation = conversation();
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "生成一个两分钟的视频", List.of(), mock(SseEmitter.class));

        assertThatThrownBy(() -> support.startVideoGenerationAsync(
                request, "别墅漫游", "8", "16:9", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("15 秒")
                .hasMessageContaining("分镜");

        verifyNoInteractions(generation);
    }

    @Test
    void storyboardShotEntryAllowsLongOriginalRequestWhenShotIsWithinFifteenSeconds() {
        AgentGenerationService generation = mock(AgentGenerationService.class);
        when(generation.createVideoTaskWithRole(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("created-past-policy"));
        AgentRuntimeSupport support = support(generation);
        AgentConversation conversation = conversation();
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "生成一个两分钟的视频", List.of(), mock(SseEmitter.class));

        assertThatThrownBy(() -> support.startStoryboardShotVideoGenerationAsyncWithRole(
                request, "scene-1", "首个镜头", "15", "16:9",
                List.of(), List.of(), Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("created-past-policy");

        verify(generation).createVideoTaskWithRole(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean());
        verify(generation, never()).pollVideoTask(any());
    }

    private static AgentRuntimeSupport support(AgentGenerationService generation) {
        AgentSessionState sessionState = mock(AgentSessionState.class);
        when(sessionState.isInterrupted(any())).thenReturn(false);
        return new AgentRuntimeSupport(
                null, null, new AgentAiConfigProperties(), generation, null, null, null,
                null, null, null, sessionState, null);
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        return conversation;
    }
}
