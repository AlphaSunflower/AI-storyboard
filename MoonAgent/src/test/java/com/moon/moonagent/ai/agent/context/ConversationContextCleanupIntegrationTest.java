package com.moon.moonagent.ai.agent.context;

import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentCanvasRunEntity;
import com.moon.moonagent.entity.AgentCanvasRunOperationEntity;
import com.moon.moonagent.entity.AgentCanvasSessionEntity;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversationAssetGroup;
import com.moon.moonagent.entity.AgentDocumentIndex;
import com.moon.moonagent.entity.AgentExternalAction;
import com.moon.moonagent.entity.AgentMediaCritiqueEntity;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.AgentTimelineManifestEntity;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.entity.AgentTurnEventEntity;
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
import com.moon.moonagent.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「/清空上下文」的端到端证据：在真实 PostgreSQL 上写入一整套会话上下文，
 * 调用清理服务后断言**每一张表都不再有该会话的行**——证明是 DELETE（真删除）
 * 而不是把状态改成 deleted/expired 的软删除。
 *
 * <p>同时断言产出素材（{@code agent_assets}）与另一个会话的行完好无损。
 */
class ConversationContextCleanupIntegrationTest extends PostgresContainerTest {

    private static final String CONVERSATION_ID = "cleanup-conversation";
    private static final String OTHER_CONVERSATION_ID = "other-conversation";

    @Autowired private ConversationContextCleanupService cleanupService;
    @Autowired private JdbcTemplate jdbc;

    @Autowired private AgentMessageMapper messageMapper;
    @Autowired private AgentCheckpointMapper checkpointMapper;
    @Autowired private AgentTurnMapper turnMapper;
    @Autowired private AgentTurnEventMapper turnEventMapper;
    @Autowired private AgentCanvasSessionMapper canvasSessionMapper;
    @Autowired private AgentCanvasRunMapper canvasRunMapper;
    @Autowired private AgentCanvasRunOperationMapper canvasRunOperationMapper;
    @Autowired private AgentConversationAssetGroupMapper assetGroupMapper;
    @Autowired private AgentDocumentIndexMapper documentIndexMapper;
    @Autowired private AgentAsyncTaskMapper asyncTaskMapper;
    @Autowired private AgentTimelineManifestMapper timelineManifestMapper;
    @Autowired private AgentMediaCritiqueMapper mediaCritiqueMapper;
    @Autowired private AgentWorkflowRunMapper workflowRunMapper;
    @Autowired private AgentExternalActionMapper externalActionMapper;

    /** 共享容器：用例之间自己清干净，避免互相干扰。 */
    @BeforeEach
    void cleanDatabase() {
        for (String table : new String[]{
                "agent_turn_events", "agent_turns", "agent_canvas_run_operations",
                "agent_canvas_runs", "agent_canvas_sessions", "agent_messages",
                "agent_checkpoints", "agent_conversation_asset_groups",
                "agent_document_index", "agent_async_tasks", "agent_timeline_manifests",
                "agent_media_critiques", "agent_workflow_runs", "agent_external_actions",
                "agent_assets"}) {
            jdbc.execute("DELETE FROM public." + table);
        }
    }

    @Test
    void deletesEveryConversationScopedRowButKeepsAssetsAndOtherConversations() {
        seedConversation(CONVERSATION_ID, "turn-1", "run-1", "asset-keep", true);
        seedConversation(OTHER_CONVERSATION_ID, "turn-2", "run-2", "asset-other", false);

        ConversationContextCleanupService.CleanupReport report =
                cleanupService.deleteConversationContext(CONVERSATION_ID);

        assertThat(report.messages()).isEqualTo(1);
        assertThat(report.checkpoints()).isEqualTo(1);
        assertThat(report.turns()).isEqualTo(1);
        assertThat(report.turnEvents()).isEqualTo(1);
        assertThat(report.canvasSessions()).isEqualTo(1);
        assertThat(report.canvasRuns()).isEqualTo(1);
        assertThat(report.canvasOperations()).isEqualTo(1);
        assertThat(report.assetGroups()).isEqualTo(1);
        assertThat(report.documentIndexes()).isEqualTo(1);
        assertThat(report.asyncTasks()).isEqualTo(1);
        assertThat(report.timelineManifests()).isEqualTo(1);
        assertThat(report.mediaCritiques()).isEqualTo(1);
        assertThat(report.workflowRuns()).isEqualTo(1);
        assertThat(report.externalActions()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(14);

        // 该会话在所有上下文表里都不再有行（真删除）
        for (String table : new String[]{
                "agent_messages", "agent_checkpoints", "agent_turns",
                "agent_canvas_sessions", "agent_canvas_runs",
                "agent_conversation_asset_groups", "agent_document_index", "agent_async_tasks",
                "agent_timeline_manifests", "agent_media_critiques", "agent_workflow_runs",
                "agent_external_actions"}) {
            assertThat(countFor(table, CONVERSATION_ID))
                    .as("%s should have no rows for %s", table, CONVERSATION_ID)
                    .isZero();
        }
        // 事件表按轮次归属统计，画布操作表按运行归属统计（两表都没有 conversation_id）
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.agent_turn_events e
                JOIN public.agent_turns t ON t.id = e.turn_id
                WHERE t.conversation_id = ?
                """, Integer.class, CONVERSATION_ID))
                .as("agent_turn_events should have no rows for %s", CONVERSATION_ID)
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM public.agent_canvas_run_operations o
                JOIN public.agent_canvas_runs r ON r.id = o.run_id
                WHERE r.conversation_id = ?
                """, Integer.class, CONVERSATION_ID))
                .as("agent_canvas_run_operations should have no rows for %s", CONVERSATION_ID)
                .isZero();

