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
}
