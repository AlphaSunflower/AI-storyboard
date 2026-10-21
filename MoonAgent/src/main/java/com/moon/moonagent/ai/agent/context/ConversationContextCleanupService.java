package com.moon.moonagent.ai.agent.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentCanvasRunEntity;
import com.moon.moonagent.entity.AgentCanvasRunOperationEntity;
import com.moon.moonagent.entity.AgentCanvasSessionEntity;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentConversationAssetGroup;
import com.moon.moonagent.entity.AgentDocumentIndex;
import com.moon.moonagent.entity.AgentExternalAction;
import com.moon.moonagent.entity.AgentMediaCritiqueEntity;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.AgentTimelineManifestEntity;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.entity.AgentTurnEventEntity;
import com.moon.moonagent.entity.AgentWorkflowRun;
import com.moon.moonagent.entity.AgentWorkflowStep;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentCanvasRunMapper;
import com.moon.moonagent.mapper.AgentCanvasRunOperationMapper;
import com.moon.moonagent.mapper.AgentCanvasSessionMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationAssetGroupMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentDocumentIndexMapper;
import com.moon.moonagent.mapper.AgentExternalActionMapper;
import com.moon.moonagent.mapper.AgentMediaCritiqueMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.mapper.AgentTimelineManifestMapper;
import com.moon.moonagent.mapper.AgentTurnEventMapper;
import com.moon.moonagent.mapper.AgentTurnMapper;
import com.moon.moonagent.mapper.AgentWorkflowRunMapper;
import com.moon.moonagent.mapper.AgentWorkflowStepMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * 会话上下文硬删除（「/清空上下文」的真正删除实现）。
 *
 * <p>目标语义：执行一次后，该会话在数据库里等同于「刚建出来的新对话」——
 * 消息、轮次与直播事件、待确认 checkpoint、画布会话与运行、文档索引、
 * 异步任务、时间线清单、评审记录、工作流运行、外部动作台账、资产组绑定
 * 全部真删（DELETE，不是软标记）。
 *
 * <p>刻意保留：
 * <ul>
 *   <li>{@code conversations} 行本身（会话 ID、项目归属、审批模式等身份信息）；
 *   <li>{@code agent_assets} 产出素材（只重置上下文，产出素材保留，与旧「清除聊天记录」同一口径）；
 *   <li>工具调用审计 {@code agent_tool_invocations}（合规审计，不属于模型上下文）。
 * </ul>
 *
 * <p>删除顺序遵循外键方向：先子后父（事件→轮次、画布操作→画布运行→画布会话），
 * 且逐层按 ID 显式删除，不用字符串拼 SQL。
 *
 * <p>整体在一个事务内执行：任何一张表删除失败都回滚，不会留下「删了一半」的会话。
 */
