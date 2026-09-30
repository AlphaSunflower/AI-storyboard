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
    private boolean planModeEnabled = true;
    private boolean taskListEnabled = true;
    private String planFileDirectory = "plans";
    private boolean toolResultEvictionEnabled = true;
    private int toolResultEvictionMaxChars = 80_000;
    private int toolResultEvictionPreviewChars = 2_000;
    private boolean otelTracingEnabled = true;
    private boolean distributedStoreEnabled = true;
    private boolean critiqueEnabled = true;
    private int critiqueMaxRepairs = 1;
    private Models models = new Models();
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

    public boolean isPlanModeEnabled() {
        return planModeEnabled;
    }

    public void setPlanModeEnabled(boolean planModeEnabled) {
        this.planModeEnabled = planModeEnabled;
    }

    public boolean isTaskListEnabled() {
        return taskListEnabled;
    }

    public void setTaskListEnabled(boolean taskListEnabled) {
        this.taskListEnabled = taskListEnabled;
    }

    public String getPlanFileDirectory() {
        return planFileDirectory;
    }

    public void setPlanFileDirectory(String planFileDirectory) {
        this.planFileDirectory = planFileDirectory == null || planFileDirectory.isBlank()
                ? "plans" : planFileDirectory.trim();
    }

    public boolean isToolResultEvictionEnabled() {
        return toolResultEvictionEnabled;
    }

    public void setToolResultEvictionEnabled(boolean toolResultEvictionEnabled) {
        this.toolResultEvictionEnabled = toolResultEvictionEnabled;
    }

    public int getToolResultEvictionMaxChars() {
        return toolResultEvictionMaxChars;
    }

    public void setToolResultEvictionMaxChars(int toolResultEvictionMaxChars) {
        this.toolResultEvictionMaxChars = Math.max(2_000, toolResultEvictionMaxChars);
    }

    public int getToolResultEvictionPreviewChars() {
        return toolResultEvictionPreviewChars;
    }

    public void setToolResultEvictionPreviewChars(int toolResultEvictionPreviewChars) {
        this.toolResultEvictionPreviewChars = Math.max(200, toolResultEvictionPreviewChars);
    }

    public boolean isOtelTracingEnabled() {
        return otelTracingEnabled;
    }

    public void setOtelTracingEnabled(boolean otelTracingEnabled) {
        this.otelTracingEnabled = otelTracingEnabled;
    }

    public boolean isDistributedStoreEnabled() {
        return distributedStoreEnabled;
    }

    public void setDistributedStoreEnabled(boolean distributedStoreEnabled) {
        this.distributedStoreEnabled = distributedStoreEnabled;
    }

    public boolean isCritiqueEnabled() {
        return critiqueEnabled;
    }

    public void setCritiqueEnabled(boolean critiqueEnabled) {
        this.critiqueEnabled = critiqueEnabled;
    }

    public int getCritiqueMaxRepairs() {
        return critiqueMaxRepairs;
    }

    public void setCritiqueMaxRepairs(int critiqueMaxRepairs) {
        this.critiqueMaxRepairs = Math.max(0, Math.min(1, critiqueMaxRepairs));
    }

    public Models getModels() {
        return models;
    }

    public void setModels(Models models) {
        this.models = models == null ? new Models() : models;
    }

    public Preflight getPreflight() {
        return preflight;
    }

    public void setPreflight(Preflight preflight) {
        this.preflight = preflight == null ? new Preflight() : preflight;
    }

    public static class Models {
        private String plan = "";
        private String prompt = "";
        private String critic = "";
        private String vision = "";
        private String image = "";
        private String video = "";

        public String getPlan() {
            return plan;
        }

        public void setPlan(String plan) {
            this.plan = text(plan);
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = text(prompt);
        }

        public String getCritic() {
            return critic;
        }

        public void setCritic(String critic) {
            this.critic = text(critic);
        }

        public String getVision() {
            return vision;
        }

        public void setVision(String vision) {
            this.vision = text(vision);
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = text(image);
        }

        public String getVideo() {
            return video;
        }

        public void setVideo(String video) {
            this.video = text(video);
        }

        private static String text(String value) {
            return value == null ? "" : value.trim();
        }
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
