package com.moon.moonagent.ai.agent.archive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 已归档会话定期清理的配置（{@code ai.cleanup.archive.*}）。
 */
@Component
@ConfigurationProperties(prefix = "ai.cleanup.archive")
public class ArchiveCleanupProperties {

    /** 总开关。关掉后不再扫已归档会话，历史数据保持原样。 */
    private boolean enabled = true;

    /**
     * 归档后多少天才清理。留出「误归档 → 恢复」的窗口：
     * 0 表示下一次任务触发即清理最快；默认 3 天。
     */
    private int graceDays = 3;

    /** 单轮领取的会话数。 */
    private int batchSize = 50;

    /** 单次任务最多跑几批（防止一次扫太多把数据库拖慢）。 */
    private int maxBatches = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getGraceDays() {
        return Math.max(0, graceDays);
    }

    public void setGraceDays(int graceDays) {
        this.graceDays = graceDays;
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
}
