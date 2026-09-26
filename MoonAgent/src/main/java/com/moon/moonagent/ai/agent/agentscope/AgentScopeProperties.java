package com.moon.moonagent.ai.agent.agentscope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "ai.agent.agentscope")
public class AgentScopeProperties {

    private boolean enabled;
    private String model = "";
    private String fallbackModel = "deepseek-flash";
    private int maxRetries = 3;
    private int maxIters = 24;
    private int assetSearchLimit = 8;
    private boolean directMediaGeneration = true;
    private String stateSchema = "public";
    private String stateTable = "agentscope_sessions";
    private String workspaceDir = "";
    private boolean structuredOutputEnabled = true;
    private boolean subagentsEnabled = true;
    private boolean compactionEnabled = true;
    private int compactionTriggerMessages = 40;
    private int compactionTriggerTokens = 60_000;
    private int compactionKeepMessages = 12;
    private int compactionKeepTokens = 8_000;
    private boolean asyncEnabled = true;
    private Duration asyncToolTimeout = Duration.ofSeconds(90);
    private int batchConfirmThreshold = 4;
    private int imageBatchConcurrency = 3;
    private boolean wakeupEnabled = true;
    private String wakeupDir = "";
    private int wakeupMaxRetries = 5;
    private Duration wakeupRetryDelay = Duration.ofSeconds(2);
    private Preflight preflight = new Preflight();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel == null ? "" : fallbackModel;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = Math.max(2, maxIters);
    }

    public int getAssetSearchLimit() {
        return assetSearchLimit;
    }

    public void setAssetSearchLimit(int assetSearchLimit) {
        this.assetSearchLimit = Math.max(1, assetSearchLimit);
    }

    public boolean isDirectMediaGeneration() {
        return directMediaGeneration;
    }

    public void setDirectMediaGeneration(boolean directMediaGeneration) {
        this.directMediaGeneration = directMediaGeneration;
    }

    public String getStateSchema() {
        return stateSchema;
    }

    public void setStateSchema(String stateSchema) {
        this.stateSchema = stateSchema;
    }

    public String getStateTable() {
        return stateTable;
    }

    public void setStateTable(String stateTable) {
        this.stateTable = stateTable;
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }

    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir == null ? "" : workspaceDir.trim();
    }

    public boolean isStructuredOutputEnabled() {
        return structuredOutputEnabled;
    }

    public void setStructuredOutputEnabled(boolean structuredOutputEnabled) {
        this.structuredOutputEnabled = structuredOutputEnabled;
    }

    public boolean isSubagentsEnabled() {
        return subagentsEnabled;
    }

    public void setSubagentsEnabled(boolean subagentsEnabled) {
        this.subagentsEnabled = subagentsEnabled;
    }

    public boolean isCompactionEnabled() {
        return compactionEnabled;
    }

    public void setCompactionEnabled(boolean compactionEnabled) {
        this.compactionEnabled = compactionEnabled;
    }

    public int getCompactionTriggerMessages() {
        return compactionTriggerMessages;
    }

    public void setCompactionTriggerMessages(int compactionTriggerMessages) {
        this.compactionTriggerMessages = Math.max(1, compactionTriggerMessages);
    }

    public int getCompactionTriggerTokens() {
        return compactionTriggerTokens;
    }

    public void setCompactionTriggerTokens(int compactionTriggerTokens) {
        this.compactionTriggerTokens = Math.max(1, compactionTriggerTokens);
    }

    public int getCompactionKeepMessages() {
        return compactionKeepMessages;
    }

    public void setCompactionKeepMessages(int compactionKeepMessages) {
        this.compactionKeepMessages = Math.max(1, compactionKeepMessages);
    }

    public int getCompactionKeepTokens() {
        return compactionKeepTokens;
    }

    public void setCompactionKeepTokens(int compactionKeepTokens) {
        this.compactionKeepTokens = Math.max(1, compactionKeepTokens);
    }

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    public void setAsyncEnabled(boolean asyncEnabled) {
        this.asyncEnabled = asyncEnabled;
    }

    public Duration getAsyncToolTimeout() {
        return asyncToolTimeout;
    }

    public void setAsyncToolTimeout(Duration asyncToolTimeout) {
        this.asyncToolTimeout = asyncToolTimeout == null
                ? Duration.ofSeconds(90) : asyncToolTimeout;
    }

    public int getBatchConfirmThreshold() {
        return batchConfirmThreshold;
    }

    public void setBatchConfirmThreshold(int batchConfirmThreshold) {
        this.batchConfirmThreshold = Math.max(1, batchConfirmThreshold);
    }

    public int getImageBatchConcurrency() {
        return imageBatchConcurrency;
    }

    public void setImageBatchConcurrency(int imageBatchConcurrency) {
        this.imageBatchConcurrency = Math.max(1, Math.min(8, imageBatchConcurrency));
    }

    public boolean isWakeupEnabled() {
        return wakeupEnabled;
    }

    public void setWakeupEnabled(boolean wakeupEnabled) {
        this.wakeupEnabled = wakeupEnabled;
    }

    public String getWakeupDir() {
        return wakeupDir;
    }

    public void setWakeupDir(String wakeupDir) {
        this.wakeupDir = wakeupDir == null ? "" : wakeupDir.trim();
    }

    public int getWakeupMaxRetries() {
        return wakeupMaxRetries;
    }

    public void setWakeupMaxRetries(int wakeupMaxRetries) {
        this.wakeupMaxRetries = Math.max(1, wakeupMaxRetries);
    }

    public Duration getWakeupRetryDelay() {
        return wakeupRetryDelay;
    }

    public void setWakeupRetryDelay(Duration wakeupRetryDelay) {
        this.wakeupRetryDelay = wakeupRetryDelay == null
                ? Duration.ofSeconds(2) : wakeupRetryDelay;
    }

    public Preflight getPreflight() {
        return preflight;
    }

    public void setPreflight(Preflight preflight) {
        this.preflight = preflight == null ? new Preflight() : preflight;
    }

    public static class Preflight {

        private boolean enabled = true;
        private Duration timeout = Duration.ofSeconds(20);
        private double referenceConfidenceThreshold = 0.65;
        private int maxRepairs = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Duration.ofSeconds(20) : timeout;
        }

        public double getReferenceConfidenceThreshold() {
            return referenceConfidenceThreshold;
        }

        public void setReferenceConfidenceThreshold(double referenceConfidenceThreshold) {
            if (referenceConfidenceThreshold <= 0 || referenceConfidenceThreshold > 1) {
                this.referenceConfidenceThreshold = 0.65;
            } else {
                this.referenceConfidenceThreshold = referenceConfidenceThreshold;
            }
        }

        public int getMaxRepairs() {
            return maxRepairs;
        }

        public void setMaxRepairs(int maxRepairs) {
            this.maxRepairs = Math.max(1, Math.min(1, maxRepairs));
        }
    }
}
