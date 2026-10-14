package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.AgentApprovalMode;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.agent.AgentContextWindowResolver;
import com.moon.moonagent.ai.agent.AgentConcurrencyGuard;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.ContextTokenEstimator;
import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.document.AgentDocumentService;
import com.moon.moonagent.ai.agent.agentscope.nacos.GlobalPromptRepository;
import com.moon.moonagent.ai.agent.agentscope.nacos.NacosMcpMetadataRepository;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.recovery.ExternalActionLedgerService;
import com.moon.moonagent.ai.agent.recovery.AgentRecoverySource;
import com.moon.moonagent.ai.agent.recovery.RecoveryCandidate;
import com.moon.moonagent.ai.agent.recovery.RecoveryOutcome;
import com.moon.moonagent.ai.agent.sandbox.SandboxArtifactPublisher;
import com.moon.moonagent.ai.agent.stream.ToolCallStreamGuard;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.security.OutboundUrlGuard;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.postgresql.PostgresDistributedStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class MoonAgentRuntime implements AgentRecoverySource {

    private static final Logger log = LoggerFactory.getLogger(MoonAgentRuntime.class);
    /** 画布编辑模式不加载的技能：分镜生成属于画布之外的链路。 */
    private static final String STORYBOARD_SKILL = "storyboard";
    private static final String COMPACTION_PROMPT = """
            Compress this AIGC conversation for continuation. Preserve only execution-critical
            facts and write "None" when a section has no data.

            ## CURRENT USER INTENT
            ## GENERATION DECISIONS
            ## SESSION ASSETS
            Include assetId, type, URL, and whether each asset was generated or uploaded.
            ## ASYNC TASKS
            Include taskId, type, status and result URL.
            ## PENDING HUMAN INPUT
            ## NEXT ACTION

            <messages>
            {messages}
            </messages>
            """;

    private final AgentScopeProperties properties;
    private final AgentScopeToolkitFactory toolkitFactory;
    private final AgentScopeCanvasTools canvasTools;
    private final AgentCanvasSessionService canvasSessions;
    private final AgentScopeCheckpointService checkpoints;
    private final AgentRuntimeSupport support;
    private final AgentScopeModelProvider modelProvider;
    private final AgentScopeAssetGroupService assetGroups;
    private final ExternalActionLedgerService externalActions;
    private final AgentScopeAsyncTaskService asyncTasks;
    private final AgentScopeMessageBus messageBus;
    private final DataSource dataSource;
    private final AgentAssetMapper assetMapper;
    private final AgentSkillRepository skillRepository;
    private final GlobalPromptRepository globalPromptRepository;
    private final NacosMcpMetadataRepository mcpMetadataRepository;
    private final FileStorageService fileStorageService;
    private final com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService workflowService;
    private final ProjectCreativeContextService creativeContext;
    private final ConstraintExtractionService constraintExtraction;
    private final AgentDocumentService documentService;
    private final CreativeModelRouter modelRouter;
    private final TurnCapabilityReporter turnCapabilityReporter;
    private final AgentConcurrencyGuard concurrencyGuard;

    @Autowired(required = false)
    private com.moon.moonagent.ai.agent.agentscope.sandbox.MoonSandboxFilesystemSpec
            moonSandboxFilesystemSpec;

    @Autowired(required = false)
    private com.moon.moonagent.ai.agent.agentscope.sandbox.MoonArtifactDeliveryTarget
            moonArtifactDeliveryTarget;

    @Autowired(required = false)
    private ToolCallContentMiddleware toolCallContentMiddleware;

    @Autowired(required = false)
    private FrameworkToolAuditMiddleware frameworkToolAuditMiddleware;

    @Value("${storyboard.asset-client.base-url:http://localhost:8085}")
    private String assetServiceBaseUrl;

    @Autowired(required = false)
    @Qualifier("attachmentHttpClient")
    private HttpClient attachmentHttpClient;

    private volatile HarnessAgent agent;
    private volatile AgentStateStore stateStore;
    /** 全局系统提示词原文，用于上下文组成估算（不随事件下发）。 */
    private volatile String systemPromptText = "";

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            log.info("[AgentScope] media runtime disabled");
            return;
        }
        try {
            Toolkit toolkit = toolkitFactory.create();
            Model model = modelProvider.primary();
            Model fallbackModel = modelProvider.fallback();
            PostgresDistributedStore distributedStore = properties.isDistributedStoreEnabled()
                    ? PostgresDistributedStore.create(dataSource) : null;
            messageBus.bind(distributedStore);
            stateStore = distributedStore != null
                    ? distributedStore.agentStateStore()
                    : new PostgresAgentStateStore(
                    dataSource, properties.getStateSchema(), properties.getStateTable(), false);
            Path workspace = resolveWorkspace();
            try {
                Files.createDirectories(workspace);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Unable to create AgentScope workspace: " + workspace, e);
            }
            List<io.agentscope.core.middleware.MiddlewareBase> middlewares =
                    new ArrayList<>(List.of(
                            new TurnContextMiddleware(
                                    workflowService, creativeContext, constraintExtraction,
                                    documentService, turnCapabilityReporter),
                            toolCallContentMiddleware != null
                                    ? toolCallContentMiddleware
                                    : new ToolCallContentMiddleware(),
                            new AgentTraceBridgeMiddleware()));
            if (frameworkToolAuditMiddleware != null) {
                middlewares.add(frameworkToolAuditMiddleware);
            }
            if (properties.isOtelTracingEnabled()) {
                middlewares.add(new OtelTracingMiddleware());
            }
            String systemPrompt = globalPromptRepository.getPrompt(java.util.Map.of());
            this.systemPromptText = systemPrompt == null ? "" : systemPrompt;
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name("moon-agent")
                    .sysPrompt(systemPrompt)
                    .model(model)
                    .fallbackModel(fallbackModel)
                    .toolkit(toolkit)
                    .maxIters(properties.getMaxIters())
                    .maxRetries(properties.getMaxRetries())
                    .modelResolver(modelProvider::resolveNamed)
                    .skillRepository(skillRepository)
                    .skillFilter(SkillFilter.only(
                            "image", "video", "storyboard", "canvas-design",
                            "canvas-run", "general"))
                    .enableMetaTool(true)
                    .enablePendingToolRecovery(true)
                    .permissionContext(permissionContext(
                            AgentApprovalMode.PLAN_APPROVAL, Set.of(),
                            readOnlyToolNames(toolkit)))
                    .middlewares(middlewares)
                    .workspace(workspace)
                    .disableMemoryTools()
                    .disableMemoryHooks()
                    .disableTranscript()
                    .disableWorkspaceContext()
                    .disableAtPathExpansion()
                    .disableToolsConfig()
                    .disableDefaultWorkspaceSkills()
                    .disableDynamicSubagents();
            builder.stateStore(stateStore);
            if (distributedStore != null) {
                builder.distributedStore(distributedStore);
            }
            if (moonSandboxFilesystemSpec != null) {
                builder.filesystem(moonSandboxFilesystemSpec);
            } else {
                builder.disableFilesystemTools().disableShellTool();
            }
            if (moonArtifactDeliveryTarget != null) {
                builder.artifactDeliveryTarget(moonArtifactDeliveryTarget);
            }
            builder.disableWebTools();
            if (properties.isPlanModeEnabled()) {
                builder.enablePlanMode(true)
                        .planFileDirectory(properties.getPlanFileDirectory());
            } else {
                builder.enablePlanMode(false);
            }
            if (properties.isTaskListEnabled()) {
                builder.enableTaskList(true);
            }
            if (properties.isToolResultEvictionEnabled()) {
                builder.toolResultEviction(ToolResultEvictionConfig.builder()
                        .maxResultChars(properties.getToolResultEvictionMaxChars())
                        .previewChars(properties.getToolResultEvictionPreviewChars())
                        .evictionPath(".agentscope/tool-results")
                        .build());
            } else {
                builder.disableToolResultEviction();
            }
            if (messageBus.enabled()) {
                builder.messageBus(messageBus.messageBus());
            }
            if (properties.isCompactionEnabled()) {
                builder.compaction(CompactionConfig.builder()
                        .triggerMessages(properties.getCompactionTriggerMessages())
                        .triggerTokens(properties.getCompactionTriggerTokens())
                        .keepMessages(properties.getCompactionKeepMessages())
                        .keepTokens(properties.getCompactionKeepTokens())
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .summaryPrompt(COMPACTION_PROMPT)
                        .build());
            } else {
                builder.disableCompaction();
            }
            if (properties.isSubagentsEnabled()) {
                builder.subagents(AgentScopeSubagents.readOnlyExecutors(
                        modelRouter.resolveOrNull("creative.prompt"),
                        modelRouter.resolveOrNull("creative.critic"),
                        modelRouter.resolveOrNull("vision.qa")));
            } else {
                builder.disableSubagents();
            }
            if (properties.isAsyncEnabled()) {
                builder.asyncToolTimeout(properties.getAsyncToolTimeout());
            }
            agent = builder.build();
            log.info("[AgentScope] Harness media runtime ready model={}", model.getModelName());
        } catch (Exception e) {
            throw new IllegalStateException("AgentScope media runtime initialization failed", e);
        }
    }

    public boolean enabled() {
        return properties.isEnabled() && agent != null;
    }

    /** 本轮主模型名，供上下文窗口解析使用。 */
    public String primaryModelName() {
        return modelProvider.primary().getModelName();
    }

    /** Authoritative context window from the AgentScope model instance. */
    public int contextWindowTokens() {
        Model current = agent != null && agent.getDelegate() != null
                ? agent.getDelegate().getModel() : null;
        if (current != null && current.getContextWindowSize() > 0) {
            return current.getContextWindowSize();
        }
        return AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS;
    }

    /** Cooperatively interrupt the in-flight AgentScope call for one conversation slot. */
    public boolean interruptTurn(String userId, String conversationId) {
        if (!enabled() || conversationId == null || conversationId.isBlank()) return false;
        agent.interrupt(userId, conversationId);
        return true;
    }

    /**
     * Seeds a previously unseen AgentScope session with the product message history.
     * Existing runtime state always wins, so this is a one-time migration path for
     * conversations created before the AgentScope cutover.
     */
    public SessionBootstrapResult ensureSessionFromHistory(String userId, String conversationId,
                                                           List<Msg> history) {
        if (!enabled() || conversationId == null || conversationId.isBlank()
                || history == null || history.isEmpty()) {
            return enabled() ? SessionBootstrapResult.UNAVAILABLE
                    : SessionBootstrapResult.DISABLED;
        }
        try {
            AgentState state = agent.getDelegate().getAgentState(userId, conversationId);
            if (state != null && !state.contextMutable().isEmpty()) {
                return SessionBootstrapResult.ALREADY_INITIALIZED;
            }
            if (state == null) {
                return SessionBootstrapResult.UNAVAILABLE;
            }
            state.contextMutable().addAll(history);
            agent.getDelegate().saveAgentState(userId, conversationId);
            log.info("[AgentScope] reconstructed session from {} product messages conversationId={}",
                    history.size(), conversationId);
            return SessionBootstrapResult.SEEDED;
        } catch (Exception exception) {
            log.warn("[AgentScope] session reconstruction failed conversationId={}: {}",
                    conversationId, exception.getMessage());
            return SessionBootstrapResult.UNAVAILABLE;
        }
    }

    /**
     * Clones the last committed AgentScope state into an independent ephemeral session.
     * Transient plan/tool/task state is intentionally not copied: a side conversation
     * branches from the stable dialogue context, not from an in-flight execution.
     */
    public int cloneSessionState(String userId, String sourceConversationId,
                                 String targetConversationId, List<Msg> fallbackHistory) {
        if (!enabled() || stateStore == null) {
            throw new IllegalStateException("AgentScope state store is unavailable");
        }
        if (sourceConversationId == null || sourceConversationId.isBlank()
                || targetConversationId == null || targetConversationId.isBlank()) {
            throw new IllegalArgumentException("source and target conversation ids are required");
        }

        AgentState sourceState = null;
        var versioned = stateStore.getVersioned(
                userId, sourceConversationId, "agent_state", AgentState.class);
        if (versioned != null && versioned.isPresent()) {
            sourceState = versioned.value();
        }

        AgentState targetState;
        if (sourceState == null || sourceState.getContext() == null
                || sourceState.getContext().isEmpty()) {
            List<Msg> seeded = fallbackHistory == null ? List.of() : List.copyOf(fallbackHistory);
            targetState = AgentState.builder()
                    .sessionId(targetConversationId)
                    .userId(userId)
                    .context(seeded)
                    .build();
        } else {
            targetState = cloneStableState(sourceState, targetConversationId, userId);
        }

        agent.clearStateCache(userId, targetConversationId);
        stateStore.save(userId, targetConversationId, "agent_state", targetState);
        log.info("[AgentScope] cloned side-chat state source={} target={} messages={}",
                sourceConversationId, targetConversationId, targetState.getContext().size());
        return targetState.getContext().size();
    }

    static AgentState cloneStableState(AgentState source, String targetConversationId,
                                       String userId) {
        AgentState.Builder builder = AgentState.builder()
                .sessionId(targetConversationId)
                .userId(userId)
                .summary(source.getSummary())
                .context(source.getContext() == null ? List.of() : List.copyOf(source.getContext()));
        if (source.getPermissionContext() != null) {
            builder.permissionContext(source.getPermissionContext());
        }
        return builder.build();
    }

    public enum SessionBootstrapResult {
        SEEDED,
        ALREADY_INITIALIZED,
        UNAVAILABLE,
        DISABLED
    }

    public Map<String, Object> assetStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("skills", repositorySource(skillRepository));
        status.put("globalPrompt", globalPromptRepository.selectedSource());
        try {
            status.put("mcpMetadata", mcpMetadataRepository.status());
        } catch (RuntimeException exception) {
            status.put("mcpMetadata", Map.of(
                    "source", "unavailable",
                    "error", exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage()));
        }
        return status;
    }

    public AgentTurnResult runTurn(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace) {
        return runTurn(conversation, request, trace, null);
    }

    public AgentTurnResult runTurn(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace, Consumer<AgentEvent> observer) {
        if (!enabled()) return null;
        String input = request.getContent() == null ? "" : request.getContent();
        String taskContext = asyncTasks.consumeUnreadContext(conversation.getId());
        if (!taskContext.isBlank()) {
            input = taskContext + "\n\n本轮用户消息：\n" + input;
        }
        Msg message = buildUserMessage(input, request.getPicUrls());
        return guarded(conversation, () ->
                stream(conversation, request, trace, List.of(message), null, observer));
    }

    private AgentTurnResult guarded(AgentConversation conversation, Supplier<AgentTurnResult> action) {
        return concurrencyGuard == null
                ? action.get()
                : concurrencyGuard.call(conversation.getUserId(), action);
    }

    private Msg buildUserMessage(String input, List<String> imageUrls) {
        List<String> urls = imageUrls == null ? List.of() : imageUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        if (urls.isEmpty()) return UserMessage.builder().textContent(input).build();

        List<ContentBlock> content = new ArrayList<>();
        content.add(TextBlock.builder().text(input).build());
        int attached = 0;
        for (String url : urls) {
            try {
                AttachmentBytes image = readAttachmentImage(url);
                if (image == null) continue;
                content.add(ImageBlock.builder()
                        .source(Base64Source.builder()
                                .mediaType(image.mimeType())
                                .data(Base64.getEncoder().encodeToString(image.bytes()))
                                .build())
                        .build());
                attached++;
            } catch (Exception error) {
                log.warn("[AgentScope] failed to attach image url={}: {}", url, error.getMessage());
            }
        }
        if (attached > 0) {
            content.set(0, TextBlock.builder()
                    .text("本轮用户附加了 " + attached + " 张图片，请直接查看图片内容，不要调用 web_fetch。\n\n" + input)
                    .build());
        } else {
            content.add(TextBlock.builder()
                    .text("\n\n本轮附带图片链接（服务端未能转为图片内容）：" + urls)
                    .build());
        }
        return UserMessage.builder().content(content).build();
    }

    private AttachmentBytes readAttachmentImage(String url) throws Exception {
        String value = url.trim();
        if (value.startsWith("data:image/")) {
            int comma = value.indexOf(',');
            int semicolon = value.indexOf(';');
            if (comma < 0 || semicolon < 0 || semicolon > comma) return null;
            return new AttachmentBytes(
                    value.substring(5, semicolon),
                    Base64.getDecoder().decode(value.substring(comma + 1)));
        }
        if (value.startsWith("/api/files/")) {
            String filename = value.substring(value.lastIndexOf('/') + 1);
            byte[] bytes = value.startsWith("/api/files/images/")
                    ? fileStorageService.readImage(filename)
                    : value.startsWith("/api/files/files/")
                    ? fileStorageService.readFile(filename)
                    : null;
            return imageBytes(bytes, filename);
        }
        String absolute = value.startsWith("/") && assetServiceBaseUrl != null
                ? assetServiceBaseUrl.replaceAll("/+$", "") + value
                : value;
        if (!absolute.startsWith("http://") && !absolute.startsWith("https://")) return null;
        OutboundUrlGuard.requireSafeHttpUrl(absolute);
        HttpResponse<byte[]> response = attachmentHttpClient.send(
                HttpRequest.newBuilder(URI.create(absolute))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
        return imageBytes(response.body(), absolute);
    }

    private static AttachmentBytes imageBytes(byte[] bytes, String source) {
        if (bytes == null || bytes.length == 0 || bytes.length > 20 * 1024 * 1024) return null;
        String mime = detectImageMime(bytes);
        if (mime == null) {
            String lower = source == null ? "" : source.toLowerCase();
            if (lower.endsWith(".png")) mime = "image/png";
            else if (lower.endsWith(".webp")) mime = "image/webp";
            else if (lower.endsWith(".gif")) mime = "image/gif";
            else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mime = "image/jpeg";
        }
        return mime == null ? null : new AttachmentBytes(mime, bytes);
    }

    private static String detectImageMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F') return "image/webp";
        return null;
    }

    private record AttachmentBytes(String mimeType, byte[] bytes) {
    }

    public AgentTurnResult runWakeup(AgentConversation conversation) {
        if (!enabled()) return null;
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(
                request, contextWindowTokens());
        request.setTrace(trace);
        return stream(conversation, request, trace, List.of(), null);
    }

    public AgentTurnResult resumeTurn(AgentConversation conversation, AgentCheckpoint checkpoint,
                                      OrchestrationRequest request, AgentTraceAccumulator trace,
                                      String action, String customText) {
        return resumeTurn(conversation, checkpoint, request, trace, action, customText, null);
    }

    public AgentTurnResult resumeTurn(AgentConversation conversation, AgentCheckpoint checkpoint,
                                      OrchestrationRequest request, AgentTraceAccumulator trace,
                                      String action, String customText,
                                      Consumer<AgentEvent> observer) {
        if (!enabled()) return null;
        if (checkpoint.getRuntimeSessionId() != null
                && !checkpoint.getRuntimeSessionId().equals(conversation.getId())) {
            throw new IllegalStateException("AgentScope 会话状态不匹配，请重新发起");
        }
        AgentScopeCheckpointService.ResumeValue value =
                checkpoints.resolve(checkpoint, action, customText);
        if ("permission".equals(value.kind())) {
            List<ConfirmResult> results = pendingToolCalls(checkpoint).stream()
                    .map(call -> new ConfirmResult(value.approved(), call))
                    .toList();
            Set<String> approvedTools = value.approved()
                    && AgentApprovalMode.PLAN_APPROVAL == AgentApprovalMode.from(
                    conversation.getApprovalMode())
                    ? pendingToolCalls(checkpoint).stream().map(ToolUseBlock::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet())
                    : Set.of();
            Msg resume = Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(value.text())
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                    .build();
            return guarded(conversation, () ->
                    stream(conversation, request, trace, List.of(resume),
                            checkpoint.getId(), observer, approvedTools));
        }
        return guarded(conversation, () ->
                stream(conversation, request, trace,
                        List.of(resumeMessage(value)), checkpoint.getId(), observer));
    }

    static Msg resumeMessage(AgentScopeCheckpointService.ResumeValue value) {
        ToolResultBlock result = ToolResultBlock.of(
                value.toolCallId(), value.toolName(),
                TextBlock.builder().text(value.text()).build());
        return Msg.builder()
                .name(value.toolName())
                .role(MsgRole.TOOL)
                .content(result)
                .build();
    }

    private AgentTurnResult stream(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace, List<Msg> messages) {
        return stream(conversation, request, trace, messages, null, null);
    }

    private AgentTurnResult stream(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace, List<Msg> messages,
                                   String checkpointId) {
        return stream(conversation, request, trace, messages, checkpointId, null);
    }

    private AgentTurnResult stream(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace, List<Msg> messages,
                                   String checkpointId, Consumer<AgentEvent> observer) {
        return stream(conversation, request, trace, messages, checkpointId, observer, Set.of());
    }

    private AgentTurnResult stream(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace, List<Msg> messages,
                                   String checkpointId, Consumer<AgentEvent> observer,
                                   Set<String> preApprovedTools) {
        boolean ignoreGenerationPlan = request != null
                && request.getGenerationPreferences().ignoreGenerationPlan();
        applyPermissionContext(conversation, preApprovedTools, ignoreGenerationPlan);
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        boolean hasBoundAssetGroups = assetGroups.hasBoundGroups(conversation);
        turnState.setReferenceInputMode(classifyReferenceInput(request, hasBoundAssetGroups));
        RuntimeContext.Builder runtimeBuilder = RuntimeContext.builder()
                .userId(conversation.getUserId())
                .sessionId(conversation.getId())
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(conversation, request, support, turnState,
                                checkpointId, externalActions));
        if (isCanvasEditTurn(request)) {
            // 画布回合只保留画布链路与生图/生视频：分镜技能不在本回合加载。
            runtimeBuilder.put(SkillFilter.class, SkillFilter.disable(STORYBOARD_SKILL));
        }
        if (moonSandboxFilesystemSpec != null) {
            runtimeBuilder.put(SandboxContext.class,
                    moonSandboxFilesystemSpec.contextForConversation(conversation.getId()));
        }
        RuntimeContext runtime = runtimeBuilder.build();
        // 上下文组成估算：系统提示词 + 工具定义（对话消息量在快照时取余量）
        trace.setContextBreakdown(
                ContextTokenEstimator.estimateTokens(systemPromptText),
                estimateToolDefinitionTokens());
        StringBuilder finalText = new StringBuilder();
        StringBuilder visibleText = new StringBuilder();
        AgentTaskClaimGuard claimGuard = new AgentTaskClaimGuard();
        ToolCallStreamGuard textFilter = new ToolCallStreamGuard();
        String resultText = "";
        boolean paused = false;
        boolean loopAborted = false;
        try {
            for (AgentEvent event : agent.streamEvents(messages, runtime).toIterable()) {
                support.checkInterrupted(request);
                if (observer != null) {
                    try {
                        observer.accept(event);
                    } catch (Exception e) {
                        log.warn("[AgentScope] event observer failed: {}", e.getMessage());
                    }
                }
                if (event instanceof ModelCallStartEvent) {
                    trace.beginModelCall();
                } else if (event instanceof ModelCallEndEvent modelEnd) {
                    io.agentscope.core.model.ChatUsage usage = modelEnd.getUsage();
                    if (usage != null) {
                        trace.recordUsage(
                                usage.getInputTokens(),
                                usage.getOutputTokens(),
                                usage.getCachedTokens(),
                                Math.round(Math.max(0.0, usage.getTime()) * 1000.0));
                    }
                } else if (event instanceof ToolResultStartEvent toolStart) {
                    trace.beginToolCall(toolStart.getToolCallId());
                } else if (event instanceof ToolResultEndEvent toolEnd) {
                    trace.endToolCall(toolEnd.getToolCallId());
                } else if (event instanceof TextBlockDeltaEvent delta) {
                    String chunk = delta.getDelta();
                    if (chunk != null && !chunk.isEmpty()) {
                        String visible;
                        try {
                            visible = textFilter.accept(chunk);
                        } catch (ToolCallStreamGuard.ToolCallLoopException loop) {
                            log.warn("[AgentScope] 检测到工具调用文本死循环，中止本轮: conversationId={}, detail={}",
                                    conversation.getId(), loop.getMessage());
                            loopAborted = true;
                            break;
                        }
                        if (!visible.isEmpty()) {
                            finalText.append(visible);
                            String safe = claimGuard.accept(visible);
                            if (!safe.isEmpty()) {
                                visibleText.append(safe);
                                support.sendMessage(request, safe);
                            }
                        }
                    }
                } else if (event instanceof AgentResultEvent result) {
                    resultText = result.getResult() == null
                            ? "" : result.getResult().getTextContent();
                } else if (event instanceof RequireExternalExecutionEvent external) {
                    ToolUseBlock prepareCanvas = external.getToolCalls().stream()
                            .filter(call -> AgentScopeCanvasTools.PREPARE_CANVAS_SESSION
                                    .equals(call.getName()))
                            .findFirst()
                            .orElse(null);
                    if (prepareCanvas != null) {
                        Map<String, Object> input = prepareCanvas.getInput() == null
                                ? Map.of() : prepareCanvas.getInput();
                        AgentScopeCanvasTools.SessionPreparation preparation =
                                canvasTools.prepare(conversation, input);
                        AgentCanvasSessionService.CanvasSessionView session =
                                canvasSessions.create(
                                        conversation,
                                        request.getTurnId(),
                                        external.getReplyId(),
                                        prepareCanvas.getId(),
                                        preparation,
                                        canvasMediaRefs(request));
                        Map<String, Object> metadata = new LinkedHashMap<>(request.getTurnMetadata());
                        metadata.put("canvasSession", session.toMap());
                        request.setTurnMetadata(metadata);
                        paused = true;
                        continue;
                    }
                    ToolUseBlock human = external.getToolCalls().stream()
                            .filter(call -> AgentScopeInteractionTools.REQUEST_HUMAN_INPUT
                                    .equals(call.getName()))
                            .findFirst()
                            .orElse(null);
                    if (human != null) {
                        Integer pendingStoryboardCommit =
                                turnState.takePendingStoryboardCommitCount();
                        if (pendingStoryboardCommit != null) {
                            checkpoints.pauseForStoryboardCommit(
                                    request,
                                    external.getReplyId(),
                                    human,
                                    pendingStoryboardCommit);
                        } else {
                            checkpoints.pauseForExternalInput(request, external.getReplyId(), human);
                        }
                    } else {
                        ToolUseBlock commit = external.getToolCalls().stream()
                                .filter(call -> AgentScopeInteractionTools.CHOOSE_STORYBOARD_COMMIT
                                        .equals(call.getName()))
                                .findFirst()
                                .orElse(null);
                        if (commit != null) {
                            turnState.takePendingStoryboardCommitCount();
                            Map<String, Object> input =
                                    commit.getInput() == null ? Map.of() : commit.getInput();
                            int existing = integer(input.get("existing_scene_count"), 0);
                            checkpoints.pauseForStoryboardCommit(
                                    request, external.getReplyId(), commit, existing);
                        } else {
                            checkpoints.pauseForConfirmation(
                                    request, external.getReplyId(), external.getToolCalls());
                        }
                    }
                    paused = true;
                    continue;
                } else if (event instanceof RequireUserConfirmEvent confirm) {
                    checkpoints.pauseForConfirmation(
                            request, confirm.getReplyId(), confirm.getToolCalls());
                    paused = true;
                    continue;
                }
            }
        } catch (RuntimeException e) {
            log.error("[AgentScope] run failed conversationId={}", conversation.getId(), e);
            throw e;
        }
        String flushed = textFilter.finish();
        if (!flushed.isEmpty()) {
            finalText.append(flushed);
            String safe = claimGuard.accept(flushed);
            if (!safe.isEmpty()) {
                visibleText.append(safe);
                support.sendMessage(request, safe);
            }
        }
        if (loopAborted) {
            support.sendFriendlyError(request, "", "模型的这条回复陷入了重复调用，已中止。请重试或换个说法再问一次。");
            return turnResult(request, trace, "模型的这条回复陷入了重复调用，已中止。", paused);
        }
        if (resultText != null && !resultText.isBlank()) {
            String complete = ToolCallStreamGuard.sanitizeComplete(resultText);
            finalText.setLength(0);
            finalText.append(complete);
        }
        if (!paused && claimGuard.hasClaim() && !turnState.hasTaskIds()) {
            String correction = runTaskClaimCorrection(conversation, request, trace, runtime, turnState);
            if (turnState.hasTaskIds()) {
                String held = claimGuard.flushAll();
                if (!held.isBlank()) {
                    visibleText.append(held);
                    support.sendMessage(request, held);
                }
                if (correction != null && !correction.isBlank()) {
                    visibleText.append(correction);
                    support.sendMessage(request, correction);
                }
            } else {
                String failure = claimGuard.reject();
                visibleText.append(failure);
                support.sendMessage(request, failure);
                if (request != null) {
                    Map<String, Object> metadata = new LinkedHashMap<>(request.getTurnMetadata());
                    metadata.put("taskContractViolation", true);
                    request.setTurnMetadata(metadata);
                }
            }
        } else {
            String held = claimGuard.flushAll();
            if (!held.isBlank()) {
                visibleText.append(held);
                support.sendMessage(request, held);
            }
        }
        String modelText = visibleText.toString();
        String content = turnState.compose(modelText);
        List<Map<String, Object>> taskCards = turnState.taskCards();
        if (!taskCards.isEmpty() && request != null) {
            Map<String, Object> metadata = new LinkedHashMap<>(request.getTurnMetadata());
            metadata.put("taskCards", taskCards);
            request.setTurnMetadata(metadata);
        }
        attachDeliveredSandboxAssets(request, turnState);
        return turnResult(request, trace, content, paused);
    }

    /**
     * Backfills {@code metadata.sandboxAttachments} for files the agent delivered explicitly via
     * the official {@code deliver_artifact} tool. The turn-end reconciler merges its own scan on
     * top, so files under {@code output/} still get picked up without duplication.
     */
    private void attachDeliveredSandboxAssets(OrchestrationRequest request,
                                              AgentScopeTurnState turnState) {
        if (request == null || turnState == null) return;
        Set<String> assetIds = turnState.deliveredAssetIds();
        if (assetIds.isEmpty()) return;
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (String assetId : assetIds) {
            AgentAsset asset = assetMapper.selectById(assetId);
            if (asset == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", asset.getId());
            item.put("url", asset.getUrl() == null ? "" : asset.getUrl());
            item.put("name", asset.getOriginalName() == null ? "生成文件" : asset.getOriginalName());
            item.put("mimeType", asset.getMimeType() == null
                    ? "application/octet-stream" : asset.getMimeType());
            item.put("kind", SandboxArtifactPublisher.attachmentKind(asset.getMimeType()));
            item.put("source", "upload");
            attachments.add(item);
        }
        if (attachments.isEmpty()) return;
        Map<String, Object> metadata = new LinkedHashMap<>(request.getTurnMetadata());
        metadata.put("sandboxAttachments", attachments);
        request.setTurnMetadata(metadata);
    }

    public AgentTurnResult resumeCanvasTurn(AgentConversation conversation,
                                            AgentCanvasSessionService.CanvasSessionView session,
                                            OrchestrationRequest request,
                                            AgentTraceAccumulator trace,
                                            Consumer<AgentEvent> observer) {
        ToolResultBlock result = ToolResultBlock.of(
                session.runtimeToolCallId(),
                AgentScopeCanvasTools.PREPARE_CANVAS_SESSION,
                TextBlock.builder().text(String.valueOf(session.toMap())).build());
        Msg resume = Msg.builder()
                .name(AgentScopeCanvasTools.PREPARE_CANVAS_SESSION)
                .role(MsgRole.TOOL)
                .content(result)
                .build();
        return guarded(conversation, () ->
                stream(conversation, request, trace, List.of(resume), null, observer));
    }

    /** 画布编辑回合：分镜技能与分镜工具不参与本回合。 */
    static boolean isCanvasEditTurn(OrchestrationRequest request) {
        if (request == null) return false;
        CanvasSessionContext canvas = request.getCanvasContext();
        return canvas != null && canvas.isCanvasEditMode();
    }

    private static List<Map<String, Object>> canvasMediaRefs(OrchestrationRequest request) {
        if (request == null) return List.of();
        if (request.getCanvasContext() != null
                && !request.getCanvasContext().normalizedMediaRefs().isEmpty()) {
            return AgentCanvasSessionService.mediaRefMaps(
                    request.getCanvasContext().normalizedMediaRefs());
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        if (request.getPicUrls() != null) {
            for (String url : request.getPicUrls()) {
                if (url == null || url.isBlank()) continue;
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("kind", "image");
                ref.put("url", url);
                ref.put("source", "upload");
                refs.add(ref);
            }
        }
        if (request.getAssetIds() != null) {
            for (String assetId : request.getAssetIds()) {
                if (assetId == null || assetId.isBlank()) continue;
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("kind", "file");
                ref.put("assetId", assetId);
                ref.put("source", "project");
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * One bounded correction round for a model claiming an async task without
     * producing a taskId. The correction text is not streamed until the task
     * contract is verified.
     */
    private String runTaskClaimCorrection(AgentConversation conversation,
                                          OrchestrationRequest request,
                                          AgentTraceAccumulator trace,
                                          RuntimeContext runtime,
                                          AgentScopeTurnState turnState) {
        String prompt = """
                内部校验：你刚才声称任务已经提交或正在执行，但本轮没有创建任何后台任务。
                如果任务确实尚未创建，请立即调用正确的异步工具；如果当前无法执行，
                只回答“任务未创建，请重试。”，不得继续说已经提交或正在执行。
                """;
        Msg correction = UserMessage.builder().textContent(prompt).build();
        StringBuilder output = new StringBuilder();
        try {
            for (AgentEvent event : agent.streamEvents(List.of(correction), runtime).toIterable()) {
                support.checkInterrupted(request);
                if (event instanceof ModelCallStartEvent) {
                    trace.beginModelCall();
                } else if (event instanceof ModelCallEndEvent modelEnd) {
                    io.agentscope.core.model.ChatUsage usage = modelEnd.getUsage();
                    if (usage != null) {
                        trace.recordUsage(
                                usage.getInputTokens(),
                                usage.getOutputTokens(),
                                usage.getCachedTokens(),
                                Math.round(Math.max(0.0, usage.getTime()) * 1000.0));
                    }
                } else if (event instanceof ToolResultStartEvent toolStart) {
                    trace.beginToolCall(toolStart.getToolCallId());
                } else if (event instanceof ToolResultEndEvent toolEnd) {
                    trace.endToolCall(toolEnd.getToolCallId());
                } else if (event instanceof TextBlockDeltaEvent delta) {
                    String chunk = delta.getDelta();
                    if (chunk != null && !chunk.isEmpty()) output.append(chunk);
                } else if (event instanceof AgentResultEvent result
                        && output.isEmpty() && result.getResult() != null) {
                    output.append(result.getResult().getTextContent());
                } else if (event instanceof RequireExternalExecutionEvent
                        || event instanceof RequireUserConfirmEvent) {
                    break;
                }
            }
        } catch (Exception exception) {
            log.warn("[AgentScope] task contract correction failed conversationId={}: {}",
                    conversation.getId(), exception.getMessage());
        }
        return output.toString().trim();
    }

    void applyPermissionContext(AgentConversation conversation) {
        applyPermissionContext(conversation, Set.of());
    }

    void applyPermissionContext(AgentConversation conversation, Set<String> preApprovedTools) {
        applyPermissionContext(conversation, preApprovedTools, false);
    }

    /**
     * @param autoApproveMediaGeneration 用户在本轮打开「忽略对话中的生成方案」：
     *                                   媒体生成类工具直接放行，其他高风险工具照旧确认。
     */
    void applyPermissionContext(AgentConversation conversation, Set<String> preApprovedTools,
                                boolean autoApproveMediaGeneration) {
        if (agent == null || conversation == null) return;
        AgentApprovalMode mode = AgentApprovalMode.from(conversation.getApprovalMode());
        agent.getDelegate().replacePermissionContext(
                conversation.getUserId(), conversation.getId(),
                permissionContext(mode, preApprovedTools,
                        readOnlyToolNames(toolkitFactory.activeToolkit()),
                        autoApproveMediaGeneration));
    }

    static PermissionContextState permissionContext(AgentApprovalMode mode) {
        return permissionContext(mode, Set.of());
    }

    static PermissionContextState permissionContext(AgentApprovalMode mode,
                                                    Set<String> preApprovedTools) {
        return permissionContext(mode, preApprovedTools, Set.of());
    }

    static PermissionContextState permissionContext(AgentApprovalMode mode,
                                                    Set<String> preApprovedTools,
                                                    Set<String> readOnlyTools) {
        return permissionContext(mode, preApprovedTools, readOnlyTools, false);
    }

    static PermissionContextState permissionContext(AgentApprovalMode mode,
                                                    Set<String> preApprovedTools,
                                                    Set<String> readOnlyTools,
                                                    boolean autoApproveMediaGeneration) {
        AgentApprovalMode effective = mode == null ? AgentApprovalMode.PLAN_APPROVAL : mode;
        Set<String> approved = preApprovedTools == null ? Set.of() : preApprovedTools;
        PermissionContextState.Builder permissions = PermissionContextState.builder()
                .mode(effective == AgentApprovalMode.FULL_ACCESS
                        ? PermissionMode.BYPASS : PermissionMode.DEFAULT);
        if (effective == AgentApprovalMode.FULL_ACCESS) {
            return permissions.build();
        }
        if (readOnlyTools != null) {
            for (String tool : readOnlyTools) {
                if (tool == null || tool.isBlank()) continue;
                permissions.addAllowRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ALLOW, "MoonAgentRegisteredReadOnlyPolicy"));
            }
        }
        for (String tool : List.of(
                "list_session_assets",
                "resolve_reference_assets",
                "get_generation_task_status",
                "list_conversation_tasks",
                "get_agent_task",
                "list_asset_groups",
                "build_media_spec",
                "assess_generation_risk",
                "task_list",
                "task_output",
                "wait_async_results",
                "agent_list",
                "agent_spawn",
                "agent_send",
                "list_storyboard_draft",
                "list_project_storyboard",
                "resolve_agent_workflow",
                "canvas_get_context",
                "canvas_get_browser_context",
                "canvas_get_summary",
                "canvas_list_node_types",
                "canvas_get_state",
                "canvas_list_nodes",
                "canvas_get_node",
                "canvas_list_edges",
                "canvas_get_selection",
                "canvas_get_node_model_config",
                "canvas_get_execution",
                "canvas_graph_get_context",
                "canvas_graph_get_state",
                "canvas_graph_list_nodes",
                "canvas_graph_get_node",
                "canvas_graph_list_edges",
                "canvas_graph_list_node_types",
                "load_skill_through_path",
                "reset_equipped_tools")) {
            permissions.addAllowRule(tool, new PermissionRule(
                    tool, null, PermissionBehavior.ALLOW, "MoonAgentReadOnlyPolicy"));
        }
        // 编排入口与交互工具直接放行：启动剧情/分镜本身不是高风险操作，
        // 真正的破坏性动作由「追加/覆盖/删除」提交方式选择卡片单独确认；
        // request_human_input 的作用就是询问用户，不应再叠加一次权限确认。
        for (String tool : List.of(
                "start_storyboard_production",
                "choose_storyboard_commit_mode",
                AgentScopeCanvasTools.PREPARE_CANVAS_SESSION,
                AgentScopeCanvasRunTools.CREATE_CANVAS_RUN,
                "canvas_graph_create_node",
                "canvas_graph_create_nodes",
                "canvas_graph_update_node",
                "canvas_graph_create_edge",
                "canvas_graph_import_media",
                "canvas_graph_import_media_batch",
                "canvas_graph_create_result_slots",
                "canvas_graph_mark_result_slots",
                "canvas_graph_layout_graph",
                "canvas_create_canvas",
                "canvas_rename_canvas",
                "canvas_update_node",
                "canvas_move_nodes",
                "canvas_duplicate_nodes",
                "canvas_resize_nodes",
                "canvas_pin_nodes",
                "canvas_set_node_model_config",
                "canvas_undo",
                "canvas_redo",
                "canvas_image_crop",
                "canvas_image_split",
                "canvas_image_adjust",
                "canvas_execute_node",
                "canvas_execute_graph",
                "canvas_cancel_execution",
                "reorder_storyboard_draft",
                "save_timeline_manifest",
                "update_creative_context",
                "todo_write",
                "plan_enter",
                "plan_write",
                "plan_exit",
                "request_human_input")) {
            permissions.addAllowRule(tool, new PermissionRule(
                    tool, null, PermissionBehavior.ALLOW, "MoonAgentAutoApprovePolicy"));
        }
        for (String tool : List.of(
                "generate_image",
                "generate_image_batch",
                "refineImage",
                "generate_video",
                "generate_storyboard_image",
                "generate_storyboard_video",
                "generate_storyboard_videos",
                "generate_storyboard_images",
                "update_project_storyboard_shot",
                "delete_storyboard_draft_shot",
                "delete_project_storyboard_shot",
                "clear_project_storyboard",
                "canvas_delete_nodes",
                "canvas_delete_edges",
                "merge_storyboard_videos",
                "write_file",
                "run_python_script",
                "task_cancel")) {
            boolean autoApproved = effective == AgentApprovalMode.PLAN_APPROVAL
                    && approved.contains(tool);
            // 「忽略对话中的生成方案」：媒体生成类工具直接放行（仅覆盖生图/生视频/生镜）
            boolean mediaExempt = autoApproveMediaGeneration
                    && AgentGenerationPreferences.isMediaGenerationTool(tool);
            if (autoApproved) {
                permissions.addAllowRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ALLOW, "MoonAgentApprovedPlanPolicy"));
            } else if (mediaExempt) {
                permissions.addAllowRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ALLOW, "MoonAgentIgnoreGenerationPlanPolicy"));
            } else {
                permissions.addAskRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ASK, "MoonAgentHighRiskPolicy"));
            }
        }
        return permissions.build();
    }

    private static Set<String> readOnlyToolNames(Toolkit toolkit) {
        if (toolkit == null || toolkit.getToolNames() == null) return Set.of();
        return toolkit.getToolNames().stream()
                .filter(name -> {
                    var tool = toolkit.getTool(name);
                    return tool != null && tool.isReadOnly();
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 当前 Toolkit 的工具定义估算 token：把所有已注册工具的名称 / 描述 / 参数 schema
     * 拼成文本后按字符估算。仅用于「上下文组成」三色明细，不做严格计量。
     */
    private long estimateToolDefinitionTokens() {
        try {
            Toolkit toolkit = toolkitFactory.activeToolkit();
            if (toolkit == null || toolkit.getToolNames() == null) return 0L;
            StringBuilder text = new StringBuilder();
            for (String name : toolkit.getToolNames()) {
                text.append(name).append(' ');
                AgentTool tool = toolkit.getTool(name);
                if (tool == null) continue;
                text.append(tool.getName()).append(' ')
                        .append(tool.getDescription() == null ? "" : tool.getDescription())
                        .append(' ');
                java.util.Map<String, Object> parameters = tool.getParameters();
                if (parameters != null) text.append(parameters);
            }
            return ContextTokenEstimator.estimateTokens(text.toString());
        } catch (RuntimeException exception) {
            log.debug("[AgentScope] tool definition estimate failed: {}", exception.getMessage());
            return 0L;
        }
    }

    static AgentScopeTurnState.ReferenceInputMode classifyReferenceInput(
            OrchestrationRequest request, boolean hasBoundAssetGroups) {
        List<String> picUrls = request == null || request.getPicUrls() == null
                ? List.of() : request.getPicUrls();
        List<String> assetIds = request == null || request.getAssetIds() == null
                ? List.of() : request.getAssetIds();
        boolean hasAssetUrls = picUrls.stream()
                .anyMatch(MoonAgentRuntime::isAssetContentUrl);
        boolean hasUploadedImages = picUrls.stream()
                .anyMatch(url -> url != null && !url.isBlank()
                        && !isAssetContentUrl(url));
        boolean hasAssets = hasBoundAssetGroups
                || hasAssetUrls
                || assetIds.stream().anyMatch(id -> id != null && !id.isBlank());
        if (hasUploadedImages && hasAssets) {
            return AgentScopeTurnState.ReferenceInputMode.MIXED;
        }
        if (hasAssets) {
            return AgentScopeTurnState.ReferenceInputMode.ASSET_ONLY;
        }
        if (hasUploadedImages) {
            return AgentScopeTurnState.ReferenceInputMode.UPLOAD_ONLY;
        }
        return AgentScopeTurnState.ReferenceInputMode.NONE;
    }

    private static boolean isAssetContentUrl(String url) {
        return url != null && url.contains("/api/library/media/");
    }

    private static String repositorySource(AgentSkillRepository repository) {
        try {
            return repository.getSource();
        } catch (RuntimeException exception) {
            return "unavailable: " + (exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private AgentTurnResult turnResult(OrchestrationRequest request, AgentTraceAccumulator trace,
                                       String content, boolean hitlPaused) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request != null) metadata.putAll(request.getTurnMetadata());
        if (trace != null) metadata.putAll(trace.getTurnMetadata());
        if (trace != null) {
            metadata.put("metrics", trace.turnMetrics());
            metadata.put("_terminalEventSent", trace.isTerminalEventSent());
        }
        metadata.put("orchestrationMode", "AGENTSCOPE");
        metadata.put("checkpointOwner", "AGENTSCOPE");
        metadata.put("agentRuntime", "agentscope-java-2.0.4");
        List<com.moon.moonagent.ai.agent.AgentTraceEvent> events =
                trace == null ? List.of() : trace.snapshot();
        com.moon.moonagent.ai.agent.AgentReasoningSnapshot reasoning =
                request == null ? null : request.getReasoning();
        if (reasoning == null && trace != null) {
            reasoning = trace.reasoningSnapshot();
        }
        return new AgentTurnResult(
                content == null ? "" : content,
                metadata,
                events,
                reasoning,
                request == null ? List.of() : request.getSuggestedActions(),
                hitlPaused || (request != null && request.isHitlPaused()),
                request == null ? "" : request.getHitlMessageId());
    }

    private List<ToolUseBlock> pendingToolCalls(AgentCheckpoint checkpoint) {
        return checkpoints.permissionToolCalls(checkpoint);
    }

    public void clearSession(String userId, String conversationId) {
        if (!enabled() || conversationId == null || conversationId.isBlank()) return;
        try {
            agent.clearStateCache(userId, conversationId);
            if (stateStore != null) stateStore.delete(userId, conversationId);
        } catch (Exception e) {
            log.warn("[AgentScope] clear session failed conversationId={}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * 手动压缩会话上下文（/chat 斜杠命令「压缩上下文」）。
     *
     * <p>复用 harness 自带的压缩组件 {@link ConversationCompactor} + {@link CompactionConfig}，
     * 与上下文溢出时的 emergency compaction（{@code HarnessAgent.forceCompactAndRetry}）
     * 同一套路：{@code triggerMessages(1)} 强制触发，保留最近 keepMessages 轮。
     *
     * <p>只改 AgentScope 运行时会话上下文，不动产品消息表 agent_messages。
     * 上下文条数不超过保留量时返回 {@link CompactOutcome#SKIPPED}，不报错。
     */
    public CompactOutcome compactSession(String userId, String conversationId) {
        if (!enabled() || conversationId == null || conversationId.isBlank()) {
            return CompactOutcome.UNAVAILABLE;
        }
        try {
            AgentState state = agent.getDelegate().getAgentState(userId, conversationId);
            if (state == null) return CompactOutcome.UNAVAILABLE;
            List<Msg> context = state.contextMutable();
            if (context == null || context.isEmpty()) return CompactOutcome.UNAVAILABLE;

            // 会话上下文理论上不含 SYSTEM（系统提示词在推理输入里单独拼），防御性剥离并回填
            Msg system = context.get(0).getRole() == MsgRole.SYSTEM ? context.remove(0) : null;
            try {
                int messagesBefore = context.size();
                int tokensBefore = TokenCounterUtil.calculateToken(context);
                int keepMessages = properties.getCompactionKeepMessages();
                if (messagesBefore <= keepMessages) {
                    log.info("[AgentScope] 手动压缩跳过：{} 条 ≤ 保留 {} 条 conversationId={}",
                            messagesBefore, keepMessages, conversationId);
                    return new CompactOutcome("SKIPPED", messagesBefore, messagesBefore,
                            tokensBefore, tokensBefore);
                }

                RuntimeContext rc = RuntimeContext.builder()
                        .userId(userId).sessionId(conversationId).build();
                CompactionConfig config = CompactionConfig.builder()
                        .triggerMessages(1)          // 手动压缩 = 强制触发
                        .keepMessages(keepMessages)  // 与自动压缩同一保留口径
                        .keepTokens(0)               // 0 = 按条数保留（>0 才走 token 口径）
                        .flushBeforeCompact(false)   // 本运行时已 disableMemoryHooks，不做长期记忆刷新
                        .offloadBeforeCompact(false)
                        .summaryPrompt(COMPACTION_PROMPT)
                        .build();
                ConversationCompactor compactor = new ConversationCompactor(
                        agent.getModel(),
                        new MemoryFlushManager(agent.getWorkspaceManager(), agent.getModel(),
                                MemoryFlushManager.DEFAULT_FLUSH_PROMPT));
                Optional<List<Msg>> compacted = compactor
                        .compactIfNeeded(rc, context, config, agent.getName(), conversationId)
                        .block(Duration.ofSeconds(90));
                if (compacted == null || compacted.isEmpty()) {
                    log.warn("[AgentScope] 手动压缩未产出结果（compactor 返回空）{} 条/{} token conversationId={}",
                            messagesBefore, tokensBefore, conversationId);
                    return new CompactOutcome("SKIPPED", messagesBefore, messagesBefore,
                            tokensBefore, tokensBefore);
                }

                context.clear();
                context.addAll(compacted.get());
                agent.getDelegate().saveAgentState(userId, conversationId);
                int tokensAfter = TokenCounterUtil.calculateToken(context);
                log.info("[AgentScope] 手动压缩完成 conversationId={} {}条/{}token → {}条/{}token",
                        conversationId, messagesBefore, tokensBefore, context.size(), tokensAfter);
                return new CompactOutcome("COMPACTED", messagesBefore, context.size(),
                        tokensBefore, tokensAfter);
            } finally {
                if (system != null) context.add(0, system);
            }
        } catch (Exception e) {
            log.warn("[AgentScope] 手动压缩失败 conversationId={}: {}", conversationId, e.getMessage());
            return CompactOutcome.UNAVAILABLE;
        }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 将 AgentScope 会话上下文回退到指定用户轮次之前，用于“编辑最后一轮并覆盖重发”。
     * 只截断该轮及其后的消息，不清空更早的会话记忆。
     */
    public boolean rewindLastTurn(String userId, String conversationId, String userContent) {
        if (!enabled() || conversationId == null || conversationId.isBlank()
                || userContent == null || userContent.isBlank()) {
            return false;
        }
        try {
            List<Msg> context = agent.getDelegate().getAgentState(userId, conversationId).contextMutable();
            if (context == null || context.isEmpty()) return false;
            int index = -1;
            for (int i = context.size() - 1; i >= 0; i--) {
                Msg message = context.get(i);
                if (message.getRole() != MsgRole.USER) continue;
                String text = message.getTextContent();
                if (text != null && text.contains(userContent)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                log.warn("[AgentScope] rewind target not found conversationId={}", conversationId);
                return false;
            }
            context.subList(index, context.size()).clear();
            agent.getDelegate().saveAgentState(userId, conversationId);
            return true;
        } catch (Exception e) {
            log.warn("[AgentScope] rewind last turn failed conversationId={}: {}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    @Override
    public String name() {
        return "video-poll";
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(30);
    }

    @Override
    public int batchSize() {
        return 50;
    }

    @Override
    public List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit) {
        if (!enabled()) return List.of();
        List<AgentAsset> rows = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getType, "video")
                .isNotNull(AgentAsset::getTaskId)
                .in(AgentAsset::getStatus, List.of("queued", "running", "generating"))
                .orderByAsc(AgentAsset::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit)));
        if (rows == null) return List.of();
        return rows.stream()
                .map(row -> new RecoveryCandidate(
                        "video-poll", row.getId(), row.getConversationId(),
                        Map.of("taskId", row.getTaskId())))
                .toList();
    }

    @Override
    public RecoveryOutcome recover(RecoveryCandidate candidate, int attempt, String leaseOwner) {
        Object taskId = candidate.attributes().get("taskId");
        if (taskId == null || String.valueOf(taskId).isBlank()) {
            return RecoveryOutcome.terminal("video task id missing");
        }
        support.scheduleVideoPolling(String.valueOf(taskId));
        return RecoveryOutcome.completed();
    }

    @PreDestroy
    void close() {
        if (agent != null) {
            try {
                agent.close();
            } catch (Exception ignored) {
                // Application shutdown.
            }
        }
    }

    private Path resolveWorkspace() {
        String configured = properties.getWorkspaceDir();
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("java.io.tmpdir"), "moonagent-agentscope");
    }
}
