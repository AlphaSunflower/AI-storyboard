package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.AgentStateStore;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class AgentScopeMediaRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeMediaRuntime.class);
    private static final String SYSTEM_PROMPT = """
            你是 Moon 的 AIGC 执行智能体。你负责图片、视频和会话素材。

            工作原则：
            1. 用户明确要求生成图片或视频时，先读取对应 Skill，然后立即构造一份最佳提示词并调用工具。
               不要先输出方案、步骤说明或“接下来执行第 x/y 步”。
               调用生成工具前先用一句自然、简短的话告诉用户你准备做什么，不要沉默执行。
            2. 只有需求存在实质歧义、缺少关键信息，或用户明确要求比较方案时，才使用
               request_human_input 提一个聚焦问题。多方案最多三套，不得为了流程而强制生成方案。
            3. 用户说“这张/刚才/上一张/继续完善”时，调用 resolve_reference_assets 找到当前会话的
               相关产物，并把 asset id 传给媒体工具；不要臆造或引用无关历史图片。
            4. 图片生成完成后，如果用户同时明确要求继续生成视频，可直接调用视频工具，并把新图片作为参考。
            5. 视频是异步任务：工具返回 taskId 后本轮结束，不要伪造完成状态。
            6. 不得声称已经生成成功，除非工具结果明确返回成功。只有结果同时满足
               ok=true，并包含图片 URL/assetId 或视频 taskId 时，才能说“已完成”或“已提交”。
               工具返回失败、ok=false 或抛出错误时，必须如实说明失败原因，绝不能描述成成功。
            7. 同一种媒体工具本轮最多调用一次。收到 generate_image 或 generate_video 结果后，
               直接向用户总结并结束，不得重复创建同一类任务。
               图片结果已经由运行时显示在对话中，最终回复只需简短总结，不要重复输出图片 Markdown。
            8. 批量生图的 follow_up_video 默认必须为 false。只有用户在同一需求中明确要求
               “生成视频”“首尾帧视频”等视频任务时，才可设为 true。只会生图时绝不创建视频。
            9. 当 follow_up_video=true 时不得再单独调用 generate_video，视频必须等批量图片完成后
               由运行时自动创建。查询图片或视频任务状态时使用 get_generation_task_status，
               不要用 task_output 查询媒体生成任务。
            10. 面向用户时不要暴露工具名、参数名或英文错误字段，必须翻译成自然、简洁的中文。
            11. 用户提到上传的资产组、素材组、参考人物或场景时，先调用 list_asset_groups
                获取组名、文字约束、assetId、媒体文件名和可读取地址，再把对应地址传给媒体工具。
            """;

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
    private final AgentOrchestratorSupport support;
    private final AgentScopeModelProvider modelProvider;
    private final AgentScopeAssetGroupService assetGroups;
    private final DataSource dataSource;
    private final AgentAssetMapper assetMapper;

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
                    "list_asset_groups",
                    "build_media_spec",
                    "assess_generation_risk",
                    "task_list",
                    "task_output",
                    "wait_async_results",
                    "agent_list",
                    "load_skill_through_path",
                    "reset_equipped_tools")) {
                permissions.addAllowRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ALLOW, "MoonAgentReadOnlyPolicy"));
            }
            for (String tool : List.of(
                    "generate_image", "generate_image_batch", "generate_video", "refineImage")) {
                if (properties.isDirectMediaGeneration()) {
                    permissions.addAllowRule(tool, new PermissionRule(
                            tool, null, PermissionBehavior.ALLOW, "MoonAgentMediaPolicy"));
                } else {
                    permissions.addAskRule(tool, new PermissionRule(
                            tool, null, PermissionBehavior.ASK, "MoonAgentMediaPolicy"));
                }
            }
            Path workspace = resolveWorkspace();
            try {
                Files.createDirectories(workspace);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Unable to create AgentScope workspace: " + workspace, e);
            }
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name("moon-aigc")
                    .sysPrompt(SYSTEM_PROMPT)
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(properties.getMaxIters())
                    .maxRetries(properties.getMaxRetries())
                    .stateStore(stateStore)
                    .skillRepository(new ClasspathSkillRepository("skills"))
                    .skillFilter(SkillFilter.only("pic", "video"))
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
                    .disableDynamicSkills()
                    .disableDefaultWorkspaceSkills()
                    .disableDynamicSubagents()
                    .disableToolResultEviction();
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

    public AgentTurnResult runTurn(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace) {
        if (!enabled()) return null;
        String input = request.getContent() == null ? "" : request.getContent();
        if (request.getPicUrls() != null && !request.getPicUrls().isEmpty()) {
            input += "\n\n本轮用户附加图片 URL：" + request.getPicUrls();
        }
        Msg message = UserMessage.builder().textContent(input).build();
        return stream(conversation, request, trace, List.of(message));
    }

    public AgentTurnResult resumeTurn(AgentConversation conversation, AgentCheckpoint checkpoint,
                                      OrchestrationRequest request, AgentTraceAccumulator trace,
                                      String action, String customText) {
        if (!enabled()) return null;
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
            return stream(conversation, request, trace, List.of(resume));
        }
        ToolResultBlock result = ToolResultBlock.of(
                value.toolCallId(), value.toolName(),
                TextBlock.builder().text(value.text()).build());
        Msg resume = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(result)
                .build();
        return stream(conversation, request, trace, List.of(resume));
    }

    private AgentTurnResult stream(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace, List<Msg> messages) {
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        boolean hasBoundAssetGroups = assetGroups.hasBoundGroups(conversation);
        turnState.setReferenceInputMode(classifyReferenceInput(request, hasBoundAssetGroups));
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(conversation.getUserId())
                .sessionId(conversation.getId())
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(conversation, request, support, turnState))
                .build();
        StringBuilder finalText = new StringBuilder();
        ToolCallTextFilter textFilter = new ToolCallTextFilter();
        String resultText = "";
        boolean paused = false;
        try {
            for (AgentEvent event : agent.streamEvents(messages, runtime).toIterable()) {
                support.checkInterrupted(request);
                if (event instanceof TextBlockDeltaEvent delta) {
                    String chunk = delta.getDelta();
                    if (chunk != null && !chunk.isEmpty()) {
                        String visible = textFilter.accept(chunk);
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
                        checkpoints.pauseForConfirmation(
                                request, external.getReplyId(), external.getToolCalls());
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
        String modelText = finalText.isEmpty()
                ? ToolCallTextFilter.sanitizeComplete(resultText) : finalText.toString();
        String content = turnState.compose(modelText);
        return turnResult(request, trace, content, paused);
    }

    static AgentScopeTurnState.ReferenceInputMode classifyReferenceInput(
            OrchestrationRequest request, boolean hasBoundAssetGroups) {
        List<String> picUrls = request == null || request.getPicUrls() == null
                ? List.of() : request.getPicUrls();
        List<String> assetIds = request == null || request.getAssetIds() == null
                ? List.of() : request.getAssetIds();
        boolean hasAssetUrls = picUrls.stream()
                .anyMatch(AgentScopeMediaRuntime::isAssetContentUrl);
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

    private AgentTurnResult turnResult(OrchestrationRequest request, AgentTraceAccumulator trace,
                                       String content, boolean hitlPaused) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request != null) metadata.putAll(request.getTurnMetadata());
        if (trace != null) metadata.putAll(trace.getTurnMetadata());
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

    /**
     * Recovers video tasks that were created before the polling hook existed or were
     * interrupted by a process restart.
     */
    @Scheduled(
            fixedDelayString = "${ai.agent.agentscope.video-recovery-delay-ms:30000}",
            initialDelayString = "${ai.agent.agentscope.video-recovery-initial-delay-ms:15000}")
    void recoverPendingVideoTasks() {
        if (!enabled()) return;
        try {
            List<AgentAsset> rows = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getType, "video")
                    .isNotNull(AgentAsset::getTaskId)
                    .in(AgentAsset::getStatus, List.of("queued", "running", "generating"))
                    .orderByAsc(AgentAsset::getCreatedAt)
                    .last("LIMIT 50"));
            if (rows == null) return;
            for (AgentAsset row : rows) {
                support.scheduleVideoPolling(row.getTaskId());
            }
        } catch (Exception e) {
            log.warn("[AgentScope] video recovery scan failed: {}", e.getMessage());
        }
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
