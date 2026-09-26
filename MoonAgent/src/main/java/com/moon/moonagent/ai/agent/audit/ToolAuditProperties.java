package com.moon.moonagent.ai.agent.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.agent.tool-audit")
public class ToolAuditProperties {

    private boolean enabled = true;
    private boolean uiEnabled = true;
    private int retentionDays = 90;
    private String cleanupCron = "0 30 3 * * *";
    private int cleanupBatchSize = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUiEnabled() {
        return uiEnabled;
    }

    public void setUiEnabled(boolean uiEnabled) {
        this.uiEnabled = uiEnabled;
    }

    public int getRetentionDays() {
        return Math.max(1, retentionDays);
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public int getCleanupBatchSize() {
        return Math.max(1, cleanupBatchSize);
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