@Service
@RequiredArgsConstructor
public class ConversationContextCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextCleanupService.class);

    private final AgentMessageMapper messageMapper;
    private final AgentCheckpointMapper checkpointMapper;
    private final AgentTurnEventMapper turnEventMapper;
    private final AgentTurnMapper turnMapper;
    private final AgentCanvasRunOperationMapper canvasRunOperationMapper;
    private final AgentCanvasRunMapper canvasRunMapper;
    private final AgentCanvasSessionMapper canvasSessionMapper;
    private final AgentConversationAssetGroupMapper assetGroupMapper;
    private final AgentDocumentIndexMapper documentIndexMapper;
    private final AgentAsyncTaskMapper asyncTaskMapper;
    private final AgentTimelineManifestMapper timelineManifestMapper;
    private final AgentMediaCritiqueMapper mediaCritiqueMapper;
    private final AgentWorkflowRunMapper workflowRunMapper;
    private final AgentWorkflowStepMapper workflowStepMapper;
    private final AgentExternalActionMapper externalActionMapper;
    private final AgentConversationMapper conversationMapper;

    /** 单条 DELETE 的 IN 列表上限：避免超长 SQL 与参数上限，按块删除。 */
    private static final int DELETE_CHUNK = 500;

    /** 异步任务只有终态才允许随归档清理，运行中的任务保留给恢复链路收尾。 */
    private static final List<String> TERMINAL_ASYNC_STATUSES =
            List.of("completed", "partial", "failed", "cancelled");

    /**
     * 真删该会话的全部上下文行。
     *
     * @param conversationId 会话 ID（调用方已完成归属校验）
     * @return 各逻辑表被删除的行数，便于日志与测试断言
     */
    @Transactional
    public CleanupReport deleteConversationContext(String conversationId) {
        // 1) 直播事件与轮次：先取轮次 ID，再按 ID 删事件，最后删轮次
        List<String> turnIds = turnMapper.selectList(new LambdaQueryWrapper<AgentTurnEntity>()
                        .select(AgentTurnEntity::getId)
                        .eq(AgentTurnEntity::getConversationId, conversationId))
                .stream().map(AgentTurnEntity::getId).filter(Objects::nonNull).toList();
        int turnEvents = run("turnEvents", () -> turnIds.isEmpty() ? 0
                : turnEventMapper.delete(new LambdaQueryWrapper<AgentTurnEventEntity>()
                        .in(AgentTurnEventEntity::getTurnId, turnIds)));
        int turns = run("turns", () -> turnMapper.delete(
                new LambdaQueryWrapper<AgentTurnEntity>()
                        .eq(AgentTurnEntity::getConversationId, conversationId)));

        // 2) 画布：操作 → 运行 → 会话
        List<String> runIds = canvasRunMapper.selectList(new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .select(AgentCanvasRunEntity::getId)
                        .eq(AgentCanvasRunEntity::getConversationId, conversationId))
                .stream().map(AgentCanvasRunEntity::getId).filter(Objects::nonNull).toList();
        int canvasOperations = run("canvasOperations", () -> runIds.isEmpty() ? 0
                : canvasRunOperationMapper.delete(new LambdaQueryWrapper<AgentCanvasRunOperationEntity>()
                        .in(AgentCanvasRunOperationEntity::getRunId, runIds)));
        int canvasRuns = run("canvasRuns", () -> canvasRunMapper.delete(
                new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .eq(AgentCanvasRunEntity::getConversationId, conversationId)));
        int canvasSessions = run("canvasSessions", () -> canvasSessionMapper.delete(
                new LambdaQueryWrapper<AgentCanvasSessionEntity>()
                        .eq(AgentCanvasSessionEntity::getConversationId, conversationId)));

        // 3) 产品消息与待确认 checkpoint（HITL 卡片不得复活）
        int messages = run("messages", () -> messageMapper.delete(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, conversationId)));
        int checkpoints = run("checkpoints", () -> checkpointMapper.delete(
                new LambdaQueryWrapper<AgentCheckpoint>()
                        .eq(AgentCheckpoint::getConversationId, conversationId)));

        // 4) 其余会话级派生状态
        int assetGroups = run("assetGroups", () -> assetGroupMapper.delete(
                new LambdaQueryWrapper<AgentConversationAssetGroup>()
                        .eq(AgentConversationAssetGroup::getConversationId, conversationId)));
        int documentIndexes = run("documentIndexes", () -> documentIndexMapper.delete(
                new LambdaQueryWrapper<AgentDocumentIndex>()
                        .eq(AgentDocumentIndex::getConversationId, conversationId)));
        int asyncTasks = run("asyncTasks", () -> asyncTaskMapper.delete(
                new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getConversationId, conversationId)));
        int timelineManifests = run("timelineManifests", () -> timelineManifestMapper.delete(
                new LambdaQueryWrapper<AgentTimelineManifestEntity>()
                        .eq(AgentTimelineManifestEntity::getConversationId, conversationId)));
        int mediaCritiques = run("mediaCritiques", () -> mediaCritiqueMapper.delete(
                new LambdaQueryWrapper<AgentMediaCritiqueEntity>()
                        .eq(AgentMediaCritiqueEntity::getConversationId, conversationId)));
        int workflowRuns = run("workflowRuns", () -> workflowRunMapper.delete(
                new LambdaQueryWrapper<AgentWorkflowRun>()
                        .eq(AgentWorkflowRun::getConversationId, conversationId)));
        int externalActions = run("externalActions", () -> externalActionMapper.delete(
                new LambdaQueryWrapper<AgentExternalAction>()
                        .eq(AgentExternalAction::getConversationId, conversationId)));

        CleanupReport report = new CleanupReport(messages, checkpoints, turns, turnEvents,
                canvasSessions, canvasRuns, canvasOperations, assetGroups, documentIndexes,
                asyncTasks, timelineManifests, mediaCritiques, workflowRuns, externalActions);
        log.info("会话上下文硬删除完成: conversationId={}, {}", conversationId, report);
        return report;
    }

    /**
     * 已归档会话的运行时清理：只删「可重建 / 归档后不再使用」的运行时派生数据。
     *
     * <p><b>删除（硬删，事务内）：</b>
     * <ul>
     *   <li>{@code agent_turn_events} —— 直播事件是库里增长最快的表（每个 delta 一行），归档后不再重放；
     *   <li>{@code agent_checkpoints} —— HITL 一次性卡片，归档后不得复活；
     *   <li>{@code agent_canvas_run_operations → agent_canvas_runs → agent_canvas_sessions}；
     *   <li>{@code agent_document_index} —— 文档摘要，需要时从 MinIO 原件重新解析；
     *   <li>{@code agent_async_tasks} 的终态行（completed / partial / failed / cancelled）；
     *   <li>{@code agent_workflow_steps → agent_workflow_runs}。
     * </ul>
     *
     * <p><b>刻意保留：</b>
     * <ul>
     *   <li>{@code conversations} 行、{@code agent_messages} —— 归档会话仍要能打开看历史；
     *   <li>{@code agent_assets} 行与 <b>MinIO 里的图片/文档对象</b> —— 消息里的文件卡和项目库引用
     *       必须继续可下载，本方法及调用链不得删除对象存储数据；
     *   <li>{@code agent_turns} —— 轻量行，保留 turnId 引用（观测页/消息 metadata 仍可跳转）；
     *   <li>{@code agent_tool_invocations}、{@code agent_subagent_runs}、{@code agent_external_actions}
     *       —— 合规审计，按各自保留期清理，不随归档删；
     *   <li>{@code agent_timeline_manifests}、{@code agent_media_critiques}、资产组绑定。
     * </ul>
     *
     * <p>AgentScope 运行时状态（{@code agentscope_sessions}）与沙箱工作区不在本方法内删除：
     * 前者必须经 {@code MoonAgentRuntime#clearSession} 同时清本地缓存，后者是外部 HTTP 调用，
     * 都由调用方在事务提交后处理。
     *
     * <p>同时把 {@code agentscope_session_initialized_at} 与 {@code canvas_context} 置空：
     * 运行时状态已被删除，若保留初始化标记，取消归档后的下一轮会带着空上下文继续；
     * 置空后走既有的「从最近消息重建会话」路径。
     */
    @Transactional
    public ArchiveCleanupReport cleanupArchivedRuntime(String conversationId) {
        List<String> turnIds = turnIds(conversationId);
        int turnEvents = deleteTurnEvents(turnIds);

        int checkpoints = run("checkpoints", () -> checkpointMapper.delete(
                new LambdaQueryWrapper<AgentCheckpoint>()
                        .eq(AgentCheckpoint::getConversationId, conversationId)));

        List<String> runIds = canvasRunIds(conversationId);
        int canvasOperations = run("canvasOperations", () -> runIds.isEmpty() ? 0
                : canvasRunOperationMapper.delete(new LambdaQueryWrapper<AgentCanvasRunOperationEntity>()
                        .in(AgentCanvasRunOperationEntity::getRunId, runIds)));
        int canvasRuns = run("canvasRuns", () -> canvasRunMapper.delete(
                new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .eq(AgentCanvasRunEntity::getConversationId, conversationId)));
        int canvasSessions = run("canvasSessions", () -> canvasSessionMapper.delete(
                new LambdaQueryWrapper<AgentCanvasSessionEntity>()
                        .eq(AgentCanvasSessionEntity::getConversationId, conversationId)));

        int documentIndexes = run("documentIndexes", () -> documentIndexMapper.delete(
                new LambdaQueryWrapper<AgentDocumentIndex>()
                        .eq(AgentDocumentIndex::getConversationId, conversationId)));

        int asyncTasks = run("asyncTasks", () -> asyncTaskMapper.delete(
                new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getConversationId, conversationId)
                        .in(AgentAsyncTask::getStatus, TERMINAL_ASYNC_STATUSES)));

        List<String> workflowRunIds = workflowRunIds(conversationId);
        int workflowSteps = run("workflowSteps", () -> workflowRunIds.isEmpty() ? 0
                : workflowStepMapper.delete(new LambdaQueryWrapper<AgentWorkflowStep>()
                        .in(AgentWorkflowStep::getWorkflowRunId, workflowRunIds)));
        int workflowRuns = run("workflowRuns", () -> workflowRunMapper.delete(
                new LambdaQueryWrapper<AgentWorkflowRun>()
                        .eq(AgentWorkflowRun::getConversationId, conversationId)));

        conversationMapper.update(null, new UpdateWrapper<AgentConversation>()
                .eq("id", conversationId)
                .set("agentscope_session_initialized_at", null)
                .set("canvas_context", null));

        ArchiveCleanupReport report = new ArchiveCleanupReport(turnEvents, checkpoints, canvasSessions,
                canvasRuns, canvasOperations, documentIndexes, asyncTasks, workflowSteps, workflowRuns);
        log.info("已归档会话运行时清理完成: conversationId={}, {}", conversationId, report);
        return report;
    }

    private List<String> turnIds(String conversationId) {
        return turnMapper.selectList(new LambdaQueryWrapper<AgentTurnEntity>()
                        .select(AgentTurnEntity::getId)
                        .eq(AgentTurnEntity::getConversationId, conversationId))
                .stream().map(AgentTurnEntity::getId).filter(Objects::nonNull).toList();
    }

    private List<String> canvasRunIds(String conversationId) {
        return canvasRunMapper.selectList(new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .select(AgentCanvasRunEntity::getId)
                        .eq(AgentCanvasRunEntity::getConversationId, conversationId))
                .stream().map(AgentCanvasRunEntity::getId).filter(Objects::nonNull).toList();
    }

    private List<String> workflowRunIds(String conversationId) {
        return workflowRunMapper.selectList(new LambdaQueryWrapper<AgentWorkflowRun>()
                        .select(AgentWorkflowRun::getId)
                        .eq(AgentWorkflowRun::getConversationId, conversationId))
                .stream().map(AgentWorkflowRun::getId).filter(Objects::nonNull).toList();
    }

    /** 按块删除某个会话全部轮次的直播事件（turnIds 可能很长，不能一次 IN 到底）。 */
    private int deleteTurnEvents(List<String> turnIds) {
        if (turnIds.isEmpty()) return 0;
        int deleted = 0;
        for (int from = 0; from < turnIds.size(); from += DELETE_CHUNK) {
            List<String> chunk = new ArrayList<>(
                    turnIds.subList(from, Math.min(turnIds.size(), from + DELETE_CHUNK)));
            deleted += turnEventMapper.delete(new LambdaQueryWrapper<AgentTurnEventEntity>()
                    .in(AgentTurnEventEntity::getTurnId, chunk));
        }
        return deleted;
    }


    /** 单表删除包装：返回行数并按需记录，便于定位「哪张表没删掉」。 */
    private int run(String name, IntSupplier delete) {
        int affected = delete.getAsInt();
        if (affected > 0) {
            log.debug("会话清理删除 {} : {} 行", name, affected);
        }
        return affected;
    }

    /**
     * 归档清理的结果：逐表记录被删行数。
     *
     * <p>只统计数据库行 —— 归档链路从不删除 MinIO 里的图片/文档对象，也不删除
     * {@code agent_assets} 行与消息，因此这里没有「对象数」。
     */
    public record ArchiveCleanupReport(
            int turnEvents,
            int checkpoints,
            int canvasSessions,
            int canvasRuns,
            int canvasOperations,
            int documentIndexes,
            int asyncTasks,
            int workflowSteps,
            int workflowRuns) {

        /** 删除的总行数（0 表示已没有可清的运行时数据，仍会写清理台账）。 */
        public int total() {
            return turnEvents + checkpoints + canvasSessions + canvasRuns + canvasOperations
                    + documentIndexes + asyncTasks + workflowSteps + workflowRuns;
        }
    }

    /**
     * 一次硬删除的结果，按逻辑名记录被删行数。
     */
    public record CleanupReport(
            int messages,
            int checkpoints,
            int turns,
            int turnEvents,
            int canvasSessions,
            int canvasRuns,
            int canvasOperations,
            int assetGroups,
            int documentIndexes,
            int asyncTasks,
            int timelineManifests,
            int mediaCritiques,
            int workflowRuns,
            int externalActions) {

        /** 删除的总行数（>0 表示确有上下文被清掉）。 */
        public int total() {
            return messages + checkpoints + turns + turnEvents + canvasSessions + canvasRuns
                    + canvasOperations + assetGroups + documentIndexes + asyncTasks
                    + timelineManifests + mediaCritiques + workflowRuns + externalActions;
        }
    }
}
