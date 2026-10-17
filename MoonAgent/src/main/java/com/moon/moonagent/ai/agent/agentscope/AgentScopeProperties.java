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
    /** 见 {@link #isDynamicDefaultModel()}：默认开，置 false 可回退到「启动时固化」的旧行为 */
    private boolean dynamicDefaultModel = true;
    private int maxRetries = 3;
    private int maxIters = 24;
    private int assetSearchLimit = 8;
    private boolean directMediaGeneration = true;
    private int journeyDirectVideoMaxSeconds = 15;
    private String stateSchema = "public";
    private String stateTable = "agentscope_sessions";
    private String workspaceDir = "";
    private boolean structuredOutputEnabled = true;
    private boolean subagentsEnabled = true;
    private boolean compactionEnabled = true;
    /**
     * 消息数触发的压缩条件：{@code <= 0} 表示禁用该条件（harness 的
     * {@code ConversationCompactor.shouldCompact} 只在 {@code > 0} 时比较，因此 0 是安全的"关闭"值）。
     *
     * <p>默认 400：画布编排一轮就能产生几十条工具结果巨大的消息，旧的 40 条会被反复误触发
     * （2026-10-16 实测：45 条 / 46931 tokens 与 42 条 / 29855 tokens 两次都不该压）。
     * 注意 setter 只做 {@code Math.max(0, ...)}，绝不能钳到 1——那会让每条消息都触发压缩。
     */
    private int compactionTriggerMessages = 400;
    /** 显式 token 阈值：仅当 {@link #compactionTriggerRatio} {@code <= 0} 时生效（人工兜底）。 */
    private int compactionTriggerTokens = 48_000;
    /** 自动阈值比例：{@code > 0} 时 triggerTokens = max(floor, round(模型窗口 × ratio))。 */
    private double compactionTriggerRatio = 0.5;
    /** 自动阈值的下限，避免小窗口模型把阈值压得过低。 */
    private int compactionTriggerTokensFloor = 60_000;
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
    /**
     * 画布工具 schema 是否对模型隐藏注入型参数
     * （{@code userId/projectId/canvasId/runId/operationKey}）。
     *
     * <p>这些值由 {@code CanvasRunToolAdapter} 在模型调用之后强制注入，模型看到它们只会
     * 增加每次调用的 schema token（15 个画布工具 × 5 个属性）。默认隐藏；
     * 置 false 时恢复到"原样透出 MCP schema"的旧行为。
     */
    private boolean canvasSchemaHideInjectedParams = true;
    private boolean toolResultEvictionEnabled = true;
    private int toolResultEvictionMaxChars = 80_000;
    private int toolResultEvictionPreviewChars = 2_000;
    private boolean otelTracingEnabled = true;
    private boolean distributedStoreEnabled = true;
    /**
     * 生成结果诊断开关，默认关闭：自动视觉评审会对每次生图/生视频结果额外发起一次
     * 付费模型调用，且用户往往已经满意首次结果，多余诊断只会造成浪费。
     * 保留开关仅用于显式重新打开（含 {@code review_generated_media} 工具注册）。
     */
    private boolean critiqueEnabled = false;
    private WebSearch webSearch = new WebSearch();
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

    /**
     * 主智能体模型是否动态跟随网关默认模型（{@code agentscope.model} 为空时生效）。
     *
     * <p>开启后 agent 只构建一次、持有 {@link GatewayDefaultModel} 包装器：<b>在途 turn 内模型钉住不变，
     * 只在智能体闲置（无在途 turn）时于下一轮开始时捡起新的网关默认模型</b>，因此切换默认模型
     * 既不需要重建 agent，也不需要重启 MoonAgent。关闭后恢复旧行为（启动时解析一次并固化）。
     */
    public boolean isDynamicDefaultModel() {
        return dynamicDefaultModel;
    }

    public void setDynamicDefaultModel(boolean dynamicDefaultModel) {
        this.dynamicDefaultModel = dynamicDefaultModel;
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

    /**
     * 复合旅程直接出片的时长上限（秒）。
     *
     * <p>≤ 该值走「生图 → 视频」短分支；&gt; 该值或显式要求分镜时走
     * 「分镜 → 逐镜图片 → 逐镜视频 → 合成」长分支。默认 15 秒，与
     * {@code VideoGenerationPolicy.MAX_DIRECT_SECONDS} 的单镜门禁保持一致。
     */
    public int getJourneyDirectVideoMaxSeconds() {
        return journeyDirectVideoMaxSeconds;
    }

    public void setJourneyDirectVideoMaxSeconds(int journeyDirectVideoMaxSeconds) {
        this.journeyDirectVideoMaxSeconds = Math.max(1, Math.min(600, journeyDirectVideoMaxSeconds));
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
        // 只允许钳到 0：0/负数表示"禁用消息数条件"。钳到 1 会让每条消息都触发压缩。
        this.compactionTriggerMessages = Math.max(0, compactionTriggerMessages);
    }

    public int getCompactionTriggerTokens() {
        return compactionTriggerTokens;
    }

    public void setCompactionTriggerTokens(int compactionTriggerTokens) {
        this.compactionTriggerTokens = Math.max(1, compactionTriggerTokens);
    }

    public double getCompactionTriggerRatio() {
        return compactionTriggerRatio;
    }

    public void setCompactionTriggerRatio(double compactionTriggerRatio) {
        this.compactionTriggerRatio = Math.max(0d, compactionTriggerRatio);
    }

    public int getCompactionTriggerTokensFloor() {
        return compactionTriggerTokensFloor;
    }

    public void setCompactionTriggerTokensFloor(int compactionTriggerTokensFloor) {
        this.compactionTriggerTokensFloor = Math.max(1, compactionTriggerTokensFloor);
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

    public boolean isCanvasSchemaHideInjectedParams() {
        return canvasSchemaHideInjectedParams;
    }

    public void setCanvasSchemaHideInjectedParams(boolean canvasSchemaHideInjectedParams) {
        this.canvasSchemaHideInjectedParams = canvasSchemaHideInjectedParams;
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

    public WebSearch getWebSearch() {
        return webSearch;
    }

    public void setWebSearch(WebSearch webSearch) {
        this.webSearch = webSearch == null ? new WebSearch() : webSearch;
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

    /**
     * 网页搜索配置。后端只有一个：<b>DeepSeek 原生网页搜索</b>（对齐 DSH 的
     * {@code @deepseek-ai/dsh-web-search-deepseek}）。一次 Anthropic 兼容 Messages 调用
     * （{@code POST {base-url}/messages}）携带服务端工具 {@code web_search_20250305}，
     * 结果从结构化的 {@code web_search_tool_result} 块里解析，绝不从模型文本里抓 URL。
     * 搜索在 DeepSeek 服务端执行，按账号计费，因此必须有 key。
     *
     * <p>这里刻意不再提供第二个后端（曾经是自托管 SearXNG）：那个实例不常驻时，
     * 每一次网页检索都以连接失败收场，而失败会直接出现在 /chat 的工具卡上。
     * 一个「可选但没人部署」的后端只会制造事故，所以只保留唯一可用的这条链路。
     * 需要换端点时改 {@code base-url} 即可（任何 Anthropic 兼容 Messages 网关都行）。
     *
     * <p>不走 AgentScope 自带 MCP 客户端：它编译时依赖 MCP SDK 0.17.x 的
     * {@code HttpClientStreamableHttpTransport.Builder#customizeRequest}，而本项目根 pom 为 Spring AI 2.0
     * 统一管理到 MCP SDK 2.0.x（该方法已移除），两者无法共存于同一 classpath。
     */
    public static class WebSearch {

        /** Anthropic 兼容 Messages 基址；{@code /messages} 由客户端追加。 */
        public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic/v1";
        /** Anthropic 格式模型名，与本仓库 DSH 客户端（web-search-deepseek）保持一致。 */
        public static final String DEFAULT_MODEL = "deepseek-v4-flash";

        private boolean enabled = true;
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey = "";
        private String model = DEFAULT_MODEL;
        private int maxResults = 8;
        private int maxUses = 5;
        private int maxTokens = 4096;
        private Duration timeout = Duration.ofSeconds(60);
        private Duration cacheTtl = Duration.ofMinutes(5);
        private int cacheMaxEntries = 256;

        /** 只有开启且 key 非空才注册工具；否则保留原状，由调用方给出明确提示。 */
        public boolean isConfigured() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** 完整 Messages 端点；调用方直接 POST 该地址。 */
        public String getMessagesEndpoint() {
            String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + "/messages";
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        }

        /** 服务端网页搜索每次请求的最大使用次数（{@code max_uses}），仅 DeepSeek 后端使用。 */
        public int getMaxUses() {
            return maxUses;
        }

        public void setMaxUses(int maxUses) {
            this.maxUses = Math.max(1, Math.min(10, maxUses));
        }

        /** 该次辅助 Messages 调用的生成 token 上限，仅 DeepSeek 后端使用。 */
        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = Math.max(256, Math.min(32_000, maxTokens));
        }

        public int getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(int maxResults) {
            this.maxResults = Math.max(1, Math.min(10, maxResults));
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            // 原生搜索是一次完整模型轮次，比纯检索端点慢，默认 60s。
            this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Duration.ofSeconds(60) : timeout;
        }

        /** 成功搜索结果的 TTL；相同 query 在窗口内直接命中缓存，避免重复计费。 */
        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()
                    ? Duration.ofMinutes(5) : cacheTtl;
        }

        /** 缓存条数上限，超出后按写入时间淘汰最旧的条目。 */
        public int getCacheMaxEntries() {
            return cacheMaxEntries;
        }

        public void setCacheMaxEntries(int cacheMaxEntries) {
            this.cacheMaxEntries = Math.max(1, Math.min(4096, cacheMaxEntries));
        }
    }
}
