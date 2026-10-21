package com.moon.moonagent.ai.agent.context;

import com.moon.moonagent.entity.AgentCanvasRunEntity;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.entity.AgentWorkflowRun;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentCanvasRunMapper;
import com.moon.moonagent.mapper.AgentCanvasRunOperationMapper;
import com.moon.moonagent.mapper.AgentCanvasSessionMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationAssetGroupMapper;
import com.moon.moonagent.mapper.AgentDocumentIndexMapper;
import com.moon.moonagent.mapper.AgentExternalActionMapper;
import com.moon.moonagent.mapper.AgentMediaCritiqueMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.mapper.AgentTimelineManifestMapper;
import com.moon.moonagent.mapper.AgentTurnEventMapper;
import com.moon.moonagent.mapper.AgentTurnMapper;
import com.moon.moonagent.mapper.AgentWorkflowRunMapper;
import com.moon.moonagent.mapper.AgentWorkflowStepMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「/清空上下文」真删除的执行器：断言每张会话级表都被 DELETE 到，
 * 且删除顺序满足外键方向（子先父后）。
 */
@ExtendWith(MockitoExtension.class)
class ConversationContextCleanupServiceTest {

    @Mock private AgentMessageMapper messageMapper;
    @Mock private AgentCheckpointMapper checkpointMapper;
    @Mock private AgentTurnEventMapper turnEventMapper;
    @Mock private AgentTurnMapper turnMapper;
    @Mock private AgentCanvasRunOperationMapper canvasRunOperationMapper;
    @Mock private AgentCanvasRunMapper canvasRunMapper;
    @Mock private AgentCanvasSessionMapper canvasSessionMapper;
    @Mock private AgentConversationAssetGroupMapper assetGroupMapper;
    @Mock private AgentDocumentIndexMapper documentIndexMapper;
    @Mock private AgentAsyncTaskMapper asyncTaskMapper;
    @Mock private AgentTimelineManifestMapper timelineManifestMapper;
    @Mock private AgentMediaCritiqueMapper mediaCritiqueMapper;
    @Mock private AgentWorkflowRunMapper workflowRunMapper;
    @Mock private AgentWorkflowStepMapper workflowStepMapper;
    @Mock private AgentExternalActionMapper externalActionMapper;
    @Mock private AgentConversationMapper conversationMapper;

    @InjectMocks
    private ConversationContextCleanupService service;

    /**
     * Pure-Mockito tests still evaluate the {@code LambdaQueryWrapper}s, so the MyBatis-Plus
     * lambda column caches have to exist (in production MyBatis builds them at startup).
     */
    @org.junit.jupiter.api.BeforeAll
    static void installLambdaCaches() {
        com.moon.moonagent.support.MyBatisPlusLambdaCache.installContextMappers();
    }

    @Test
    void deletesEveryConversationScopedTableAndReportsRowCounts() {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        AgentCanvasRunEntity run = new AgentCanvasRunEntity();
        run.setId("run-1");
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(canvasRunMapper.selectList(any())).thenReturn(List.of(run));

        // 每张表都返回 2 行，便于区分「删了」和「没删」
        when(messageMapper.delete(any())).thenReturn(2);
        when(checkpointMapper.delete(any())).thenReturn(2);
        when(turnEventMapper.delete(any())).thenReturn(2);
        when(turnMapper.delete(any())).thenReturn(2);
        when(canvasRunOperationMapper.delete(any())).thenReturn(2);
        when(canvasRunMapper.delete(any())).thenReturn(2);
        when(canvasSessionMapper.delete(any())).thenReturn(2);
        when(assetGroupMapper.delete(any())).thenReturn(2);
        when(documentIndexMapper.delete(any())).thenReturn(2);
        when(asyncTaskMapper.delete(any())).thenReturn(2);
        when(timelineManifestMapper.delete(any())).thenReturn(2);
        when(mediaCritiqueMapper.delete(any())).thenReturn(2);
        when(workflowRunMapper.delete(any())).thenReturn(2);
        when(externalActionMapper.delete(any())).thenReturn(2);

        var report = service.deleteConversationContext("conversation-1");

        assertThat(report.messages()).isEqualTo(2);
        assertThat(report.checkpoints()).isEqualTo(2);
        assertThat(report.turns()).isEqualTo(2);
        assertThat(report.turnEvents()).isEqualTo(2);
        assertThat(report.canvasSessions()).isEqualTo(2);
        assertThat(report.canvasRuns()).isEqualTo(2);
        assertThat(report.canvasOperations()).isEqualTo(2);
        assertThat(report.assetGroups()).isEqualTo(2);
        assertThat(report.documentIndexes()).isEqualTo(2);
        assertThat(report.asyncTasks()).isEqualTo(2);
        assertThat(report.timelineManifests()).isEqualTo(2);
        assertThat(report.mediaCritiques()).isEqualTo(2);
        assertThat(report.workflowRuns()).isEqualTo(2);
        assertThat(report.externalActions()).isEqualTo(2);
        assertThat(report.total()).isEqualTo(28);
    }

