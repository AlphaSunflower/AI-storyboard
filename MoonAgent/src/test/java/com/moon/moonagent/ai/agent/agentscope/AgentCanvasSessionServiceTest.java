package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentCanvasSessionEntity;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentCanvasSessionMapper;
import com.moon.moonagent.service.MorrowCanvasService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

class AgentCanvasSessionServiceTest {

    @Test
    void multipleCanvasesCreateSelectionSession() {
        AgentCanvasSessionMapper mapper = mock(AgentCanvasSessionMapper.class);
        doAnswer(invocation -> {
            AgentCanvasSessionEntity entity = invocation.getArgument(0);
            entity.setId("session-1");
            return 1;
        }).when(mapper).insert(any(AgentCanvasSessionEntity.class));
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        AgentCanvasSessionService service =
                new AgentCanvasSessionService(mapper, canvasService);
        AgentScopeCanvasTools.SessionPreparation preparation =
                new AgentScopeCanvasTools.SessionPreparation(
                        "project-1", "", "", "设计画布", 2, false,
                        List.of(
                                new AgentScopeCanvasTools.CanvasChoice("canvas-1", "画布 1"),
                                new AgentScopeCanvasTools.CanvasChoice("canvas-2", "画布 2")));

        AgentCanvasSessionService.CanvasSessionView session = service.create(
                conversation(), "turn-1", "reply-1", "call-1", preparation, List.of());

        assertThat(session.status()).isEqualTo(AgentCanvasSessionService.PENDING_SELECTION);
        assertThat(session.choices()).hasSize(2);
    }

    @Test
    void blankCanvasIdIsStoredAsNullForPendingSelection() {
        AgentCanvasSessionMapper mapper = mock(AgentCanvasSessionMapper.class);
        doAnswer(invocation -> {
            AgentCanvasSessionEntity entity = invocation.getArgument(0);
            entity.setId("session-1");
            return 1;
        }).when(mapper).insert(any(AgentCanvasSessionEntity.class));
        AgentCanvasSessionService service =
                new AgentCanvasSessionService(mapper, mock(MorrowCanvasService.class));
        AgentScopeCanvasTools.SessionPreparation preparation =
                new AgentScopeCanvasTools.SessionPreparation(
                        "project-1", "", "", "设计画布", 2, false,
                        List.of(new AgentScopeCanvasTools.CanvasChoice("canvas-1", "画布 1")));

        service.create(conversation(), "turn-1", "reply-1", "call-1", preparation, List.of());

        var captor = forClass(AgentCanvasSessionEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCanvasId()).isNull();
    }

    @Test
    void readyBindsSelectedCanvas() {
        AgentCanvasSessionMapper mapper = mock(AgentCanvasSessionMapper.class);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        AgentCanvasSessionEntity entity = new AgentCanvasSessionEntity();
        entity.setId("session-1");
        entity.setUserId("user-1");
        entity.setConversationId("conversation-1");
        entity.setProjectId("project-1");
        entity.setStatus(AgentCanvasSessionService.PENDING_SELECTION);
        entity.setChoicesJson("[{\"canvasId\":\"canvas-1\",\"title\":\"画布 1\"}]");
        entity.setMediaRefsJson("[]");
        when(mapper.selectById("session-1")).thenReturn(entity);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setTitle("画布 1");
        when(canvasService.listByProject("user-1", "project-1")).thenReturn(List.of(canvas));
        AgentCanvasSessionService service =
                new AgentCanvasSessionService(mapper, canvasService);

        AgentCanvasSessionService.CanvasSessionView session = service.ready(
                "user-1", "conversation-1", "session-1", "canvas-1", false);

        assertThat(session.status()).isEqualTo(AgentCanvasSessionService.READY);
        assertThat(session.canvasId()).isEqualTo("canvas-1");
    }

    @Test
    void readyIsIdempotentAfterIframeRegistration() {
        AgentCanvasSessionMapper mapper = mock(AgentCanvasSessionMapper.class);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        AgentCanvasSessionEntity entity = new AgentCanvasSessionEntity();
        entity.setId("session-1");
        entity.setUserId("user-1");
        entity.setConversationId("conversation-1");
        entity.setProjectId("project-1");
        entity.setCanvasId("canvas-1");
        entity.setCanvasTitle("画布 1");
        entity.setStatus(AgentCanvasSessionService.READY);
        entity.setChoicesJson("[]");
        entity.setMediaRefsJson("[]");
        when(mapper.selectById("session-1")).thenReturn(entity);
        AgentCanvasSessionService service =
                new AgentCanvasSessionService(mapper, canvasService);

        AgentCanvasSessionService.CanvasSessionView session = service.ready(
                "user-1", "conversation-1", "session-1", "", true);

        assertThat(session.status()).isEqualTo(AgentCanvasSessionService.READY);
        assertThat(session.canvasId()).isEqualTo("canvas-1");
        verify(canvasService, never()).create(any(), any(), any());
    }

    @Test
    void retryableFailureKeepsSessionReadyForResume() {
        AgentCanvasSessionMapper mapper = mock(AgentCanvasSessionMapper.class);
        AgentCanvasSessionEntity entity = new AgentCanvasSessionEntity();
        entity.setId("session-1");
        entity.setStatus(AgentCanvasSessionService.READY);
        when(mapper.selectById("session-1")).thenReturn(entity);
        AgentCanvasSessionService service =
                new AgentCanvasSessionService(mapper, mock(MorrowCanvasService.class));

        service.markRetryable("session-1", "模型暂时不可用");

        assertThat(entity.getStatus()).isEqualTo(AgentCanvasSessionService.READY);
        assertThat(entity.getError()).isEqualTo("模型暂时不可用");
        verify(mapper).updateById(entity);
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        return conversation;
    }
}
