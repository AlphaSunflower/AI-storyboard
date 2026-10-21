package com.moon.moonagent.ai.agent.maintenance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据保留期配置（{@code ai.cleanup.retention.*}）。
 *
 * <p>约定：**保留天数 <= 0 表示关闭该项**，不再执行计数与删除。
 */
@Component
@ConfigurationProperties(prefix = "ai.cleanup.retention")
public class RetentionProperties {

    /** 总开关。 */
    private boolean enabled = true;

    /**
     * 演练模式：只统计「本来会删多少行」并打日志，不执行删除。
     * 默认开启，观察一到两周日志确认数字合理后再关掉。
     */
    private boolean dryRun = true;

    /** 单条 DELETE 的行数上限。 */
    private int batchSize = 5000;

    /** 单项目标单次任务最多跑几批，防止一次扫太多拖慢数据库。 */
    private int maxBatches = 20;

    /** `agent_turn_events` 直播事件保留天数（0=关闭）。 */
    private int turnEventsDays = 30;

    /** `agent_subagent_runs` 子智能体节点审计保留天数（0=关闭）。 */
    private int subagentRunsDays = 90;

    /** `agentscope_sessions` 中「会话已不存在」的孤儿运行时状态保留天数（0=关闭）。 */
    private int orphanSessionsDays = 7;

    /** `asset_outbox` 中已投递（SUCCEEDED/DEAD）事件的保留天数（0=关闭）。 */
    private int outboxDays = 7;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getBatchSize() {
        return Math.max(1, batchSize);
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatches() {
        return Math.max(1, maxBatches);
    }

    public void setMaxBatches(int maxBatches) {
        this.maxBatches = maxBatches;
    }

    public int getTurnEventsDays() {
        return Math.max(0, turnEventsDays);
    }

    public void setTurnEventsDays(int turnEventsDays) {
        this.turnEventsDays = turnEventsDays;
    }

    public int getSubagentRunsDays() {
        return Math.max(0, subagentRunsDays);
    }

    public void setSubagentRunsDays(int subagentRunsDays) {
        this.subagentRunsDays = subagentRunsDays;
    }

    public int getOrphanSessionsDays() {
        return Math.max(0, orphanSessionsDays);
    }

    public void setOrphanSessionsDays(int orphanSessionsDays) {
        this.orphanSessionsDays = orphanSessionsDays;
    }

    public int getOutboxDays() {
        return Math.max(0, outboxDays);
    }

    public void setOutboxDays(int outboxDays) {
        this.outboxDays = outboxDays;
    }
}