    @Test
    void deletesChildrenBeforeParentsSoForeignKeysAreSatisfied() {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        AgentCanvasRunEntity run = new AgentCanvasRunEntity();
        run.setId("run-1");
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(canvasRunMapper.selectList(any())).thenReturn(List.of(run));

        service.deleteConversationContext("conversation-1");

        InOrder ordered = inOrder(turnEventMapper, turnMapper,
                canvasRunOperationMapper, canvasRunMapper, canvasSessionMapper);
        ordered.verify(turnEventMapper).delete(any());       // 事件先于轮次
        ordered.verify(turnMapper).delete(any());
        ordered.verify(canvasRunOperationMapper).delete(any()); // 操作先于运行
        ordered.verify(canvasRunMapper).delete(any());
        ordered.verify(canvasSessionMapper).delete(any());   // 会话在运行之后
    }

    @Test
    void archivedCleanupDeletesRuntimeTablesAndKeepsProductData() {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setId("turn-1");
        AgentCanvasRunEntity run = new AgentCanvasRunEntity();
        run.setId("run-1");
        AgentWorkflowRun workflowRun = new AgentWorkflowRun();
        workflowRun.setId("workflow-1");
        when(turnMapper.selectList(any())).thenReturn(List.of(turn));
        when(canvasRunMapper.selectList(any())).thenReturn(List.of(run));
        when(workflowRunMapper.selectList(any())).thenReturn(List.of(workflowRun));

        when(turnEventMapper.delete(any())).thenReturn(3);
        when(checkpointMapper.delete(any())).thenReturn(3);
        when(canvasRunOperationMapper.delete(any())).thenReturn(3);
        when(canvasRunMapper.delete(any())).thenReturn(3);
        when(canvasSessionMapper.delete(any())).thenReturn(3);
        when(documentIndexMapper.delete(any())).thenReturn(3);
        when(asyncTaskMapper.delete(any())).thenReturn(3);
        when(workflowStepMapper.delete(any())).thenReturn(3);
        when(workflowRunMapper.delete(any())).thenReturn(3);

        var report = service.cleanupArchivedRuntime("conversation-1");

        // 9 张运行时表 × 3 行
        assertThat(report.total()).isEqualTo(27);
        // 产品数据与 MinIO 归属行不在归档清理范围：消息、轮次行、资产组、时间线、评审、外部动作
        verify(messageMapper, never()).delete(any());
        verify(turnMapper, never()).delete(any());
        verify(assetGroupMapper, never()).delete(any());
        verify(timelineManifestMapper, never()).delete(any());
        verify(mediaCritiqueMapper, never()).delete(any());
        verify(externalActionMapper, never()).delete(any());
        // 运行时状态已删，初始化标记与画布绑定必须置空（否则恢复归档后带着空上下文继续）
        verify(conversationMapper).update(any(), any());
    }

    @Test
    void skipsChildDeleteWhenThereIsNothingToCascadeFrom() {
        when(turnMapper.selectList(any())).thenReturn(List.of());
        when(canvasRunMapper.selectList(any())).thenReturn(List.of());

        var report = service.deleteConversationContext("conversation-1");

        // 没有轮次/画布运行时不去发一条 `IN ()` 的空查询：直接跳过子表删除
        verify(turnEventMapper, never()).delete(any());
        verify(canvasRunOperationMapper, never()).delete(any());
        assertThat(report.turnEvents()).isZero();
        assertThat(report.canvasOperations()).isZero();
        assertThat(report.total()).isZero();
    }
}
