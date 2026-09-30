package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentCanvasRunEntity;
import com.moon.moonagent.entity.AgentCanvasRunOperationEntity;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentCanvasRunMapper;
import com.moon.moonagent.mapper.AgentCanvasRunOperationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.MorrowCanvasService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCanvasRunServiceTest {

    @Test
    void createPersistsRunAndCanvasMessage() {
        AgentCanvasRunMapper runMapper = mock(AgentCanvasRunMapper.class);
        doAnswer(invocation -> {
            AgentCanvasRunEntity entity = invocation.getArgument(0);
            entity.setId("run-1");
            return 1;
        }).when(runMapper).insert(any(AgentCanvasRunEntity.class));
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        doAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            message.setId("message-1");
            return 1;
        }).when(messageMapper).insert(any(AgentMessage.class));
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setTitle("雪茄房转场");
        when(canvasService.create("user-1", "project-1", "雪茄房转场"))
                .thenReturn(canvas);
        AgentCanvasRunService service = new AgentCanvasRunService(
                runMapper, mock(AgentCanvasRunOperationMapper.class),
                canvasService, messageMapper);

        AgentCanvasRunService.CanvasRunView run = service.create(
                conversation(), "turn-1", "call-1", "雪茄房转场",
                "创建画布链路", List.of(Map.of("step", "生成雪茄房")));

        assertThat(run.runId()).isEqualTo("run-1");
        assertThat(run.canvasId()).isEqualTo("canvas-1");
        assertThat(run.status()).isEqualTo(AgentCanvasRunService.RUNNING);
        var captor = forClass(AgentMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry("kind", "canvas-run")
                .containsEntry("canvasRunId", "run-1")
                .containsEntry("canvasId", "canvas-1");
    }

    @Test
    void createIsIdempotentForSameToolCall() {
        AgentCanvasRunMapper runMapper = mock(AgentCanvasRunMapper.class);
        AgentCanvasRunEntity existing = new AgentCanvasRunEntity();
        existing.setId("run-1");
        existing.setConversationId("conversation-1");
        existing.setUserId("user-1");
        existing.setProjectId("project-1");
        existing.setTurnId("turn-1");
        existing.setToolCallId("call-1");
        existing.setCanvasId("canvas-1");
        existing.setTitle("雪茄房转场");
        existing.setStatus(AgentCanvasRunService.RUNNING);
        existing.setProgress(0);
        existing.setPlanJson("[]");
        when(runMapper.selectOne(any())).thenReturn(existing);
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        AgentCanvasRunService service = new AgentCanvasRunService(
                runMapper, mock(AgentCanvasRunOperationMapper.class),
                canvasService, mock(AgentMessageMapper.class));

        AgentCanvasRunService.CanvasRunView run = service.create(
                conversation(), "turn-1", "call-1", "雪茄房转场",
                "创建画布链路", List.of());

        assertThat(run.runId()).isEqualTo("run-1");
        verify(canvasService, never()).create(any(), any(), any());
    }

    @Test
    void updateFromGraphResultPersistsProgressAndMessageMetadata() {
        AgentCanvasRunMapper runMapper = mock(AgentCanvasRunMapper.class);
        AgentCanvasRunEntity run = new AgentCanvasRunEntity();
        run.setId("run-1");
        run.setConversationId("conversation-1");
        run.setUserId("user-1");
        run.setProjectId("project-1");
        run.setTurnId("turn-1");
        run.setToolCallId("call-1");
        run.setCanvasId("canvas-1");
        run.setTitle("雪茄房转场");
        run.setStatus(AgentCanvasRunService.RUNNING);
        run.setProgress(0);
        run.setMessageId("message-1");
        run.setPlanJson("[]");
        when(runMapper.selectById("run-1")).thenReturn(run);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMessage message = new AgentMessage();
        message.setId("message-1");
        message.setMetadata(new java.util.LinkedHashMap<>(Map.of(
                "kind", "canvas-run",
                "canvasRunId", "run-1",
                "status", "running")));
        when(messageMapper.selectById("message-1")).thenReturn(message);
        AgentCanvasRunService service = new AgentCanvasRunService(
                runMapper, mock(AgentCanvasRunOperationMapper.class),
                mock(MorrowCanvasService.class), messageMapper);

        AgentCanvasRunService.CanvasRunView updated = service.updateFromGraphResult(
                "run-1",
                Map.of(
                        "ok", true,
                        "revision", 4,
                        "nodeCount", 3,
                        "edgeCount", 2,
                        "progress", 65));

        assertThat(updated.progress()).isEqualTo(65);
        assertThat(run.getStatus()).isEqualTo(AgentCanvasRunService.RUNNING);
        assertThat(message.getMetadata())
                .containsEntry("revision", 4)
                .containsEntry("nodeCount", 3)
                .containsEntry("edgeCount", 2)
                .containsEntry("progress", 65);
        verify(runMapper).updateById(run);
        verify(messageMapper).updateById(message);
    }

    @Test
    void createCanAttachToExistingCanvasContext() {
        AgentCanvasRunMapper runMapper = mock(AgentCanvasRunMapper.class);
        doAnswer(invocation -> {
            AgentCanvasRunEntity entity = invocation.getArgument(0);
            entity.setId("run-1");
            return 1;
        }).when(runMapper).insert(any(AgentCanvasRunEntity.class));
        MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-existing");
        canvas.setTitle("已有画布");
        when(canvasService.get("user-1", "canvas-existing")).thenReturn(canvas);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        doAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            message.setId("message-1");
            return 1;
        }).when(messageMapper).insert(any(AgentMessage.class));
        AgentCanvasRunService service = new AgentCanvasRunService(
                runMapper, mock(AgentCanvasRunOperationMapper.class),
                canvasService, messageMapper);

        AgentCanvasRunService.CanvasRunView run = service.createExisting(
                conversation(), "turn-1", "call-1", "canvas-existing",
                "继续编辑", "补充节点", List.of());

        assertThat(run.canvasId()).isEqualTo("canvas-existing");
        verify(canvasService, never()).create(any(), any(), any());
    }

    @Test
    void rememberOperationReturnsPreviousResultForDuplicateToolCall() {
        AgentCanvasRunOperationMapper operationMapper = mock(AgentCanvasRunOperationMapper.class);
        AgentCanvasRunOperationEntity existing = new AgentCanvasRunOperationEntity();
        existing.setRunId("run-1");
        existing.setOperationKey("call-1");
        existing.setResultJson("{\"ok\":true,\"nodeId\":\"node-1\"}");
        when(operationMapper.selectOne(any())).thenReturn(existing);
        AgentCanvasRunService service = new AgentCanvasRunService(
                mock(AgentCanvasRunMapper.class), operationMapper,
                mock(MorrowCanvasService.class), mock(AgentMessageMapper.class));

        Map<String, Object> result = service.rememberOperation(
                "run-1", "call-1", Map.of("ok", true, "nodeId", "new-node"));

        assertThat(result).containsEntry("nodeId", "node-1");
        verify(operationMapper, never()).insert(any(AgentCanvasRunOperationEntity.class));
    }

    @Test
    void operationLookupCanReadWithoutWriting() {
        AgentCanvasRunOperationMapper operationMapper = mock(AgentCanvasRunOperationMapper.class);
        AgentCanvasRunOperationEntity existing = new AgentCanvasRunOperationEntity();
        existing.setRunId("run-1");
        existing.setOperationKey("call-1");
        existing.setResultJson("{\"ok\":true,\"edgeId\":\"edge-1\"}");
        when(operationMapper.selectOne(any())).thenReturn(existing);
        AgentCanvasRunService service = new AgentCanvasRunService(
                mock(AgentCanvasRunMapper.class), operationMapper,
                mock(MorrowCanvasService.class), mock(AgentMessageMapper.class));

        Map<String, Object> result = service.findOperationResult("run-1", "call-1");

        assertThat(result).containsEntry("edgeId", "edge-1");
        verify(operationMapper, never()).insert(any(AgentCanvasRunOperationEntity.class));
    }

    @Test
    void completeMarksRunAndMessageCompleted() {
        AgentCanvasRunMapper runMapper = mock(AgentCanvasRunMapper.class);
        AgentCanvasRunEntity run = new AgentCanvasRunEntity();
        run.setId("run-1");
        run.setConversationId("conversation-1");
        run.setUserId("user-1");
        run.setProjectId("project-1");
        run.setTurnId("turn-1");
        run.setCanvasId("canvas-1");
        run.setTitle("雪茄房转场");
        run.setStatus(AgentCanvasRunService.RUNNING);
        run.setProgress(80);
        run.setMessageId("message-1");
        run.setPlanJson("[]");
        when(runMapper.selectById("run-1")).thenReturn(run);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMessage message = new AgentMessage();
        message.setId("message-1");
        message.setMetadata(new java.util.LinkedHashMap<>(Map.of(
                "kind", "canvas-run",
                "canvasRunId", "run-1",
                "status", "running")));
        when(messageMapper.selectById("message-1")).thenReturn(message);
        AgentCanvasRunService service = new AgentCanvasRunService(
                runMapper, mock(AgentCanvasRunOperationMapper.class),
                mock(MorrowCanvasService.class), messageMapper);

        AgentCanvasRunService.CanvasRunView completed = service.complete(
                "run-1", Map.of("nodeCount", 5, "edgeCount", 4, "revision", 7));

        assertThat(completed.status()).isEqualTo(AgentCanvasRunService.COMPLETED);
        assertThat(completed.progress()).isEqualTo(100);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(message.getMetadata())
                .containsEntry("status", "completed")
                .containsEntry("progress", 100)
                .containsEntry("nodeCount", 5)
                .containsEntry("edgeCount", 4)
                .containsEntry("revision", 7);
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        return conversation;
    }
}
