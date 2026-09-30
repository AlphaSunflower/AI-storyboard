package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.service.MorrowCanvasService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeCanvasToolsTest {

    @Test
    void createsCanvasWhenProjectHasNone() {
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        when(canvasService.listByProject("user-1", "project-1")).thenReturn(List.of());
        MorrowCanvas created = canvas("canvas-new", "雪茄房转场");
        when(canvasService.create("user-1", "project-1", "雪茄房转场")).thenReturn(created);
        AgentScopeCanvasTools tools = new AgentScopeCanvasTools(canvasService);

        AgentScopeCanvasTools.SessionPreparation preparation = tools.prepare(
                conversation(), Map.of("intent", "生成雪茄房转场", "canvas_title", "雪茄房转场"));

        assertThat(preparation.canvasId()).isEqualTo("canvas-new");
        assertThat(preparation.projectId()).isEqualTo("project-1");
        assertThat(preparation.created()).isTrue();
        verify(canvasService).create("user-1", "project-1", "雪茄房转场");
    }

    @Test
    void reusesMostRecentlyUpdatedCanvasWithoutCreatingAnother() {
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        when(canvasService.listByProject("user-1", "project-1"))
                .thenReturn(List.of(canvas("canvas-recent", "画布 9")));
        AgentScopeCanvasTools tools = new AgentScopeCanvasTools(canvasService);

        AgentScopeCanvasTools.SessionPreparation preparation = tools.prepare(
                conversation(), Map.of("intent", "继续设计"));

        assertThat(preparation.canvasId()).isEqualTo("canvas-recent");
        assertThat(preparation.created()).isFalse();
        verify(canvasService, never()).create(any(), any(), any());
    }

    @Test
    void opensExplicitlySelectedCanvasWhenProjectHasMultiple() {
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        when(canvasService.listByProject("user-1", "project-1"))
                .thenReturn(List.of(
                        canvas("canvas-recent", "画布 9"),
                        canvas("canvas-selected", "重生之我在搞建筑")));
        AgentScopeCanvasTools tools = new AgentScopeCanvasTools(canvasService);

        AgentScopeCanvasTools.SessionPreparation preparation = tools.prepare(
                conversation(), Map.of("intent", "继续设计", "canvas_id", "canvas-selected"));

        assertThat(preparation.canvasId()).isEqualTo("canvas-selected");
        assertThat(preparation.canvasTitle()).isEqualTo("重生之我在搞建筑");
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        return conversation;
    }

    private static MorrowCanvas canvas(String id, String title) {
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId(id);
        canvas.setProjectId("project-1");
        canvas.setTitle(title);
        return canvas;
    }
}
