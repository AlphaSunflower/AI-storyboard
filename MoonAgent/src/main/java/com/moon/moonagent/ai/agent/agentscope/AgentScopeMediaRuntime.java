package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.recovery.AgentRecoverySource;
import com.moon.moonagent.ai.agent.recovery.RecoveryCandidate;
import com.moon.moonagent.ai.agent.recovery.RecoveryOutcome;
import com.moon.moonagent.ai.agent.recovery.ExternalActionLedgerService;
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

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class AgentScopeMediaRuntime implements AgentRecoverySource {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeMediaRuntime.class);
    private static final String SYSTEM_PROMPT = """
            你是 Moon 的 AIGC 执行智能体。你负责剧情优化、分镜、图片、视频和会话素材。

            工作原则：
            1. 用户明确要求生成图片或视频时，先读取对应 Skill，然后立即构造一份最佳提示词并调用工具。
               不要先输出方案、步骤说明或“接下来执行第 x/y 步”。
               调用生成工具前先用一句自然、简短的话告诉用户你准备做什么，不要沉默执行。
            2. 不要调用 request_human_input，也不要输出 moonHumanInput。信息不完整时使用安全默认值
               直接继续执行；不得为了流程暂停、确认或生成选项卡。
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
            12. 用户要求生成分镜时，使用 start_storyboard_production，不得一次性输出全部分镜。
                该工具会先在后台生成/优化完整剧情，再连续完成剧情诊断、问题修正、
                正式镜头生成和项目写库。
                启动前必须明确告诉用户“先根据需求生成并优化完整剧情，再据此设计结构化分镜”，
                不得说“直接生成分镜”或跳过剧情优化阶段。
                拿到 taskId 后只说明这个执行顺序，不提前展示分镜方案、镜头数量或运镜主线；
                完整剧情生成后再由运行时推送剧情稿。
                拿到 taskId 后本轮简短说明即可，结果完成时由运行时自动追加；不得等待、轮询或重复调用。
                当用户要求建筑、产品、空间、风景等设计展示，或明确表示不要剧情时，直接调用
                start_storyboard_production；诊断只修复已确认问题，不额外发明剧情。
                项目已有分镜时，start_storyboard_production 会返回 needsChoice=true。
                此时只能调用 choose_storyboard_commit_mode，让用户固定选择 append、overwrite 或 delete。
                不得生成“扩写稿”。
            13. 用户要看/审查分镜时先调用 list_project_storyboard，汇总缺图和缺视频数量。
                单镜生图或生视频使用 generate_storyboard_image / generate_storyboard_video；
                批量补全所有分镜图片使用 generate_storyboard_images。
            14. 新分镜流程自动完成剧情优化、诊断和正式镜头生成；写入前只保留一次人工选择。
                项目为空时默认 append；项目已有分镜时使用 choose_storyboard_commit_mode 询问
                append、overwrite 或 delete，不得自行替用户选择覆盖或删除。
                查询草稿用 list_storyboard_draft，查询项目正式分镜用 list_project_storyboard；
                删除时严格区分草稿工具和项目删除工具。
            15. 用户说“把所有分镜图片生成出来”时直接调用 generate_storyboard_images，
                不先输出审查列表、不请求确认；默认只补缺图，覆盖已有图片必须明确确认后再传
                overwrite=true 和 confirmed=true。
            16. 用户要修改某个项目分镜的参数时，先查询 list_project_storyboard 定位，
                再调用 update_project_storyboard_shot；只传需要修改的字段，未传字段保持不变。
                修改成功后说明改了哪几个字段，不要重新生成分镜。
            17. 用户只要求“给某个分镜生成视频”时，由你自行判断参考图方案，不得再问用户。
                无参考图则不传 reference_mode；1 张参考图自动作首帧；2 张自动作首尾帧；
                3 张及以上自动作为普通参考素材。用户明确说了首帧、尾帧、首尾帧或参考素材时，
                以用户指定为准并显式传入 reference_mode。没有使用参考图时，需简短说明
                跨镜人物、场景一致性可能减弱，并优先用提示词保持动作与构图连续。
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
    private final ExternalActionLedgerService externalActions;
    private final AgentScopeAsyncTaskService asyncTasks;
    private final AgentScopeMessageBus messageBus;
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
                    "list_conversation_tasks",
                    "get_agent_task",
                    "list_asset_groups",
                    "build_media_spec",
                    "assess_generation_risk",
                    "task_list",
                    "task_output",
                    "wait_async_results",
                    "agent_list",
                    "start_storyboard_production",
                    "revise_storyboard_production",
                    "approve_storyboard_production",
                    "generate_storyboard_image",
                    "generate_storyboard_video",
                    "upsert_storyboard_shot",
                    "list_storyboard_draft",
                    "list_project_storyboard",
                    "delete_storyboard_draft_shot",
                    "reorder_storyboard_draft",
                    "generate_storyboard_images",
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
                    .skillFilter(SkillFilter.only("pic", "video", "storyboard"))
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
            if (messageBus.enabled()) {
                builder.messageBus(messageBus.messageBus());
            }
            for (String tool : List.of(
                    "delete_project_storyboard_shot",
                    "clear_project_storyboard")) {
                permissions.addAskRule(tool, new PermissionRule(
                        tool, null, PermissionBehavior.ASK, "MoonAgentStoryboardDeletePolicy"));
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

    public AgentTurnResult runTurn(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace) {
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
        return stream(conversation, request, trace, List.of(message));
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
            return stream(conversation, request, trace, List.of(resume), checkpoint.getId());
        }
        return stream(conversation, request, trace,
                List.of(resumeMessage(value)), checkpoint.getId());
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
        return stream(conversation, request, trace, messages, null);
    }

    private AgentTurnResult stream(AgentConversation conversation, OrchestrationRequest request,
                                   AgentTraceAccumulator trace, List<Msg> messages,
                                   String checkpointId) {
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
