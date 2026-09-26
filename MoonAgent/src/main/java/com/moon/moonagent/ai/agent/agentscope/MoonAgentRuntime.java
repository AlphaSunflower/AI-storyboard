package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.moon.moonagent.ai.agent.stream.ToolCallStreamGuard;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.AssistantMessage;
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
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class MoonAgentRuntime implements AgentRecoverySource {

    private static final Logger log = LoggerFactory.getLogger(MoonAgentRuntime.class);
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

    private volatile HarnessAgent agent;
    private volatile AgentStateStore stateStore;

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            log.info("[AgentScope] media runtime disabled");
            return;
        }
        try {
            Toolkit toolkit = toolkitFactory.create();
            Model model = modelProvider.withFallback();
            stateStore = new PostgresAgentStateStore(
                    dataSource, properties.getStateSchema(), properties.getStateTable(), false);
            PermissionContextState.Builder permissions = PermissionContextState.builder()
                    .mode(PermissionMode.DEFAULT);
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
                    "revise_storyboard_production",
                    "approve_storyboard_production",
                    "generate_storyboard_image",
                    "generate_storyboard_video",
                    "upsert_storyboard_shot",
                    "list_storyboard_draft",
                    "list_project_storyboard",
                    "reorder_storyboard_draft",
                    "load_skill_through_path",
                    "reset_equipped_tools")) {
                permissions.addAllowRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ALLOW, "MoonAgentReadOnlyPolicy"));
            }
            for (String tool : List.of(
                    "generate_image", "generate_video", "refineImage")) {
                if (properties.isDirectMediaGeneration()) {
                    permissions.addAllowRule(tool, new PermissionRule(
                            tool, null, PermissionBehavior.ALLOW, "MoonAgentMediaPolicy"));
                } else {
                    permissions.addAskRule(tool, new PermissionRule(
                            tool, null, PermissionBehavior.ASK, "MoonAgentMediaPolicy"));
                }
            }
            for (String tool : List.of(
                    "generate_image_batch",
                    "start_storyboard_production",
                    "generate_storyboard_images",
                    "delete_storyboard_draft_shot",
                    "delete_project_storyboard_shot",
                    "clear_project_storyboard",
                    "canvas_delete_nodes",
                    "canvas_delete_edges",
                    "canvas_execute_node",
                    "canvas_execute_graph",
                    "canvas_cancel_execution")) {
                permissions.addAskRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ASK, "MoonAgentMutationPolicy"));
            }
            Path workspace = resolveWorkspace();
            try {
                Files.createDirectories(workspace);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Unable to create AgentScope workspace: " + workspace, e);
            }
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name("moon-agent")
                    .sysPrompt(globalPromptRepository.getPrompt(Map.of()))
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(properties.getMaxIters())
                    .maxRetries(properties.getMaxRetries())
                    .stateStore(stateStore)
                    .skillRepository(skillRepository)
                    .skillFilter(SkillFilter.only(
                            "image", "video", "storyboard", "canvas-design",
                            "canvas-run", "general"))
                    .enableMetaTool(true)
                    .enablePendingToolRecovery(true)
                    .permissionContext(permissions.build())
                    .middlewares(List.of(
                            new TurnContextMiddleware(),
                            new MediaProgressMiddleware(),
                            new ToolCallContentMiddleware(),
                            new AgentTraceBridgeMiddleware()))
                    .workspace(workspace)
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableMemoryTools()
                    .disableMemoryHooks()
                    .disableTranscript()
                    .disableWorkspaceContext()
                    .disableAtPathExpansion()
                    .disableToolsConfig()
                    .disableDefaultWorkspaceSkills()
                    .disableDynamicSubagents()
                    .disableToolResultEviction();
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
                builder.subagents(AgentScopeSubagents.readOnlyExecutors());
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

    /**
     * Seeds a previously unseen AgentScope session with the product message history.
     * Existing runtime state always wins, so this is a one-time migration path for
     * conversations created before the AgentScope cutover.
     */
    public boolean ensureSessionFromHistory(String userId, String conversationId,
                                            List<Msg> history) {
        if (!enabled() || conversationId == null || conversationId.isBlank()
                || history == null || history.isEmpty()) {
            return false;
        }
        try {
            AgentState state = agent.getDelegate().getAgentState(userId, conversationId);
            if (state != null && !state.contextMutable().isEmpty()) {
                return false;
            }
            if (state == null) {
                return false;
            }
            state.contextMutable().addAll(history);
            agent.getDelegate().saveAgentState(userId, conversationId);
            log.info("[AgentScope] reconstructed session from {} product messages conversationId={}",
                    history.size(), conversationId);
            return true;
        } catch (Exception exception) {
            log.warn("[AgentScope] session reconstruction failed conversationId={}: {}",
                    conversationId, exception.getMessage());
            return false;
        }
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
        if (request.getPicUrls() != null && !request.getPicUrls().isEmpty()) {
            input += "\n\n本轮用户附加图片 URL：" + request.getPicUrls();
        }
        String taskContext = asyncTasks.consumeUnreadContext(conversation.getId());
        if (!taskContext.isBlank()) {
            input = taskContext + "\n\n本轮用户消息：\n" + input;
        }
        Msg message = UserMessage.builder().textContent(input).build();
        return stream(conversation, request, trace, List.of(message), null, observer);
    }

    public AgentTurnResult runWakeup(AgentConversation conversation) {
        if (!enabled()) return null;
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
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
            Msg resume = Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(value.text())
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                    .build();
            return stream(conversation, request, trace, List.of(resume),
                    checkpoint.getId(), observer);
        }
        return stream(conversation, request, trace,
                List.of(resumeMessage(value)), checkpoint.getId(), observer);
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
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        boolean hasBoundAssetGroups = assetGroups.hasBoundGroups(conversation);
        turnState.setReferenceInputMode(classifyReferenceInput(request, hasBoundAssetGroups));
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(conversation.getUserId())
                .sessionId(conversation.getId())
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(conversation, request, support, turnState,
                                checkpointId, externalActions))
                .build();
        StringBuilder finalText = new StringBuilder();
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
                                usage.getCachedTokens());
                    }
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
                            support.sendMessage(request, visible);
                        }
                    }
                } else if (event instanceof AgentResultEvent result) {
                    resultText = result.getResult() == null
                            ? "" : result.getResult().getTextContent();
                } else if (event instanceof RequireExternalExecutionEvent external) {
                    ToolUseBlock human = external.getToolCalls().stream()
                            .filter(call -> AgentScopeInteractionTools.REQUEST_HUMAN_INPUT
                                    .equals(call.getName()))
                            .findFirst()
                            .orElse(null);
                    if (human != null) {
                        checkpoints.pauseForExternalInput(request, external.getReplyId(), human);
                    } else {
                        ToolUseBlock commit = external.getToolCalls().stream()
                                .filter(call -> AgentScopeInteractionTools.CHOOSE_STORYBOARD_COMMIT
                                        .equals(call.getName()))
                                .findFirst()
                                .orElse(null);
                        if (commit != null) {
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
                    break;
                } else if (event instanceof RequireUserConfirmEvent confirm) {
                    checkpoints.pauseForConfirmation(
                            request, confirm.getReplyId(), confirm.getToolCalls());
                    paused = true;
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("[AgentScope] run failed conversationId={}", conversation.getId(), e);
            throw e;
        }
        String flushed = textFilter.finish();
        if (!flushed.isEmpty()) {
            finalText.append(flushed);
            support.sendMessage(request, flushed);
        }
        if (loopAborted) {
            support.sendFriendlyError(request, "", "模型的这条回复陷入了重复调用，已中止。请重试或换个说法再问一次。");
            return turnResult(request, trace, "模型的这条回复陷入了重复调用，已中止。", paused);
        }
        String modelText = finalText.isEmpty()
                ? ToolCallStreamGuard.sanitizeComplete(resultText) : finalText.toString();
        String content = turnState.compose(modelText);
        List<Map<String, Object>> taskCards = turnState.taskCards();
        if (!taskCards.isEmpty() && request != null) {
            Map<String, Object> metadata = new LinkedHashMap<>(request.getTurnMetadata());
            metadata.put("taskCards", taskCards);
            request.setTurnMetadata(metadata);
        }
        return turnResult(request, trace, content, paused);
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
        metadata.put("orchestrationMode", "UNIFIED_LOOP");
        metadata.put("checkpointOwner", "AGENTSCOPE");
        metadata.put("agentRuntime", "agentscope-java-2.0.3");
        List<com.moon.moonagent.ai.agent.AgentTraceEvent> events =
                trace == null ? List.of() : trace.snapshot();
        return new AgentTurnResult(
                content == null ? "" : content,
                metadata,
                events,
                request == null ? null : request.getReasoning(),
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
