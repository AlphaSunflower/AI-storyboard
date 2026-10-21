package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.entity.CanvasExecution;
import com.moon.xiaoyuecanvas.entity.CanvasExecutionNode;
import com.moon.xiaoyuecanvas.mapper.CanvasExecutionMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasExecutionNodeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务端执行批次的串行语义：成功 → 继续；失败 → 停止并把剩余节点标 blocked；取消 → 整批终止。
 */
class CanvasExecutionServiceTest {

    private final CanvasExecutionMapper executionMapper = mock(CanvasExecutionMapper.class);
    private final CanvasExecutionNodeMapper nodeMapper = mock(CanvasExecutionNodeMapper.class);
    private final DesignJobService designJobService = mock(DesignJobService.class);
    private final MorrowCanvasService canvasService = mock(MorrowCanvasService.class);
    private final CanvasAssetService canvasAssetService = mock(CanvasAssetService.class);
    private final CanvasGraphService canvasGraphService = mock(CanvasGraphService.class);
    private final CanvasExecutionService service =
            new CanvasExecutionService(executionMapper, nodeMapper, designJobService, canvasService,
                    canvasAssetService, canvasGraphService);

    private static CanvasExecution execution(String status) {
        CanvasExecution execution = new CanvasExecution();
        execution.setId("execution-1");
        execution.setCanvasId("canvas-1");
        execution.setUserId("user-1");
        execution.setStatus(status);
        execution.setMaxParallel(1);
        return execution;
    }

    private static CanvasExecutionNode step(String nodeId, int position) {
        CanvasExecutionNode node = new CanvasExecutionNode();
        node.setExecutionId("execution-1");
        node.setNodeId(nodeId);
        node.setPosition(position);
        node.setActionId("generate");
        node.setCapabilityId("image.generate");
        node.setClientRequestId("req-" + nodeId);
        node.setStatus("pending");
        return node;
    }

    @Test
    void runsStepsInOrderAndStopsOnFailure() {
        CanvasExecution execution = execution("running");
        List<CanvasExecutionNode> nodes = List.of(step("n1", 0), step("n2", 1), step("n3", 2));
        when(executionMapper.selectById("execution-1")).thenReturn(execution);
        when(nodeMapper.selectList(any())).thenReturn(nodes);
        when(designJobService.submitAndWait(any(), any(), any()))
                .thenReturn(new DesignJobService.ExecutionOutcome("job-1", "completed", List.of("asset-1"), null))
                .thenReturn(new DesignJobService.ExecutionOutcome("job-2", "failed", List.of(), "内容审核不通过"))
                .thenReturn(new DesignJobService.ExecutionOutcome("job-2-r", "failed", List.of(), "内容审核不通过"));

        service.runSteps(execution, "project-1");

        assertEquals("succeeded", nodes.get(0).getStatus());
        assertEquals(List.of("asset-1"), com.moon.xiaoyuecanvas.util.JsonUtil.parseList(nodes.get(0).getResultAssetIds()));
        assertEquals("failed", nodes.get(1).getStatus());
        assertEquals("内容审核不通过", nodes.get(1).getError());
        // 失败之后的节点不再提交作业，直接 blocked
        assertEquals("blocked", nodes.get(2).getStatus());
        assertEquals("failed", execution.getStatus());
        // n1 一次 + n2 首次失败后重试一次
        verify(designJobService, times(3)).submitAndWait(any(), any(), any());
    }

    @Test
    void retriesOnceWithFreshIdempotencyKeyAndSucceeds() {
        CanvasExecution execution = execution("running");
        List<CanvasExecutionNode> nodes = List.of(step("n1", 0));
        when(executionMapper.selectById("execution-1")).thenReturn(execution);
        when(nodeMapper.selectList(any())).thenReturn(nodes);
        when(designJobService.submitAndWait(any(), any(), any()))
                .thenReturn(new DesignJobService.ExecutionOutcome("job-1", "failed", List.of(), "上游超时"))
                .thenReturn(new DesignJobService.ExecutionOutcome("job-1-r", "completed", List.of("asset-9"), null));

        service.runSteps(execution, "project-1");

        assertEquals("succeeded", nodes.get(0).getStatus());
        assertEquals("job-1-r", nodes.get(0).getJobId());
        var requests = org.mockito.ArgumentCaptor.forClass(
                com.moon.xiaoyuecanvas.dto.request.JobRequest.class);
        verify(designJobService, times(2)).submitAndWait(any(), requests.capture(), any());
        assertEquals("req-n1", requests.getAllValues().get(0).clientRequestId());
        assertEquals("req-n1-r1", requests.getAllValues().get(1).clientRequestId());
    }

    @Test
    void cancelledBatchMarksRemainingNodesCancelledWithoutSubmitting() {
        CanvasExecution execution = execution("cancelled");
        List<CanvasExecutionNode> nodes = List.of(step("n1", 0), step("n2", 1));
        when(executionMapper.selectById("execution-1")).thenReturn(execution);
        when(nodeMapper.selectList(any())).thenReturn(nodes);

        service.runSteps(execution, "project-1");

        assertEquals("cancelled", nodes.get(0).getStatus());
        assertEquals("cancelled", nodes.get(1).getStatus());
        verify(designJobService, never()).submitAndWait(any(), any(), any());
    }

    @Test
    void runningNodeIsCancelledWhenBatchIsCancelled() {
        CanvasExecution execution = execution("running");
        CanvasExecutionNode running = step("n1", 0);
        running.setStatus("running");
        running.setJobId("job-1");
        when(executionMapper.selectById("execution-1")).thenReturn(execution).thenReturn(execution);
        when(nodeMapper.selectList(any())).thenReturn(List.of(running));

        service.cancel("user-1", "canvas-1", "execution-1");

        assertEquals("cancelled", execution.getStatus());
        verify(designJobService).cancelDetached("job-1");
        assertNotNull(execution.getError());
        assertTrue(execution.getError().contains("取消"));
    }

    @Test
    void createRejectsEmptyPlan() {
        var request = new com.moon.xiaoyuecanvas.dto.request.CanvasExecutionCreateRequest(1, List.of());
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                com.storyboard.common.BusinessException.class,
                () -> service.create("user-1", "canvas-1", request));
        assertTrue(error.getMessage().contains("至少"));
    }
}
