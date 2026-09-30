package com.moon.moonagent.service;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryboardQueueRequestGuardTest {

    @Mock
    private StoryboardClient storyboardClient;
    @Mock
    private AgentConversationMapper conversationMapper;

    private StoryboardQueueRequestGuard guard;
    private AgentConversation conversation;
    private Scene scene;

    @BeforeEach
    void setUp() {
        guard = new StoryboardQueueRequestGuard(storyboardClient, conversationMapper);
        conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");
        scene = new Scene();
        scene.setId("s1");
        scene.setProjectId("p1");
    }

    @Test
    void acceptsOwnedProjectConversationAndScene() {
        when(storyboardClient.getProject("p1")).thenReturn(Map.of("userId", "u1"));
        when(conversationMapper.selectById("c1")).thenReturn(conversation);
        when(storyboardClient.getProjectScenes("p1")).thenReturn(List.of(scene));

        assertDoesNotThrow(() -> guard.requireOwnedScope("u1", "p1", "c1", List.of("s1")));
    }

    @Test
    void rejectsSceneOutsideProject() {
        when(storyboardClient.getProject("p1")).thenReturn(Map.of("userId", "u1"));
        when(conversationMapper.selectById("c1")).thenReturn(conversation);
        when(storyboardClient.getProjectScenes("p1")).thenReturn(List.of(scene));

        assertThrows(BusinessException.class,
                () -> guard.requireOwnedScope("u1", "p1", "c1", List.of("victim-scene")));
    }

    @Test
    void rejectsConversationOwnedByAnotherUser() {
        conversation.setUserId("victim");
        when(storyboardClient.getProject("p1")).thenReturn(Map.of("userId", "u1"));
        when(conversationMapper.selectById("c1")).thenReturn(conversation);

        assertThrows(BusinessException.class,
                () -> guard.requireOwnedScope("u1", "p1", "c1", List.of("s1")));
    }
}