        // 产出素材保留（只清上下文，产出素材不动）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.agent_assets WHERE id = 'asset-keep'", Integer.class))
                .isEqualTo(1);
        // 其他会话不受影响
        assertThat(countFor("agent_messages", OTHER_CONVERSATION_ID)).isEqualTo(1);
        assertThat(countFor("agent_turns", OTHER_CONVERSATION_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.agent_assets WHERE id = 'asset-other'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void secondCleanupIsIdempotentAndReportsNoRows() {
        seedConversation(CONVERSATION_ID, "turn-1", "run-1", "asset-keep", true);

        assertThat(cleanupService.deleteConversationContext(CONVERSATION_ID).total()).isEqualTo(14);
        // 重复执行不报错、不删到别的会话：第二遍没有行可删
        assertThat(cleanupService.deleteConversationContext(CONVERSATION_ID).total()).isZero();
    }

    private int countFor(String table, String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM public." + table + " WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    /** 为一个会话写入覆盖全部上下文表的样本行（operation 只有 run_id，按 run 归属）。 */
    private void seedConversation(String conversationId, String turnId, String runId,
                                  String assetId, boolean withDocumentIndex) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent("hello");
        messageMapper.insert(message);

        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setConversationId(conversationId);
        checkpoint.setAction("generate_image");
        checkpoint.setFormToken("token-" + turnId);
        checkpoint.setStatus("pending");
        checkpoint.setExpirationTime(OffsetDateTime.now().plusMinutes(30));
        checkpointMapper.insert(checkpoint);

        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setConversationId(conversationId);
        turn.setStatus("completed");
        turn.setStartedAt(OffsetDateTime.now());
        turnMapper.insert(turn);
        // 事件挂在真实 turn id 上：服务删事件的依据是「该会话的轮次 ID」
        AgentTurnEventEntity event = new AgentTurnEventEntity();
        event.setTurnId(turn.getId());
        event.setSequence(1);
        event.setEventType("TEXT_BLOCK_DELTA");
        event.setPayload("{}");
        event.setCreatedAt(OffsetDateTime.now());
        turnEventMapper.insertBatch(java.util.List.of(event));

        AgentCanvasSessionEntity session = new AgentCanvasSessionEntity();
        session.setConversationId(conversationId);
        session.setUserId("user-1");
        session.setProjectId("project-1");
        session.setCanvasId("canvas-1");
        session.setCanvasTitle("画布");
        session.setIntent("canvas-edit");
        session.setStatus("pending");
        session.setMediaRefsJson("[]");
        canvasSessionMapper.insert(session);

        AgentCanvasRunEntity run = new AgentCanvasRunEntity();
        run.setConversationId(conversationId);
        run.setUserId("user-1");
        run.setProjectId("project-1");
        run.setCanvasId("canvas-1");
        run.setTitle("run");
        run.setStatus("running");
        canvasRunMapper.insert(run);

        AgentCanvasRunOperationEntity operation = new AgentCanvasRunOperationEntity();
        operation.setRunId(run.getId());
        operation.setOperationKey("op-1");
        operation.setResultJson("{}");
        canvasRunOperationMapper.insert(operation);

        AgentConversationAssetGroup group = new AgentConversationAssetGroup();
        group.setConversationId(conversationId);
        group.setGroupId("group-1");
        group.setGroupVersion(1L);
        assetGroupMapper.insert(group);

        if (withDocumentIndex) {
            AgentDocumentIndex index = new AgentDocumentIndex();
            index.setAssetId(assetId + "-doc");
            index.setConversationId(conversationId);
            index.setOriginalName("doc.md");
            index.setMimeType("text/markdown");
            index.setContentHash("hash-" + assetId);
            index.setCharCount(10);
            index.setParserVersion("v1");
            documentIndexMapper.insert(index);
        }

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversationId);
        task.setUserId("user-1");
        task.setStatus("running");
        asyncTaskMapper.insert(task);

        AgentTimelineManifestEntity manifest = new AgentTimelineManifestEntity();
        manifest.setConversationId(conversationId);
        manifest.setProjectId("project-1");
        manifest.setTitle("长视频");
        manifest.setStatus("draft");
        timelineManifestMapper.insert(manifest);

        AgentMediaCritiqueEntity critique = new AgentMediaCritiqueEntity();
        critique.setConversationId(conversationId);
        critique.setProjectId("project-1");
        mediaCritiqueMapper.insert(critique);

        AgentWorkflowRun workflowRun = new AgentWorkflowRun();
        workflowRun.setConversationId(conversationId);
        workflowRun.setUserId("user-1");
        workflowRun.setProjectId("project-1");
        workflowRun.setMode("auto");
        workflowRun.setStatus("running");
        workflowRunMapper.insert(workflowRun);

        AgentExternalAction externalAction = new AgentExternalAction();
        externalAction.setConversationId(conversationId);
        externalAction.setActionKey("action-" + turnId);
        externalAction.setStatus("pending");
        externalActionMapper.insert(externalAction);

        // 产出素材：清空上下文时保留
        jdbc.update("""
                INSERT INTO public.agent_assets (id, conversation_id, type, url, status)
                VALUES (?, ?, 'image', ?, 'completed')
                """, assetId, conversationId, "/api/files/images/" + assetId + ".png");
    }
}
