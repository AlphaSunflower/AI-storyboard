package com.moon.moonagent.ai.agent.maintenance;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 数据保留期清理：把「过期且不再需要的运行时数据」按保留天数分批删掉。
 *
 * <p>四个目标（每个都能单独关掉：保留天数配 0）：
 * <ul>
 *   <li>{@code agent_turn_events} —— 直播事件（每个 delta 一行，增长最快）；</li>
 *   <li>{@code agent_subagent_runs} —— 子智能体节点审计（output 是整段脚本，单行很大）；</li>
 *   <li>{@code asset_outbox} —— 已投递（SUCCEEDED / DEAD）的资产事件；</li>
 *   <li>{@code agentscope_sessions} —— 会话已不存在的孤儿运行时状态。</li>
 * </ul>
 *
 * <p><b>不做的事</b>：不删消息、不删产出素材行、不删 MinIO 对象、不删工具调用审计、
 * 不碰仍然存在的会话（含已归档）的运行时状态——归档会话的清理走
 * {@code ArchivedConversationCleanupService}，那条链路会同步重置会话的初始化标记。
 *
 * <p>默认 {@code dry-run}：只统计并打印「本来会删多少行」，不执行删除。
 */
@Service
@RequiredArgsConstructor
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    private final DataRetentionRepository repository;
    private final RetentionProperties properties;

    /**
     * 跑一轮全部目标。
     *
     * @return 实际删除的行数（dry-run 下恒为 0）
     */
    public int runOnce() {
        if (!properties.isEnabled()) return 0;
        int total = 0;
        total += sweep("agent_turn_events（直播事件）", properties.getTurnEventsDays(),
                repository::countTurnEventsOlderThan, repository::deleteTurnEventsOlderThan);
        total += sweep("agent_subagent_runs（子智能体审计）", properties.getSubagentRunsDays(),
                repository::countSubagentRunsOlderThan, repository::deleteSubagentRunsOlderThan);
        total += sweep("asset_outbox（已投递事件）", properties.getOutboxDays(),
                repository::countTerminalOutboxOlderThan, repository::deleteTerminalOutboxOlderThan);
        total += sweep("agentscope_sessions（孤儿运行时状态）", properties.getOrphanSessionsDays(),
                repository::countOrphanSessionStatesOlderThan,
                repository::deleteOrphanSessionStatesOlderThan);
        return total;
    }

    private int sweep(String name, int days, LongCounter counter, BatchDeleter deleter) {
        if (days <= 0) {
            log.debug("数据保留：{} 未配置保留期（<=0），跳过", name);
            return 0;
        }
        try {
            long candidates = counter.count(days);
            if (candidates == 0) {
                log.debug("数据保留：{} 没有超过 {} 天的数据", name, days);
                return 0;
            }
            if (properties.isDryRun()) {
                log.info("数据保留[dry-run]：{} 超过 {} 天共 {} 行，演练模式不删除"
                                + "（置 AI_RETENTION_DRY_RUN=false 后才执行）",
                        name, days, candidates);
                return 0;
            }
            int deleted = 0;
            for (int round = 0; round < properties.getMaxBatches(); round++) {
                int affected = deleter.delete(days, properties.getBatchSize());
                deleted += affected;
                if (affected < properties.getBatchSize()) break;
            }
            log.info("数据保留：{} 超过 {} 天，删除 {} 行（待删 {} 行）",
                    name, days, deleted, candidates);
            return deleted;
        } catch (Exception e) {
            // 单项目标失败（例如该环境没有 agent_subagent_runs 表）不影响其它目标
            log.warn("数据保留：{} 清理失败，跳过: {}", name, e.getMessage());
            return 0;
        }
    }

    @FunctionalInterface
    interface LongCounter {
        long count(int days);
    }

    @FunctionalInterface
    interface BatchDeleter {
        int delete(int days, int limit);
    }
}
