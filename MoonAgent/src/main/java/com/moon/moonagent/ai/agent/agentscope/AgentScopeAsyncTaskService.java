package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.recovery.AgentRecoverySource;
import com.moon.moonagent.ai.agent.recovery.RecoveryCandidate;
import com.moon.moonagent.ai.agent.recovery.RecoveryOutcome;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxException;
import com.moon.moonagent.ai.agent.sandbox.SandboxArtifactPublisher;
import com.moon.moonagent.ai.agent.sandbox.SandboxExecutionResult;
import com.moon.moonagent.ai.agent.sandbox.SandboxOutputReconciler;
import com.moon.moonagent.ai.agent.sandbox.SandboxSessionLock;
import com.moon.moonagent.ai.agent.sandbox.SandboxSessionRegistry;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.ai.agent.task.AgentTaskSlotView;
import com.moon.moonagent.ai.agent.task.AgentTaskView;
import com.moon.moonagent.ai.agent.task.AgentWakeupMessage;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardGenerationParams;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardCommitMode;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.entity.StoryboardVideoQueue;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueItem;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueRequest;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.StoryboardVideoQueueService;
import com.moon.moonagent.util.PollingBackoff;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeAsyncTaskService implements AgentRecoverySource {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAsyncTaskService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentAsyncTaskMapper taskMapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentGenerationService generationService;
    private final AgentRuntimeSupport support;
    private final AgentScopeProperties properties;
    private final StoryboardProductionService storyboardProductionService;
    private final AgentTaskEventHub taskEvents;
    private final AgentScopeMessageBus messageBus;
    private final AgentAssetMapper assetMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore executorAdmission = new Semaphore(64, true);
    /** 任务运行期的实时输出缓冲（仅内存，终态清理），供任务面板流式展示。 */
    private static final int LIVE_STREAM_MAX_CHARS = 8000;
    private static final long LIVE_STREAM_PUBLISH_INTERVAL_MILLIS = 400L;
    private static final long LIVE_STREAM_PERSIST_INTERVAL_MILLIS = 1500L;
    /** 复合旅程视频子任务等待 agent_assets 终态的上限（与既有人工确认链路一致，偏保守）。 */
    private static final Duration VIDEO_ASSET_WAIT = Duration.ofMinutes(15);
    private final Map<String, StringBuilder> liveStreams = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> liveThinking = new ConcurrentHashMap<>();
    private final Map<String, Long> liveStreamPublishedAt = new ConcurrentHashMap<>();
    private final Map<String, Long> liveStreamPersistedAt = new ConcurrentHashMap<>();
    private final Map<String, String> liveStages = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> liveSequences = new ConcurrentHashMap<>();
    private final PollingBackoff pollBackoff = PollingBackoff.video();
    /** 任务 → userId：推送实时输出时避免每次都查库。 */
    private final Map<String, String> liveUserIds = new ConcurrentHashMap<>();
    /**
     * 子任务终态回调：复合旅程（{@code AgentScopeJourneyRunner}）用它把「子任务完成」
     * 直接接到父子编排上，不需要轮询数据库，也不需要新增事件通道。
     */
    private final Map<String, List<Consumer<AgentAsyncTask>>> terminalListeners =
            new ConcurrentHashMap<>();
    /**
     * 复合旅程编排器（按需解析）。
     *
     * <p>{@link AgentScopeJourneyRunner} 与本服务互相引用（本服务调度父任务、父任务回调本服务），
     * 直接构造器注入会形成 Spring 无法打破的构造环（{@code @Autowired(required=false)} 也不生效：
     * 依赖在容器里存在，只是当前正在创建）。这里改用 {@link ObjectProvider}，
     * 容器注入的是按需解析器本身，不会在创建本 Bean 时触发 runner 的实例化；
     * 真正的循环从此变成"运行时单向调用"。测试直接 new 本服务时该 provider 可为 null
     * （旅程方法只在有 runner 时才被调用）。
     */
    private final ObjectProvider<AgentScopeJourneyRunner> journeyRunnerProvider;

    /**
     * 显式构造器（保留 9 参重载，既有单测无需改动）。
     */
    public AgentScopeAsyncTaskService(AgentAsyncTaskMapper taskMapper,
                                     AgentConversationMapper conversationMapper,
                                     AgentMessageMapper messageMapper,
                                     AgentGenerationService generationService,
                                     AgentRuntimeSupport support,
                                     AgentScopeProperties properties,
                                     StoryboardProductionService storyboardProductionService,
                                     AgentTaskEventHub taskEvents,
                                     AgentScopeMessageBus messageBus) {
        this(taskMapper, conversationMapper, messageMapper, generationService, support, properties,
                storyboardProductionService, taskEvents, messageBus, null, null);
    }

    @Autowired
    public AgentScopeAsyncTaskService(AgentAsyncTaskMapper taskMapper,
                                     AgentConversationMapper conversationMapper,
                                     AgentMessageMapper messageMapper,
                                     AgentGenerationService generationService,
                                     AgentRuntimeSupport support,
                                     AgentScopeProperties properties,
                                     StoryboardProductionService storyboardProductionService,
                                     AgentTaskEventHub taskEvents,
                                     AgentScopeMessageBus messageBus,
                                     @Autowired(required = false) AgentAssetMapper assetMapper,
                                     ObjectProvider<AgentScopeJourneyRunner> journeyRunnerProvider) {
        this.taskMapper = taskMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.generationService = generationService;
        this.support = support;
        this.properties = properties;
        this.storyboardProductionService = storyboardProductionService;
        this.taskEvents = taskEvents;
        this.messageBus = messageBus;
        this.assetMapper = assetMapper;
        this.journeyRunnerProvider = journeyRunnerProvider;
    }

    @Autowired(required = false)
    private transient StoryboardClient storyboardClient;

    @Autowired(required = false)
    private transient StoryboardVideoQueueService storyboardVideoQueueService;

    @Autowired(required = false)
    private transient MoonSandboxClient sandboxClient;

    @Autowired(required = false)
    private transient SandboxArtifactPublisher sandboxPublisher;

    @Autowired(required = false)
    private transient SandboxOutputReconciler sandboxOutputReconciler;

    @Autowired(required = false)
    private transient SandboxSessionLock sandboxSessionLock;

    @Autowired(required = false)
    private transient SandboxSessionRegistry sandboxSessionRegistry;

    /**
     * 站内通知（可选依赖）：任务进入终态时写一条通知，供手机端角标与通知中心展示。
     * 用字段注入保持既有 9 参构造器与单测不变；未装配时静默跳过。
     */
    @Autowired(required = false)
    private transient com.moon.moonagent.service.notification.NotificationService notificationService;

    @Override
    public String name() {
        return "agent-async";
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(30);
    }

    @Override
    public int batchSize() {
        return 20;
    }

    @Override
    public List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit) {
        List<RecoveryCandidate> candidates = new java.util.ArrayList<>();
        taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "image-batch")
                        .eq(AgentAsyncTask::getStatus, "queued")
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .forEach(task -> candidates.add(candidate(task)));
        taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "image-batch")
                        .eq(AgentAsyncTask::getStatus, "running")
                        .lt(AgentAsyncTask::getUpdatedAt, OffsetDateTime.now().minusMinutes(15))
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .forEach(task -> candidates.add(candidate(task)));
        taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "long-tool")
                        .eq(AgentAsyncTask::getStatus, "queued")
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .forEach(task -> candidates.add(candidate(task)));
        taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "long-tool")
                        .eq(AgentAsyncTask::getStatus, "running")
                        .lt(AgentAsyncTask::getUpdatedAt, OffsetDateTime.now().minusMinutes(15))
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .forEach(task -> candidates.add(candidate(task)));
        taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "long-tool")
                        .eq(AgentAsyncTask::getStatus, "waiting_parent")
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .filter(task -> {
                    AgentAsyncTask parent = task.getParentTaskId() == null
                            ? null : taskMapper.selectById(task.getParentTaskId());
                    return parent == null || isTerminal(parent.getStatus());
                })
                .forEach(task -> candidates.add(candidate(task)));
        // 复合旅程：只有 queued 才可安全重放（running 的父子编排无法确认进度，由看门狗按超时处理）。
        taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "journey")
                        .eq(AgentAsyncTask::getStatus, "queued")
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .forEach(task -> candidates.add(candidate(task)));
        return candidates;
    }

    @Override
    public RecoveryOutcome recover(RecoveryCandidate candidate, int attempt, String leaseOwner) {
        AgentAsyncTask task = taskMapper.selectById(candidate.resourceId());
        if (task == null || "completed".equals(task.getStatus())
                || "failed".equals(task.getStatus())) {
            return RecoveryOutcome.completed();
        }
        if ("running".equals(task.getStatus())) {
            if (isStoryboardVideoTool(task.getToolName())) {
                updateTask(task.getId(), "status", "queued", "stage", "accepted", "progress", 0);
                submitStoryboardTask(taskMapper.selectById(task.getId()));
                return RecoveryOutcome.deferred("storyboard video task re-submitted");
            }
            fail(task.getId(), "服务重启后无法安全确认原任务状态，请重新发起。");
            return RecoveryOutcome.completed();
        }
        if ("queued".equals(task.getStatus())) {
            if ("journey".equals(task.getKind())) {
                submitJourney(task);
                return RecoveryOutcome.deferred("journey task submitted");
            }
            if ("long-tool".equals(task.getKind())) {
                submitStoryboardTask(task);
                return RecoveryOutcome.deferred("storyboard task submitted");
            }
            submitAdmitted(() -> runBatchImageTask(task.getId()));
            return RecoveryOutcome.deferred("batch image task submitted");
        }
        if ("waiting_parent".equals(task.getStatus())) {
            AgentAsyncTask parent = task.getParentTaskId() == null
                    ? null : taskMapper.selectById(task.getParentTaskId());
            if (parent == null || isTerminal(parent.getStatus())) {
                submitStoryboardTask(task);
                return RecoveryOutcome.deferred("child storyboard task submitted");
            }
            return RecoveryOutcome.retryable("parent task still running");
        }
        return RecoveryOutcome.completed();
    }

    public String startBatchImageTask(
            AgentConversation conversation,
            String prompt,
            List<String> prompts,
            List<String> sourceUrls,
            String size,
            String quality,
            int count,
            boolean followUpVideo,
            String videoPrompt,
            String videoDuration,
            String videoAspectRatio,
            String videoResolution,
            String toolCallId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", prompt == null ? "" : prompt);
        request.put("prompts", prompts == null ? List.of() : prompts);
        request.put("sourceUrls", sourceUrls == null ? List.of() : sourceUrls);
        request.put("size", size == null ? "" : size);
        request.put("quality", quality == null ? "" : quality);
        request.put("count", count);
        request.put("followUpVideo", followUpVideo);
        request.put("videoPrompt", videoPrompt == null ? "" : videoPrompt);
        request.put("videoDuration", videoDuration == null ? "" : videoDuration);
        request.put("videoAspectRatio", videoAspectRatio == null ? "" : videoAspectRatio);
        request.put("videoResolution", videoResolution == null ? "" : videoResolution);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + requestJson).getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        task.setKind("image-batch");
        task.setStatus("queued");
        task.setTitle(count > 1 ? "批量生成 " + count + " 张图片" : "生成图片");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitAdmitted(() -> runBatchImageTask(task.getId()));
        return task.getId();
    }

    public String startStoryboardImageTask(
            AgentConversation conversation,
            List<StoryboardImageTarget> targets,
            boolean overwrite,
            String toolCallId) {
        List<StoryboardImageTarget> safeTargets = targets == null ? List.of() : targets;
        if (safeTargets.isEmpty()) {
            throw new IllegalStateException("没有需要生成图片的分镜");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", "分镜图片批量生成");
        request.put("prompts", safeTargets.stream().map(StoryboardImageTarget::prompt).toList());
        request.put("slotLabels", safeTargets.stream().map(StoryboardImageTarget::label).toList());
        request.put("sceneIds", safeTargets.stream().map(StoryboardImageTarget::sceneId).toList());
        request.put("shotIds", safeTargets.stream().map(StoryboardImageTarget::shotId).toList());
        request.put("sourceUrlsBySlot",
                safeTargets.stream().map(StoryboardImageTarget::sourceUrls).toList());
        request.put("sourceUrls", List.of());
        request.put("count", safeTargets.size());
        request.put("overwrite", overwrite);
        request.put("followUpVideo", false);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + toolCallId + "|" + requestJson)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        task.setKind("image-batch");
        task.setStatus("queued");
        task.setTitle("批量生成分镜图片");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitAdmitted(() -> runBatchImageTask(task.getId()));
        return task.getId();
    }

    public String startStoryboardTask(
            AgentConversation conversation,
            StoryboardProductionRequest productionRequest,
            String toolCallId) {
        return startStoryboardTask(
                conversation, productionRequest, toolCallId, StoryboardCommitMode.APPEND);
    }

    public String startStoryboardTask(
            AgentConversation conversation,
            StoryboardProductionRequest productionRequest,
            String toolCallId,
            StoryboardCommitMode commitMode) {
        return startStoryboardTask(conversation, productionRequest, toolCallId, commitMode, true);
    }

    public String startPlotReviewStoryboardTask(
            AgentConversation conversation,
            StoryboardProductionRequest productionRequest,
            String toolCallId,
            StoryboardCommitMode commitMode) {
        return startStoryboardTask(conversation, productionRequest, toolCallId, commitMode, false);
    }

    private String startStoryboardTask(
            AgentConversation conversation,
            StoryboardProductionRequest productionRequest,
            String toolCallId,
            StoryboardCommitMode commitMode,
            boolean auto) {
        AgentAsyncTask active = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getConversationId, conversation.getId())
                .eq(AgentAsyncTask::getToolName, AgentScopeStoryboardTools.START_STORYBOARD)
                .in(AgentAsyncTask::getStatus, List.of("queued", "running", "waiting_parent"))
                .orderByDesc(AgentAsyncTask::getUpdatedAt)
                .last("LIMIT 1"));
        if (active != null) return active.getId();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("originalScript", productionRequest.originalScript());
        request.put("creationType", productionRequest.creationType());
        request.put("requestedShotCount", productionRequest.requestedShotCount());
        request.put("assets", productionRequest.assets());
        request.put("roleHints", productionRequest.roleHints());
        request.put("commitMode",
                (commitMode == null ? StoryboardCommitMode.APPEND : commitMode).name());
        // 生成参数随任务落库：写库阶段可能发生在另一个 approve 任务里，必须能按会话回读
        request.put("generationParams", productionRequest.generationParams());
        request.put("auto", auto);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + toolCallId + "|" + requestJson)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeStoryboardTools.START_STORYBOARD);
        task.setKind("long-tool");
        task.setStatus("queued");
        task.setTitle(auto ? "剧情生成、诊断与分镜" : "生成剧情稿");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitAdmitted(() -> runStoryboardStartTask(task.getId()));
        return task.getId();
    }

    public String startStoryboardApprovalTask(
            AgentConversation conversation,
            String runId,
            String stage,
            String decision,
            String toolCallId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("runId", runId == null ? "" : runId);
        request.put("stage", stage == null ? "" : stage);
        request.put("decision", decision == null ? "" : decision);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + toolCallId + "|" + requestJson)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeStoryboardTools.APPROVE_STORYBOARD);
        task.setKind("long-tool");
        task.setStatus("queued");
        task.setTitle("PLOT_REVIEW".equalsIgnoreCase(stage)
                ? "生成正式镜头清单"
                : "FINAL_REVIEW".equalsIgnoreCase(stage) ? "写入项目分镜" : "生成正式分镜");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitAdmitted(() -> runStoryboardApprovalTask(task.getId()));
        return task.getId();
    }

    public String startStoryboardRevisionTask(
            AgentConversation conversation,
            String runId,
            String stage,
            String instruction,
            String shotId,
            String toolCallId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("runId", runId == null ? "" : runId);
        request.put("stage", stage == null ? "" : stage);
        request.put("instruction", instruction == null ? "" : instruction);
        request.put("shotId", shotId == null ? "" : shotId);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + toolCallId + "|" + requestJson)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeStoryboardTools.REVISE_STORYBOARD);
        task.setKind("long-tool");
        task.setStatus("queued");
        task.setTitle("修订分镜");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitAdmitted(() -> runStoryboardRevisionTask(task.getId()));
        return task.getId();
    }

    public String startStoryboardVideoTask(
            AgentConversation conversation,
            List<String> sceneIds,
            boolean overwrite,
            String toolCallId) {
        List<String> targets = sceneIds == null ? List.of() : sceneIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalStateException("没有可生成视频的分镜");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sceneIds", targets);
        request.put("overwrite", overwrite);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + toolCallId + "|" + requestJson)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(targets.size() == 1
                ? AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO
                : AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS);
        task.setKind("long-tool");
        task.setStatus("queued");
        task.setTitle(targets.size() == 1 ? "生成分镜视频" : "批量生成分镜视频");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitAdmitted(() -> runStoryboardVideoTask(task.getId()));
        return task.getId();
    }

    public String startSandboxScriptTask(
            AgentConversation conversation,
            String script,
            List<String> args,
            int timeoutSeconds,
            String toolCallId) {
        if (sandboxClient == null || !sandboxClient.enabled() || sandboxPublisher == null) {
            throw new MoonSandboxException("Sandbox runner is not enabled");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("script", script == null ? "" : script);
        request.put("args", args == null ? List.of() : args);
        request.put("timeoutSeconds", timeoutSeconds);
        String requestJson = writeJson(request);
        String idempotencyKey = UUID.nameUUIDFromBytes(
                (conversation.getId() + "|" + toolCallId + "|" + requestJson)
                        .getBytes(StandardCharsets.UTF_8))
                .toString();
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeSandboxTools.RUN_PYTHON_SCRIPT);
        task.setKind("long-tool");
        task.setStatus("queued");
        task.setTitle("执行 Python 脚本");
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitAdmitted(() -> runSandboxScriptTask(task.getId()));
        return task.getId();
    }

    public Map<String, Object> getStatus(String userId, String taskId) {
        AgentTaskView view = getTask(userId, taskId);
        return view == null ? Map.of() : viewMap(view);
    }

    public Map<String, Object> getConversationStatus(String conversationId, String taskId) {
        AgentTaskView view = getConversationTask(conversationId, taskId);
        return view == null ? Map.of() : viewMap(view);
    }

    public AgentTaskView getTask(String userId, String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return null;
        AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
        if (conversation == null || !userId.equals(conversation.getUserId())) return null;
        return view(task, conversation);
    }

    public AgentTaskView getConversationTask(String conversationId, String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !conversationId.equals(task.getConversationId())) return null;
        AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
        return conversation == null ? null : view(task, conversation);
    }

    public Map<String, Object> listTasks(
            String userId,
            String conversationId,
            String projectId,
            String status,
            int page,
            int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        LambdaQueryWrapper<AgentAsyncTask> query = new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getUserId, userId);
        if (conversationId != null && !conversationId.isBlank()) {
            query.eq(AgentAsyncTask::getConversationId, conversationId);
        } else if (projectId != null && !projectId.isBlank()) {
            List<String> conversationIds = conversationMapper.selectList(
                            new LambdaQueryWrapper<AgentConversation>()
                                    .eq(AgentConversation::getUserId, userId)
                                    .eq(AgentConversation::getProjectId, projectId))
                    .stream().map(AgentConversation::getId).toList();
            if (conversationIds.isEmpty()) {
                return Map.of("records", List.of(), "total", 0L, "page", safePage, "size", safeSize);
            }
            query.in(AgentAsyncTask::getConversationId, conversationIds);
        }
        applyStatusFilter(query, status);
        long total = taskMapper.selectCount(query);
        List<AgentAsyncTask> tasks = taskMapper.selectList(query
                .orderByDesc(AgentAsyncTask::getUpdatedAt)
                .last("LIMIT " + safeSize + " OFFSET " + ((safePage - 1) * safeSize)));
        Map<String, AgentConversation> conversations = conversationsById(tasks);
        List<AgentTaskView> records = tasks.stream()
                .map(task -> {
                    AgentConversation conversation = conversations.get(task.getConversationId());
                    if (conversation == null || !userId.equals(conversation.getUserId())) return null;
                    return view(task, conversation);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return Map.of(
                "records", records,
                "total", total,
                "page", safePage,
                "size", safeSize);
    }

    public List<AgentTaskView> listTaskViews(
            String userId,
            String conversationId,
            String projectId,
            String status,
            int limit) {
        LambdaQueryWrapper<AgentAsyncTask> query = new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getUserId, userId)
                .orderByDesc(AgentAsyncTask::getUpdatedAt)
                .last("LIMIT " + Math.max(1, Math.min(500, limit)));
        if (conversationId != null && !conversationId.isBlank()) {
            query.eq(AgentAsyncTask::getConversationId, conversationId);
        } else if (projectId != null && !projectId.isBlank()) {
            List<String> conversationIds = conversationMapper.selectList(
                            new LambdaQueryWrapper<AgentConversation>()
                                    .eq(AgentConversation::getUserId, userId)
                                    .eq(AgentConversation::getProjectId, projectId))
                    .stream().map(AgentConversation::getId).toList();
            if (conversationIds.isEmpty()) return List.of();
            query.in(AgentAsyncTask::getConversationId, conversationIds);
        }
        applyStatusFilter(query, status);
        List<AgentAsyncTask> tasks = taskMapper.selectList(query);
        Map<String, AgentConversation> conversations = conversationsById(tasks);
        return tasks.stream()
                .map(task -> {
                    AgentConversation conversation = conversations.get(task.getConversationId());
                    if (conversation == null || !userId.equals(conversation.getUserId())) return null;
                    return view(task, conversation);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Map<String, AgentConversation> conversationsById(Collection<AgentAsyncTask> tasks) {
        Set<String> ids = tasks.stream()
                .map(AgentAsyncTask::getConversationId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return conversationMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AgentConversation::getId, conversation -> conversation));
    }

    public AgentTaskView cancelTask(String userId, String taskId) {
        AgentTaskView owned = getTask(userId, taskId);
        if (owned == null) return null;
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || isTerminal(task.getStatus()) || "cancelled".equals(task.getStatus())) {
            AgentConversation conversation = task == null
                    ? null : conversationMapper.selectById(task.getConversationId());
            return task == null || conversation == null ? null : view(task, conversation);
        }
        OffsetDateTime now = OffsetDateTime.now();
        if ("queued".equals(task.getStatus()) || "waiting_parent".equals(task.getStatus())) {
            updateTask(taskId, "status", "cancelled", "cancel_requested", true,
                    "stage", "cancelled", "result_summary", "任务已取消", "completed_at", now);
            // 取消父任务必须同时收口它的子行，避免留下孤儿后台生成。
            // 这里刻意不走 afterTerminal：queued/waiting_parent 的任务从未运行，
            // 没有实时输出需要 flush，取消路径必须只有上面这一次终态写入。
            releaseWaitingChildren(taskId);
            notifyTerminalListeners(taskId);
        } else {
            updateTask(taskId, "cancel_requested", true, "status", "cancelling");
        }
        return getTask(userId, taskId);
    }

    public AgentTaskView retryTask(String userId, String taskId) {
        AgentTaskView owned = getTask(userId, taskId);
        if (owned == null) return null;
        AgentAsyncTask source = taskMapper.selectById(taskId);
        if (source == null) return null;
        if (!"partial".equals(source.getStatus())
                && !"failed".equals(source.getStatus())
                && !"cancelled".equals(source.getStatus())) {
            throw new IllegalStateException("只有失败、部分成功或已取消的任务可以重试");
        }
        AgentConversation conversation = conversationMapper.selectById(source.getConversationId());
        String requestJson = source.getRequestJson();
        String title = source.getTitle();
        if (AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(source.getToolName())) {
            Map<String, Object> sourceRequest = readMap(source.getRequestJson());
            List<AgentScopeBatchImageSlots.Slot> slots =
                    AgentScopeBatchImageSlots.fromMaps(readMap(source.getResultJson()).get("slots"));
            List<Integer> retryIndices = AgentScopeBatchImageSlots.failedOrMissingIndices(slots);
            if (!retryIndices.isEmpty()) {
                Map<String, Object> retryRequest = new LinkedHashMap<>(sourceRequest);
                retryRequest.put("prompts", AgentScopeBatchImageSlots.promptsAt(slots, retryIndices));
                retryRequest.put("slotLabels", AgentScopeBatchImageSlots.labelsAt(slots, retryIndices));
                retryRequest.put("slotIndices", retryIndices);
                retryRequest.put("count", retryIndices.size());
                retryRequest.put("retryOfTaskId", source.getId());
                requestJson = writeJson(retryRequest);
                title = "重试 " + retryIndices.size() + " 张失败图片";
            }
        } else if (AgentScopeStoryboardTools.START_STORYBOARD.equals(source.getToolName())) {
            // 整条分镜重试：带上上一次尝试创建的 run，写入成功后据此清理旧分镜
            String previousRunId = string(readMap(source.getResultJson()).get("runId"));
            if (!previousRunId.isBlank()) {
                Map<String, Object> retryRequest = new LinkedHashMap<>(readMap(source.getRequestJson()));
                retryRequest.put("previousRunId", previousRunId);
                retryRequest.put("retryOfTaskId", source.getId());
                requestJson = writeJson(retryRequest);
            }
        }
        AgentAsyncTask child = new AgentAsyncTask();
        child.setConversationId(source.getConversationId());
        child.setUserId(source.getUserId());
        child.setSessionId(source.getSessionId());
        child.setToolName(source.getToolName());
        child.setKind(source.getKind());
        child.setStatus("queued");
        child.setTitle(title);
        child.setStage("accepted");
        child.setProgress(0);
        child.setRequestJson(requestJson);
        child.setParentTaskId(source.getId());
        child.setIdempotencyKey(UUID.randomUUID().toString());
        child.setVersion(0L);
        child.setCancelRequested(false);
        child.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(child);
        publish(child.getId());
        submit(child);
        return conversation == null ? null : view(child, conversation);
    }

    public AgentTaskView followUpTask(String userId, String taskId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalStateException("补充要求不能为空");
        }
        AgentTaskView owned = getTask(userId, taskId);
        if (owned == null) return null;
        AgentAsyncTask parent = taskMapper.selectById(taskId);
        if (parent == null) return null;
        if (!AgentScopeStoryboardTools.START_STORYBOARD.equals(parent.getToolName())
                && !AgentScopeStoryboardTools.APPROVE_STORYBOARD.equals(parent.getToolName())) {
            throw new IllegalStateException("该任务暂不支持补充要求");
        }
        AgentConversation conversation = conversationMapper.selectById(parent.getConversationId());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("runId", "");
        request.put("stage", AgentScopeStoryboardTools.START_STORYBOARD.equals(parent.getToolName())
                ? "PLOT_REVIEW" : "PSEUDO_REVIEW");
        request.put("instruction", instruction == null ? "" : instruction.trim());
        request.put("shotId", "");
        String requestJson = writeJson(request);
        AgentAsyncTask child = new AgentAsyncTask();
        child.setConversationId(parent.getConversationId());
        child.setUserId(parent.getUserId());
        child.setSessionId(parent.getSessionId());
        child.setToolName(AgentScopeStoryboardTools.REVISE_STORYBOARD);
        child.setKind("long-tool");
        child.setStatus(isTerminal(parent.getStatus()) ? "queued" : "waiting_parent");
        child.setTitle("按补充要求修订分镜");
        child.setStage("accepted");
        child.setProgress(0);
        child.setRequestJson(requestJson);
        child.setParentTaskId(parent.getId());
        child.setIdempotencyKey(UUID.randomUUID().toString());
        child.setVersion(0L);
        child.setCancelRequested(false);
        child.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(child);
        publish(child.getId());
        if ("queued".equals(child.getStatus())) submit(child);
        return conversation == null ? null : view(child, conversation);
    }

    public void bindAnchorMessages(String messageId, List<Map<String, Object>> taskCards) {
        if (messageId == null || messageId.isBlank() || taskCards == null || taskCards.isEmpty()) return;
        for (Map<String, Object> card : taskCards) {
            String taskId = string(card.get("taskId"));
            if (taskId.isBlank()) continue;
            updateTask(taskId, "anchor_message_id", messageId);
        }
    }

    public String consumeUnreadContext(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return "";
        List<AgentAsyncTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getConversationId, conversationId)
                .in(AgentAsyncTask::getStatus, List.of("completed", "partial", "failed", "cancelled"))
                .isNull(AgentAsyncTask::getContextConsumedAt)
                .and(wrapper -> wrapper
                        .isNull(AgentAsyncTask::getWakeupStatus)
                        .or()
                        .in(AgentAsyncTask::getWakeupStatus, List.of("not_required", "failed")))
                .orderByAsc(AgentAsyncTask::getCompletedAt)
                .last("LIMIT 5"));
        if (tasks.isEmpty()) return "";
        StringBuilder context = new StringBuilder();
        for (AgentAsyncTask task : tasks) {
            context.append("- [").append(task.getStatus()).append("] ")
                    .append(string(task.getTitle()));
            String summary = string(task.getResultSummary());
            if (!summary.isBlank()) context.append("：").append(summary);
            String error = string(task.getError());
            if (!error.isBlank()) context.append("；错误：").append(error);
            context.append("\n");
        }
        for (AgentAsyncTask task : tasks) {
            updateTask(task.getId(), "context_consumed_at", OffsetDateTime.now());
        }
        return "以下是刚完成、尚未在后续对话中消费的后台任务：\n" + context;
    }

    private static RecoveryCandidate candidate(AgentAsyncTask task) {
        return new RecoveryCandidate("agent-async", task.getId(),
                task.getConversationId(), Map.of());
    }

    private void runBatchImageTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) return;
        int claimed = claim(taskId, "generating_images", 10);
        if (claimed == 0) return;
        try {
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            Map<String, Object> request = readMap(task.getRequestJson());
            AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
            if (conversation == null) {
                fail(taskId, "会话不存在");
                return;
            }
            List<String> sources = stringList(request.get("sourceUrls"));
            List<AgentScopeBatchImageSlots.Slot> initial = initialBatchSlots(request);
            if (initial.isEmpty()) {
                fail(taskId, "批量生图缺少提示词");
                return;
            }
            List<AgentScopeBatchImageSlots.Slot> current =
                    new java.util.ArrayList<>(initial);
            persistBatchImageProgress(taskId, current);

            var result = AgentScopeBatchImageRunner.run(
                    executor,
                    initial,
                    properties.getImageBatchConcurrency(),
                    () -> isCancelRequested(taskId),
                    slot -> generateBatchSlot(conversation, request, sources, slot),
                    completed -> {
                        replaceSlot(current, completed);
                        persistBatchImageProgress(taskId, current);
                    });
            if (result.cancelled()) {
                cancelBatchImageTask(taskId, result.slots(), request);
            } else {
                finishBatchImageTask(taskId, conversation, request, result.slots());
            }
        } catch (Exception e) {
            log.warn("Batch image task failed: taskId={}, error={}", taskId, e.getMessage());
            if (isCancelRequested(taskId)) cancelRunning(taskId);
            else fail(taskId, e.getMessage() == null ? "批量生图失败" : e.getMessage());
        }
    }

    private List<AgentScopeBatchImageSlots.Slot> initialBatchSlots(Map<String, Object> request) {
        int count = integer(request.get("count"), 1);
        List<String> prompts = stringList(request.get("prompts"));
        if (prompts.isEmpty()) {
            String prompt = string(request.get("prompt"));
            prompts = java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> prompt)
                    .toList();
        }
        prompts = prompts.stream()
                .filter(prompt -> prompt != null && !prompt.isBlank())
                .toList();
        return AgentScopeBatchImageSlots.initial(
                prompts,
                integerList(request.get("slotIndices")),
                stringList(request.get("slotLabels")));
    }

    private AgentScopeBatchImageSlots.Slot generateBatchSlot(
            AgentConversation conversation,
            Map<String, Object> request,
            List<String> sources,
            AgentScopeBatchImageSlots.Slot slot) {
        try {
            Map<String, Object> result = generationService.generateImage(
                    conversation, sceneIdAt(request, slot.index()), slot.prompt(), null,
                    string(request.get("size")), string(request.get("quality")),
                    1, effectiveSources(request, sources, slot.index()).isEmpty() ? null : "edit",
                    effectiveSources(request, sources, slot.index()), null);
            List<String> urls = stringList(result.get("imageUrls"));
            if (urls.isEmpty()) {
                return slot.fail("图片生成未返回结果", OffsetDateTime.now());
            }
            List<String> assetIds = stringList(result.get("assetIds"));
            return slot.complete(
                    urls.getFirst(),
                    assetIds.isEmpty() ? "" : assetIds.getFirst(),
                    OffsetDateTime.now());
        } catch (Exception e) {
            return slot.fail(
                    e.getMessage() == null ? "图片生成失败" : e.getMessage(),
                    OffsetDateTime.now());
        }
    }

    private void replaceSlot(
            List<AgentScopeBatchImageSlots.Slot> slots,
            AgentScopeBatchImageSlots.Slot completed) {
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).index() == completed.index()) {
                slots.set(index, completed);
                return;
            }
        }
    }

    private void persistBatchImageProgress(
            String taskId,
            List<AgentScopeBatchImageSlots.Slot> slots) {
        AgentScopeBatchImageSlots.Snapshot snapshot =
                AgentScopeBatchImageSlots.snapshot(slots, false);
        int updated = taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .in("status", List.of("running", "cancelling"))
                .set("stage", "generating_images")
                .set("progress", snapshot.progress())
                .set("result_summary", snapshot.resultSummary())
                .set("result_json", writeJson(Map.of(
                        "content", snapshot.content(),
                        "slots", AgentScopeBatchImageSlots.toMaps(slots),
                        "followUpTaskId", "")))
                .set("asset_ids_json", writeJson(snapshot.assetIds()))
                .set("result_urls_json", writeJson(snapshot.resultUrls()))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now()));
        if (updated > 0) publish(taskId);
    }

    private void finishBatchImageTask(
            String taskId,
            AgentConversation conversation,
            Map<String, Object> request,
            List<AgentScopeBatchImageSlots.Slot> slots) {
        AgentScopeBatchImageSlots.Snapshot snapshot =
                AgentScopeBatchImageSlots.snapshot(slots, true);
        String followUpTaskId = "";
        if ("completed".equals(snapshot.status())) {
            followUpTaskId = startFollowUpVideo(
                    conversation, request, snapshot.resultUrls());
        }
        String content = terminalBatchContent(snapshot, followUpTaskId);
        updateBatchImageTerminal(
                taskId,
                snapshot.status(),
                snapshot.progress(),
                snapshot.resultSummary(),
                snapshot.error(),
                content,
                snapshot.assetIds(),
                snapshot.resultUrls(),
                slots);
        mergeRetryIntoParent(taskId, request, slots);
    }

    private void cancelBatchImageTask(
            String taskId,
            List<AgentScopeBatchImageSlots.Slot> slots,
            Map<String, Object> request) {
        AgentScopeBatchImageSlots.Snapshot snapshot =
                AgentScopeBatchImageSlots.snapshot(slots, true);
        updateBatchImageTerminal(
                taskId,
                "cancelled",
                snapshot.progress(),
                "任务已取消，已生成 " + snapshot.resultUrls().size() + " 张图片",
                "",
                terminalBatchContent(snapshot, ""),
                snapshot.assetIds(),
                snapshot.resultUrls(),
                slots);
        mergeRetryIntoParent(taskId, request, slots);
    }

    private void updateBatchImageTerminal(
            String taskId,
            String status,
            int progress,
            String summary,
            String error,
            String content,
            List<String> assetIds,
            List<String> urls,
            List<AgentScopeBatchImageSlots.Slot> slots) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null
                || "completed".equals(task.getStatus())
                || "failed".equals(task.getStatus())
                || "cancelled".equals(task.getStatus())) {
            return;
        }
        String stage = switch (status) {
            case "partial" -> "partial";
            case "failed" -> "failed";
            case "cancelled" -> "cancelled";
            default -> completedStage(task);
        };
        int updated = taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .in("status", List.of("running", "cancelling", "partial"))
                .set("status", status)
                .set("stage", stage)
                .set("progress", progress)
                .set("result_summary", summary)
                .set("error", error == null || error.isBlank() ? null : error)
                .set("result_json", writeJson(Map.of(
                        "content", content,
                        "slots", AgentScopeBatchImageSlots.toMaps(slots),
                        "followUpTaskId", "")))
                .set("asset_ids_json", writeJson(assetIds))
                .set("result_urls_json", writeJson(urls))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        if (updated > 0) {
            publish(taskId);
            afterTerminal(taskId);
            appendTerminalResultToAnchor(task, content);
        }
    }

    void mergeRetryIntoParent(
            String childTaskId,
            Map<String, Object> childRequest,
            List<AgentScopeBatchImageSlots.Slot> childSlots) {
        String parentTaskId = string(childRequest.get("retryOfTaskId"));
        if (parentTaskId.isBlank()) return;
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null
                || !AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(parent.getToolName())) {
            return;
        }
        List<AgentScopeBatchImageSlots.Slot> parentSlots =
                AgentScopeBatchImageSlots.fromMaps(readMap(parent.getResultJson()).get("slots"));
        if (parentSlots.isEmpty()) return;
        List<AgentScopeBatchImageSlots.Slot> merged =
                AgentScopeBatchImageSlots.merge(parentSlots, childSlots);
        AgentScopeBatchImageSlots.Snapshot snapshot =
                AgentScopeBatchImageSlots.snapshot(merged, true);
        Map<String, Object> parentRequest = readMap(parent.getRequestJson());
        AgentConversation conversation = conversationMapper.selectById(parent.getConversationId());
        String followUpTaskId = "";
        if ("completed".equals(snapshot.status()) && conversation != null) {
            followUpTaskId = startFollowUpVideo(
                    conversation, parentRequest, snapshot.resultUrls());
        }
        updateBatchImageTerminal(
                parentTaskId,
                snapshot.status(),
                snapshot.progress(),
                snapshot.resultSummary(),
                snapshot.error(),
                terminalBatchContent(snapshot, followUpTaskId),
                snapshot.assetIds(),
                snapshot.resultUrls(),
                merged);
        log.info("Batch image retry merged: child={}, parent={}, status={}",
                childTaskId, parentTaskId, snapshot.status());
    }

    private static String terminalBatchContent(
            AgentScopeBatchImageSlots.Snapshot snapshot,
            String followUpTaskId) {
        StringBuilder content = new StringBuilder(snapshot.content());
        if (!snapshot.resultSummary().isBlank()) {
            if (!content.isEmpty()) content.append("\n\n");
            content.append(snapshot.resultSummary());
        }
        if (content.isEmpty()) {
            content.append("批量生图未返回图片。");
        }
        if (followUpTaskId != null && !followUpTaskId.isBlank()) {
            content.append("\n\n图片已生成，首尾帧视频任务已提交，任务 ID：`")
                    .append(followUpTaskId).append("`。");
        }
        return content.toString();
    }

    private void appendTerminalResultToAnchor(AgentAsyncTask task, String content) {
        if (task == null || task.getAnchorMessageId() == null
                || task.getAnchorMessageId().isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        AgentMessage anchor = messageMapper.selectById(task.getAnchorMessageId());
        if (anchor == null) return;
        String existing = string(anchor.getContent()).trim();
        String startMarker = "[//]: # (async-task:" + task.getId() + ":start)";
        String endMarker = "[//]: # (async-task:" + task.getId() + ":end)";
        String block = startMarker + "\n" + content + "\n" + endMarker;
        int start = existing.indexOf(startMarker);
        int end = existing.indexOf(endMarker, Math.max(0, start));
        String updated = start >= 0 && end >= start
                ? existing.substring(0, start) + block + existing.substring(end + endMarker.length())
                : (existing.isBlank() ? block : existing + "\n\n" + block);
        if (updated.equals(existing)) return;
        messageMapper.update(null, new UpdateWrapper<AgentMessage>()
                .eq("id", task.getAnchorMessageId())
                .set("content", updated));
    }

    private void runStoryboardStartTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) return;
        int claimed = claim(taskId, "script_optimization", 5);
        if (claimed == 0) return;
        try {
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            Map<String, Object> request = readMap(task.getRequestJson());
            AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
            if (conversation == null) {
                fail(taskId, "会话不存在");
                return;
            }
            StoryboardProductionRequest productionRequest = new StoryboardProductionRequest(
                    conversation.getUserId(),
                    conversation.getProjectId(),
                    conversation.getId(),
                    "",
                    string(request.get("originalScript")),
                    string(request.get("creationType")),
                    string(request.get("requestedShotCount")),
                    assetRefs(request.get("assets")),
                    stringList(request.get("roleHints")),
                    generationParamsOf(request.get("generationParams")));
            boolean auto = Boolean.TRUE.equals(request.get("auto"));
            com.moon.moonagent.ai.storyboard.production.StoryboardProgressListener listener =
                    liveListener(taskId);
            StoryboardRunSnapshot generated = auto
                    ? storyboardProductionService.startAutoStoryboard(productionRequest, listener)
                    : storyboardProductionService.start(productionRequest, listener);
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            // 记录本次尝试创建的 run：重试时据此回滚上一次已写入的项目分镜，避免重复写入
            updateTask(taskId, "result_json", writeJson(Map.of("runId", generated.runId())));
            if (!auto) {
                completeStoryboard(taskId, storyboardReviewContent(generated), generated);
                return;
            }
            if (generated.stage() != Stage.FINAL_REVIEW) {
                completeStoryboard(taskId, storyboardApprovalContent(generated), generated);
                return;
            }
            StoryboardCommitMode mode = commitMode(string(request.get("commitMode")));
            StoryboardRunSnapshot snapshot = commitFinalStoryboard(
                    conversation, generated.runId(), mode, listener);
            discardPreviousPublishedScenes(
                    conversation, string(request.get("previousRunId")), snapshot.runId());
            completeStoryboard(taskId, autoStoryboardContent(snapshot, mode), snapshot);
        } catch (Exception e) {
            log.warn("Storyboard start task failed: taskId={}, error={}", taskId, e.getMessage());
            if (isCancelRequested(taskId)) cancelRunning(taskId);
            else fail(taskId, e.getMessage() == null ? "分镜诊断失败" : e.getMessage());
        }
    }

    private void runStoryboardApprovalTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) return;
        Map<String, Object> request = readMap(task.getRequestJson());
        String requestedStage = string(request.get("stage"));
        String stageKey = "PSEUDO_REVIEW".equalsIgnoreCase(requestedStage)
                ? "final_generation" : "pseudo_planning";
        int claimed = claim(taskId, stageKey, 10);
        if (claimed == 0) return;
        try {
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
            if (conversation == null) {
                fail(taskId, "会话不存在");
                return;
            }
            StoryboardRunSnapshot snapshot;
            com.moon.moonagent.ai.storyboard.production.StoryboardProgressListener listener =
                    liveListener(taskId);
            if (Stage.FINAL_REVIEW.name().equalsIgnoreCase(requestedStage)) {
                String runId = string(request.get("runId"));
                // 重复提交入库 / 断点续跑：已完成直接幂等收尾，缺镜头的先自动补跑。
                Stage actual = storyboardProductionService.get(runId).stage();
                if (actual == Stage.COMPLETED) {
                    complete(taskId, "该阶段已确认，无需重复操作。",
                            List.of(), List.of(), "", false);
                    return;
                }
                if (actual == Stage.FINALIZING) {
                    snapshot = storyboardProductionService.retryIncompleteFinalShots(runId, listener);
                    if (snapshot.stage() != Stage.FINAL_REVIEW) {
                        updateTask(taskId, "progress", 90);
                        completeStoryboard(taskId, storyboardApprovalContent(snapshot), snapshot);
                        return;
                    }
                } else if (actual != Stage.FINAL_REVIEW) {
                    throw new IllegalStateException("当前阶段不可提交项目分镜: " + actual);
                }
                StoryboardCommitMode commitMode = latestStoryboardCommitMode(conversation.getId());
                snapshot = commitFinalStoryboard(conversation, runId, commitMode, listener);
                updateTask(taskId, "progress", 90);
                completeStoryboard(taskId, autoStoryboardContent(snapshot, commitMode), snapshot);
                return;
            } else {
                String runId = string(request.get("runId"));
                Approval approval = new Approval(
                        stage(requestedStage),
                        string(request.get("decision")),
                        "", List.of(), List.of());
                if (Stage.PLOT_REVIEW.name().equalsIgnoreCase(requestedStage)) {
                    // 旧确认卡片重复提交、或服务重启中断后续跑：按运行实际所处阶段继续，而不是直接失败。
                    Stage actual = storyboardProductionService.get(runId).stage();
                    if (actual == Stage.PLOT_REVIEW) {
                        snapshot = storyboardProductionService.approveToFinal(runId, approval, listener);
                    } else if (actual == Stage.PSEUDO_REVIEW) {
                        snapshot = storyboardProductionService.approve(runId,
                                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()),
                                listener);
                    } else if (actual == Stage.FINALIZING) {
                        snapshot = storyboardProductionService.retryIncompleteFinalShots(runId, listener);
                    } else if (actual == Stage.FINAL_REVIEW) {
                        StoryboardCommitMode commitMode =
                                latestStoryboardCommitMode(conversation.getId());
                        snapshot = commitFinalStoryboard(conversation, runId, commitMode, listener);
                        updateTask(taskId, "progress", 90);
                        completeStoryboard(
                                taskId, autoStoryboardContent(snapshot, commitMode), snapshot);
                        return;
                    } else if (actual == Stage.COMPLETED) {
                        complete(taskId, "该阶段已确认，无需重复操作。",
                                List.of(), List.of(), "", false);
                        return;
                    } else {
                        throw new IllegalStateException("当前阶段不可确认: " + actual);
                    }
                    if (snapshot.stage() == Stage.FINAL_REVIEW) {
                        StoryboardCommitMode commitMode =
                                latestStoryboardCommitMode(conversation.getId());
                        snapshot = commitFinalStoryboard(
                                conversation, snapshot.runId(), commitMode, listener);
                        updateTask(taskId, "progress", 90);
                        completeStoryboard(
                                taskId, autoStoryboardContent(snapshot, commitMode), snapshot);
                        return;
                    }
                } else {
                    snapshot = storyboardProductionService.approve(runId, approval, listener);
                }
            }
            updateTask(taskId, "progress", 90);
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            completeStoryboard(taskId, storyboardApprovalContent(snapshot), snapshot);
        } catch (Exception e) {
            log.warn("Storyboard approval task failed: taskId={}, error={}", taskId, e.getMessage());
            if (isCancelRequested(taskId)) cancelRunning(taskId);
            else fail(taskId, e.getMessage() == null ? "分镜生成失败" : e.getMessage());
        }
    }

    private void runStoryboardRevisionTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) return;
        int claimed = claim(taskId, "revision", 10);
        if (claimed == 0) return;
        try {
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            Map<String, Object> request = readMap(task.getRequestJson());
            AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
            if (conversation == null) {
                fail(taskId, "会话不存在");
                return;
            }
            String runId = string(request.get("runId"));
            if (runId.isBlank()) {
                runId = storyboardProductionService.getLatestOwned(
                        conversation.getUserId(),
                        conversation.getProjectId(),
                        conversation.getId()).runId();
            }
            String stageValue = string(request.get("stage"));
            Stage targetStage = stage(stageValue.isBlank() ? Stage.PSEUDO_REVIEW.name() : stageValue);
            StoryboardRunSnapshot snapshot = storyboardProductionService.revise(
                    runId,
                    targetStage,
                    string(request.get("instruction")),
                    string(request.get("shotId")),
                    liveListener(taskId));
            completeStoryboard(taskId,
                    targetStage == Stage.PLOT_REVIEW
                            ? storyboardReviewContent(snapshot) : storyboardApprovalContent(snapshot),
                    snapshot);
        } catch (Exception e) {
            log.warn("Storyboard revision task failed: taskId={}, error={}", taskId, e.getMessage());
            if (isCancelRequested(taskId)) cancelRunning(taskId);
            else fail(taskId, e.getMessage() == null ? "分镜修订失败" : e.getMessage());
        }
    }

    private StoryboardRunSnapshot commitFinalStoryboard(
            AgentConversation conversation,
            String runId) {
        return commitFinalStoryboard(conversation, runId, StoryboardCommitMode.OVERWRITE);
    }

    private StoryboardCommitMode latestStoryboardCommitMode(String conversationId) {
        AgentAsyncTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getConversationId, conversationId)
                        .eq(AgentAsyncTask::getToolName, AgentScopeStoryboardTools.START_STORYBOARD)
                        .orderByDesc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT 1"));
        if (task == null) return StoryboardCommitMode.APPEND;
        return commitMode(string(readMap(task.getRequestJson()).get("commitMode")));
    }

    private StoryboardRunSnapshot commitFinalStoryboard(
            AgentConversation conversation,
            String runId,
            StoryboardCommitMode commitMode) {
        return commitFinalStoryboard(conversation, runId, commitMode, null);
    }

    private StoryboardRunSnapshot commitFinalStoryboard(
            AgentConversation conversation,
            String runId,
            StoryboardCommitMode commitMode,
            com.moon.moonagent.ai.storyboard.production.StoryboardProgressListener listener) {
        if (storyboardClient == null) {
            throw new IllegalStateException("主后端分镜写入客户端未启用");
        }
        StoryboardRunSnapshot current = storyboardProductionService.getOwned(
                runId, conversation.getUserId(), conversation.getProjectId());
        if (current.stage() != Stage.FINAL_REVIEW) {
            throw new IllegalStateException("正式分镜尚不可提交: " + current.stage());
        }
        // 按 shotId 去重：历史运行可能存在跨分段编号冲突，重复项写进项目会造成分镜重影，
        // 也会让 shotId→sceneId 映射塌缩、触发「项目分镜映射不完整」。
        var seenShotIds = new java.util.HashSet<String>();
        List<StoryboardShot> finalShots = current.finalShots().stream()
                .filter(shot -> seenShotIds.add(shot.shotId()))
                .toList();
        StoryboardGenerationParams generationParams =
                storyboardParamsFor(conversation.getId());
        // 用户给了总时长就精确摊到每一镜（总和不偏离），否则各镜保留模型给的时长并吸附到合法档位
        List<Integer> durationPlan = generationParams.targetDurationSeconds() == null
                ? null
                : StoryboardParamResolver.perShotDurations(generationParams, finalShots.size(), 4);
        List<Scene> scenes = finalShots.stream()
                .map(shot -> sceneForStoryboard(conversation, shot, generationParams, durationPlan))
                .toList();
        // 该运行此前已写入过项目分镜（如改写剧情后重新确认）：先清掉自己写过的那批，
        // 否则按「追加」会把同一份分镜写第二遍。
        List<String> previouslyPublished = storyboardProductionService.publishedSceneIds(runId);
        for (String sceneId : previouslyPublished) {
            deleteProjectSceneQuietly(conversation.getProjectId(), sceneId);
        }
        if (listener != null) {
            listener.stream("final_generation", "正在写入项目分镜…\n");
        }
        List<String> sceneIds = commitMode == StoryboardCommitMode.APPEND
                ? storyboardClient.appendProjectScenes(conversation.getProjectId(), scenes)
                : storyboardClient.replaceProjectScenes(conversation.getProjectId(), scenes);
        if (sceneIds.size() != finalShots.size()) {
            // 写入不完整：回滚本次已写入的分镜，避免留下半份、重试时叠加成重复
            for (String sceneId : sceneIds) {
                deleteProjectSceneQuietly(conversation.getProjectId(), sceneId);
            }
            throw new IllegalStateException("项目分镜写入结果不完整");
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        for (int index = 0; index < finalShots.size(); index++) {
            mappings.put(finalShots.get(index).shotId(), sceneIds.get(index));
        }
        // 资产绑定落库：此前 sceneForStoryboard 只写文本列，分镜写进项目后资产关联整段丢失
        // （全库 scene_assets 长期只有 1 条）。挂的是 scene_reference_images：
        // 生图链路（sceneReferenceImageUrls）读的就是它，而 scene_assets.asset_id 有外键指向
        // 项目资产库 assets(id)，塞 agent_assets 的 id 会被外键直接拒掉。
        int linkedShots = linkShotAssets(current, finalShots, mappings);
        if (listener != null && linkedShots > 0) {
            listener.stream("final_generation", "已为 " + linkedShots + " 个镜头关联参考资产。\n");
        }
        try {
            StoryboardRunSnapshot completed = storyboardProductionService.markCompleted(
                    current.runId(), conversation.getUserId(), conversation.getProjectId(), mappings);
            if (listener != null) {
                listener.stream("final_generation",
                        "已写入项目分镜 " + finalShots.size() + " 个。\n");
            }
            return completed;
        } catch (RuntimeException e) {
            // 分镜已写进项目但运行态标记失败：回滚本次写入，避免重试时叠加成重复分镜
            for (String sceneId : sceneIds) {
                deleteProjectSceneQuietly(conversation.getProjectId(), sceneId);
            }
            throw e;
        }
    }

    /**
     * 把每镜绑定的资产挂到写库后的项目分镜上（写 {@code scene_reference_images}）。
     *
     * <p>为什么不是 {@code scene_assets}：那张表的 {@code asset_id} 有外键指向项目资产库
     * {@code assets(id)}，而分镜阶段绑定的是会话素材 {@code agent_assets} 的 id，写进去会被
     * 外键直接拒绝（实测 0 行、静默 warn）。而且生图链路
     * （{@code AgentScopeStoryboardTools.sceneReferenceImageUrls}）读的本身就是
     * {@code scene_reference_images}。
     *
     * <p>best-effort：单个镜头失败只记日志，绝不回滚已经写好的分镜。
     *
     * @return 成功挂上资产的镜头数
     */
    private int linkShotAssets(StoryboardRunSnapshot run, List<StoryboardShot> finalShots,
                               Map<String, String> mappings) {
        if (run == null || finalShots == null || finalShots.isEmpty()) return 0;
        // assetId → url：运行快照里就是请求带进去的那份资产（含 url），不必再查会话素材
        Map<String, String> urlsById = new LinkedHashMap<>();
        for (var asset : run.assets()) {
            if (asset == null) continue;
            String assetId = asset.assetId() == null ? "" : asset.assetId().trim();
            if (assetId.isEmpty() || asset.url() == null || asset.url().isBlank()) continue;
            urlsById.putIfAbsent(assetId, asset.url());
        }
        if (urlsById.isEmpty()) return 0;

        int linked = 0;
        for (StoryboardShot shot : finalShots) {
            String sceneId = mappings.get(shot.shotId());
            if (sceneId == null || sceneId.isBlank()) continue;
            List<String> assetIds;
            try {
                assetIds = storyboardProductionService
                        .referenceAssetIds(run.runId(), shot.shotId()).assetIds();
            } catch (Exception e) {
                log.warn("分镜资产解析失败: runId={}, shotId={}, error={}",
                        run.runId(), shot.shotId(), e.getMessage());
                continue;
            }
            List<Map<String, Object>> refs = new ArrayList<>();
            for (String raw : assetIds) {
                String assetId = normalizeAssetId(raw);
                String url = urlsById.get(assetId);
                if (url == null || url.isBlank()) continue;
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("url", url);
                ref.put("type", "image");
                ref.put("purpose", "image");
                ref.put("role", "REFERENCE");
                ref.put("sourceType", "agent_asset");
                ref.put("sourceId", assetId);
                refs.add(ref);
            }
            if (refs.isEmpty()) continue;
            try {
                storyboardClient.linkSceneReferences(sceneId, refs);
                linked++;
            } catch (Exception e) {
                log.warn("分镜资产关联失败: sceneId={}, shotId={}, error={}",
                        sceneId, shot.shotId(), e.getMessage());
            }
        }
        log.info("分镜资产关联完成: runId={}, shots={}, linked={}",
                run.runId(), finalShots.size(), linked);
        return linked;
    }

    /**
     * 从模型可能写歪的引用串里提取真实资产 id。
     *
     * <p>实测见过三种写法：
     * <ul>
     *   <li>{@code 27abfc16…｜仅用于镜头4的背影站位构图关系参考}（purpose 拼进 id）</li>
     *   <li>{@code 参考资产 da0917…：仅用于锁定少年背影造型}</li>
     *   <li>{@code [asset:da0917…] 参考神龙召唤的构图范式}</li>
     * </ul>
     * 先按 id 形状（16 位以上 hex 或 UUID）抓第一个匹配；抓不到再退回按分隔符截断。
     */
    private static final java.util.regex.Pattern ASSET_ID_PATTERN = java.util.regex.Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|[0-9a-fA-F]{16,})");

    static String normalizeAssetId(String raw) {
        if (raw == null) return "";
        java.util.regex.Matcher matcher = ASSET_ID_PATTERN.matcher(raw);
        if (matcher.find()) return matcher.group(1);
        String value = raw.trim();
        int cut = value.indexOf('｜');
        if (cut < 0) cut = value.indexOf('|');
        if (cut > 0) value = value.substring(0, cut).trim();
        int space = value.indexOf(' ');
        if (space > 0) value = value.substring(0, space).trim();
        return value;
    }

    private void deleteProjectSceneQuietly(String projectId, String sceneId) {
        if (sceneId == null || sceneId.isBlank() || storyboardClient == null) return;
        try {
            storyboardClient.deleteScene(sceneId, projectId);
        } catch (Exception e) {
            log.warn("项目分镜删除失败: projectId={}, sceneId={}, error={}",
                    projectId, sceneId, e.getMessage());
        }
    }

    private void runStoryboardVideoTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) return;
        int claimed = claim(taskId, "video_batch", 5);
        if (claimed == 0) return;
        try {
            if (storyboardVideoQueueService == null || storyboardClient == null) {
                fail(taskId, "分镜视频队列未启用");
                return;
            }
            AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
            if (conversation == null) {
                fail(taskId, "会话不存在");
                return;
            }
            Map<String, Object> request = readMap(task.getRequestJson());
            List<String> sceneIds = stringList(request.get("sceneIds"));
            if (sceneIds.isEmpty()) {
                fail(taskId, "没有可生成视频的分镜");
                return;
            }
            Map<String, Scene> scenesById = new LinkedHashMap<>();
            for (Scene scene : storyboardClient.getProjectScenesFresh(conversation.getProjectId())) {
                scenesById.put(scene.getId(), scene);
            }
            List<StoryboardVideoEnqueueItem> items = new ArrayList<>();
            for (String sceneId : sceneIds) {
                Scene scene = scenesById.get(sceneId);
                if (scene == null) continue;
                items.add(new StoryboardVideoEnqueueItem(
                        scene.getId(),
                        nonBlank(scene.getVideoPrompt(), scene.getScriptContent(), "分镜视频生成"),
                        scene.getVideoModel(),
                        scene.getVideoResolution(),
                        null,
                        scene.getVideoAspectRatio(),
                        scene.getDuration(),
                        scene.getNegativePrompt(),
                        null,
                        null,
                        null,
                        List.of()));
            }
            if (items.isEmpty()) {
                fail(taskId, "目标分镜不存在");
                return;
            }
            List<StoryboardVideoQueue> created = storyboardVideoQueueService.enqueue(
                    new StoryboardVideoEnqueueRequest(
                            conversation.getId(), conversation.getProjectId(), items));
            Set<String> targetSceneIds = new java.util.LinkedHashSet<>(sceneIds);
            Set<String> trackedQueueIds = created.stream()
                    .map(StoryboardVideoQueue::getId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (trackedQueueIds.isEmpty()) {
                storyboardVideoQueueService.listByProject(conversation.getProjectId(), null).stream()
                        .filter(row -> targetSceneIds.contains(row.getSceneId()))
                        .filter(row -> "queued".equals(row.getStatus()) || "running".equals(row.getStatus()))
                        .map(StoryboardVideoQueue::getId)
                        .forEach(trackedQueueIds::add);
            }
            if (trackedQueueIds.isEmpty()) {
                fail(taskId, "目标分镜没有可执行的分镜视频任务");
                return;
            }
            List<String> queueIds = List.copyOf(trackedQueueIds);
            Map<String, Object> progressResult = new LinkedHashMap<>();
            progressResult.put("queueIds", queueIds);
            updateTask(taskId, "result_json", writeJson(progressResult));

            int attempts = 0;
            Integer lastProgress = null;
            while (attempts++ < 8640) {
                if (isCancelRequested(taskId)) {
                    for (StoryboardVideoQueue row :
                            storyboardVideoQueueService.listByProject(conversation.getProjectId(), null)) {
                        if (targetSceneIds.contains(row.getSceneId())
                                && ("queued".equals(row.getStatus()) || "running".equals(row.getStatus()))) {
                            storyboardVideoQueueService.cancel(row.getId());
                        }
                    }
                    cancelRunning(taskId);
                    return;
                }
                List<StoryboardVideoQueue> rows =
                        storyboardVideoQueueService.listByProject(conversation.getProjectId(), null)
                                .stream()
                                .filter(row -> trackedQueueIds.contains(row.getId()))
                                .toList();
                long completed = rows.stream().filter(row -> "completed".equals(row.getStatus())).count();
                long failed = rows.stream()
                        .filter(row -> "failed".equals(row.getStatus())
                                || "cancelled".equals(row.getStatus()))
                        .count();
                long terminal = completed + failed;
                int progress = (int) Math.min(95, 5 + terminal * 90 / Math.max(1, trackedQueueIds.size()));
                if (!Integer.valueOf(progress).equals(lastProgress)) {
                    updateTask(taskId, "progress", progress,
                            "result_summary", "已完成 " + terminal + "/" + trackedQueueIds.size() + " 个分镜视频");
                    lastProgress = progress;
                }
                if (terminal >= trackedQueueIds.size()) {
                    String content = "分镜视频批量生成完成：成功 " + completed + " 个，失败 "
                            + failed + " 个。";
                    if (failed > 0) {
                        completePartialStoryboard(taskId, content, failed + " 个分镜视频生成失败");
                    } else {
                        complete(taskId, content, List.of(), List.of(), "", false, "completed");
                    }
                    return;
                }
                Thread.sleep(pollBackoff.delayMillis(attempts));
            }
            fail(taskId, "分镜视频批量任务等待超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelRunning(taskId);
        } catch (Exception e) {
            log.warn("Storyboard video task failed: taskId={}, error={}", taskId, e.getMessage());
            fail(taskId, e.getMessage() == null ? "分镜视频生成失败" : e.getMessage());
        }
    }

    /**
     * 重试整条分镜生成时，清掉上一次尝试已写入、且本次没有重写的项目分镜，
     * 让「失败→重试」等价于覆盖重写，而不是把同一份分镜追加两遍。
     */
    private void discardPreviousPublishedScenes(
            AgentConversation conversation, String previousRunId, String currentRunId) {
        if (previousRunId == null || previousRunId.isBlank() || previousRunId.equals(currentRunId)) {
            return;
        }
        List<String> previous = storyboardProductionService.publishedSceneIds(previousRunId);
        if (previous.isEmpty()) return;
        var current = new java.util.HashSet<>(
                storyboardProductionService.publishedSceneIds(currentRunId));
        for (String sceneId : previous) {
            if (current.contains(sceneId)) continue;
            deleteProjectSceneQuietly(conversation.getProjectId(), sceneId);
        }
    }

    private static StoryboardCommitMode commitMode(String value) {
        try {
            return StoryboardCommitMode.valueOf(
                    value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return StoryboardCommitMode.APPEND;
        }
    }

    /**
     * 把正式镜头转成项目分镜行，并把**生成参数落到分镜级**。
     *
     * <p>参数优先级已在 {@link StoryboardParamResolver} 收敛成「用户偏好 > 模型自选 > 网关默认」；
     * 这里只负责回填与时长吸附：每镜时长必须落在当前视频模型支持的档位内
     * （有总时长时按镜数均分再吸附），否则生成的视频会被上游直接拒。
     */
    private static Scene sceneForStoryboard(AgentConversation conversation,
                                            StoryboardShot shot,
                                            StoryboardGenerationParams params,
                                            List<Integer> durationPlan) {
        Scene scene = new Scene();
        scene.setProjectId(conversation.getProjectId());
        scene.setConversationId(conversation.getId());
        scene.setSceneNumber(shot.shotOrdinal());
        scene.setScriptContent(shot.sceneDescription());
        scene.setImagePrompt(shot.imagePrompt());
        scene.setVideoPrompt(shot.videoPrompt());
        scene.setNegativePrompt(shot.negativePrompt());
        scene.setContinuityNote(shot.continuityNote());
        scene.setCameraMovement(shot.cameraMovement());
        scene.setShotType(shot.shotType());
        scene.setDuration(durationFor(shot, params, durationPlan));
        scene.setImageStatus("pending");
        scene.setVideoStatus("pending");
        if (params != null) {
            if (!params.imageModel().isEmpty()) scene.setImageModel(params.imageModel());
            if (!params.imageSize().isEmpty()) scene.setImageSize(params.imageSize());
            if (!params.imageQuality().isEmpty()) scene.setImageQuality(params.imageQuality());
            if (params.imageN() != null) scene.setImageN(params.imageN());
            if (!params.videoModel().isEmpty()) scene.setVideoModel(params.videoModel());
            if (!params.videoResolution().isEmpty()) scene.setVideoResolution(params.videoResolution());
            if (!params.videoAspectRatio().isEmpty()) scene.setVideoAspectRatio(params.videoAspectRatio());
        }
        return scene;
    }

    /**
     * 单镜时长：有总时长计划就用计划值（保证总和不偏离目标），否则保留模型给的时长
     * 并吸附到当前视频模型的合法档位。
     */
    private static int durationFor(StoryboardShot shot, StoryboardGenerationParams params,
                                   List<Integer> durationPlan) {
        if (durationPlan != null && !durationPlan.isEmpty()) {
            int index = Math.min(Math.max(0, shot.shotOrdinal() - 1), durationPlan.size() - 1);
            return durationPlan.get(index);
        }
        return StoryboardParamResolver.snapDuration(
                params == null ? List.of() : params.allowedDurations(), shot.durationSeconds());
    }

    /**
     * 回读本次分镜的生成参数：写在 START 任务的 requestJson 里（写库可能发生在后续 approve 任务中）。
     * 读不到就返回 {@link StoryboardGenerationParams#EMPTY}，行为与改造前一致。
     */
    private StoryboardGenerationParams storyboardParamsFor(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return StoryboardGenerationParams.EMPTY;
        }
        try {
            AgentAsyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                    .eq(AgentAsyncTask::getConversationId, conversationId)
                    .eq(AgentAsyncTask::getToolName, AgentScopeStoryboardTools.START_STORYBOARD)
                    .orderByDesc(AgentAsyncTask::getCreatedAt)
                    .last("LIMIT 1"));
            if (task == null) return StoryboardGenerationParams.EMPTY;
            Object raw = readMap(task.getRequestJson()).get("generationParams");
            if (raw == null) return StoryboardGenerationParams.EMPTY;
            return JSON.convertValue(raw, StoryboardGenerationParams.class);
        } catch (Exception e) {
            log.warn("读取分镜生成参数失败(按无参数处理): conversationId={}, error={}",
                    conversationId, e.getMessage());
            return StoryboardGenerationParams.EMPTY;
        }
    }

    /** 任务 JSON 里的生成参数 → 领域对象（缺失/畸形一律退回空参数，不阻断任务）。 */
    private static StoryboardGenerationParams generationParamsOf(Object raw) {
        if (raw == null) return StoryboardGenerationParams.EMPTY;
        try {
            return JSON.convertValue(raw, StoryboardGenerationParams.class);
        } catch (Exception e) {
            log.warn("解析任务生成参数失败(按无参数处理): {}", e.getMessage());
            return StoryboardGenerationParams.EMPTY;
        }
    }

    private String startFollowUpVideo(
            AgentConversation conversation,
            Map<String, Object> request,
            List<String> urls) {
        if (!Boolean.TRUE.equals(request.get("followUpVideo")) || urls.size() < 2) return "";
        try {
            String prompt = string(request.get("videoPrompt"));
            if (prompt.isBlank()) {
                prompt = string(request.get("prompt"))
                        + "\n真实转播质感，首尾帧人物、球衣和场景保持连续。";
            }
            String duration = string(request.get("videoDuration"));
            String aspectRatio = string(request.get("videoAspectRatio"));
            String resolution = string(request.get("videoResolution"));
            Map<String, String> params = new LinkedHashMap<>();
            if (!resolution.isBlank()) params.put("resolution", resolution);
            OrchestrationRequest videoRequest =
                    new OrchestrationRequest(conversation, prompt, List.of(), null);
            String taskId = support.startVideoGenerationAsyncWithRole(
                    videoRequest,
                    prompt,
                    duration,
                    aspectRatio,
                    List.of(
                            ReferenceImage.ofFirstFrame(urls.get(0)),
                            ReferenceImage.ofLastFrame(urls.get(1))),
                    List.of(),
                    params);
            return taskId == null ? "" : taskId;
        } catch (Exception e) {
            log.warn("Batch image follow-up video failed: taskId={}, error={}",
                    conversation == null ? "" : conversation.getId(), e.getMessage());
            return "";
        }
    }

    private void completeStoryboard(String taskId, String content,
                                    StoryboardRunSnapshot snapshot) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task != null) {
            String messageId = persistStoryboardTerminalMessage(task, snapshot, content);
            if (!messageId.isBlank()) {
                taskEvents.publishWakeup(
                        task.getUserId(),
                        new AgentWakeupMessage(taskId, task.getConversationId(), messageId));
            }
        }
        if (snapshot != null && snapshot.stage() == Stage.FINALIZING
                && !snapshot.error().isBlank()) {
            completePartialStoryboard(taskId, content, snapshot.error());
            return;
        }
        complete(
                taskId,
                content,
                List.of(),
                List.of(),
                "",
                false,
                snapshot != null && snapshot.stage() == Stage.COMPLETED
                        ? "completed" : null);
    }

    private void completePartialStoryboard(String taskId, String content, String error) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || (!"running".equals(task.getStatus())
                && !"cancelling".equals(task.getStatus()))) return;
        Map<String, Object> result = new LinkedHashMap<>(readMap(task.getResultJson()));
        result.put("content", content);
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .in("status", List.of("running", "cancelling"))
                .set("status", "partial")
                .set("stage", "partial_failure")
                .set("progress", 100)
                .set("result_summary", error)
                .set("error", error)
                .set("result_json", writeJson(result))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(taskId);
        afterTerminal(taskId);
    }

    private void complete(
            String taskId,
            String content,
            List<String> assetIds,
            List<String> urls,
            String followUpTaskId) {
        complete(taskId, content, assetIds, urls, followUpTaskId, true);
    }

    private void complete(
            String taskId,
            String content,
            List<String> assetIds,
            List<String> urls,
            String followUpTaskId,
            boolean enqueueWakeup) {
        complete(taskId, content, assetIds, urls, followUpTaskId, enqueueWakeup, null);
    }

    private void complete(
            String taskId,
            String content,
            List<String> assetIds,
            List<String> urls,
            String followUpTaskId,
            boolean enqueueWakeup,
            String stageOverride) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || (!"running".equals(task.getStatus())
                && !"cancelling".equals(task.getStatus()))) return;
        String stage = stageOverride == null || stageOverride.isBlank()
                ? completedStage(task) : stageOverride;
        String resultSummary = AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(task.getToolName())
                ? "已生成 " + urls.size() + " 张图片"
                : summarize(content);
        // 保留运行期间写入的额外字段（如 runId），供失败后重试时回滚旧分镜
        Map<String, Object> result = new LinkedHashMap<>(readMap(task.getResultJson()));
        result.put("content", content);
        result.put("followUpTaskId", followUpTaskId == null ? "" : followUpTaskId);
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .in("status", List.of("running", "cancelling"))
                .set("status", "completed")
                .set("stage", stage)
                .set("progress", 100)
                .set("result_summary", resultSummary)
                .set("result_json", writeJson(result))
                .set("asset_ids_json", writeJson(assetIds))
                .set("result_urls_json", writeJson(urls))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(taskId);
        afterTerminal(taskId);
        if (enqueueWakeup) enqueueWakeupIfNeeded(taskId, content);
    }

    private void runSandboxScriptTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
        if (conversation == null) {
            fail(taskId, "会话不存在");
            return;
        }
        if (sandboxClient == null || sandboxPublisher == null) {
            fail(taskId, "沙箱执行器未启用");
            return;
        }
        Map<String, Object> request = readMap(task.getRequestJson());
        String script = string(request.get("script"));
        List<String> args = stringList(request.get("args"));
        int timeoutSeconds = integer(request.get("timeoutSeconds"), 300);
        try {
            updateTask(taskId, "status", "running", "stage", "executing", "progress", 10);
            SandboxExecutionResult result = sandboxSessionLock == null
                    ? sandboxClient.execute(conversation.getId(), script, args, timeoutSeconds)
                    : sandboxSessionLock.withLock(conversation.getId(),
                    () -> sandboxClient.execute(conversation.getId(), script, args, timeoutSeconds));
            if (sandboxSessionRegistry != null) {
                sandboxSessionRegistry.touch(conversation.getId(), 24);
            }
            updateTask(taskId, "stage", "publishing", "progress", 80,
                    "result_json", writeJson(SandboxArtifactPublisher.executionResult(result)));
            SandboxArtifactPublisher.PublishedArtifacts published =
                    sandboxPublisher.publish(conversation, taskId, result);
            java.util.LinkedHashSet<String> assetIds = new java.util.LinkedHashSet<>(published.assetIds());
            java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>(published.urls());
            // 脚本可能写出未在执行结果里声明、或在回合末扫描之后才落盘的文件。
            // 任务完成时对 output/ 做一次「只取新建」的补扫，保证产物在本次任务内即可见，
            // 不再依赖更晚的回合末扫描（避免 reconciler 与异步生产者竞态）。
            if (sandboxOutputReconciler != null) {
                SandboxArtifactPublisher.PublishedArtifacts swept =
                        sandboxOutputReconciler.reconcileNew(conversation, taskId);
                assetIds.addAll(swept.assetIds());
                urls.addAll(swept.urls());
            }
            if (result.timedOut()) {
                fail(taskId, "Python 脚本执行超时");
                return;
            }
            if (result.exitCode() != 0) {
                fail(taskId, result.stderr().isBlank()
                        ? "Python 脚本执行失败，退出码 " + result.exitCode()
                        : result.stderr());
                return;
            }
            String content = result.stdout().isBlank()
                    ? "Python 脚本执行完成。"
                    : result.stdout();
            complete(taskId, content, List.copyOf(assetIds), List.copyOf(urls), "", true, "completed");
        } catch (Exception error) {
            // 带 throwable 记录：这条 catch 之后还会调 fail(...) 收口，若只记 message，
            // 一旦收口路径再抛异常，日志里就只剩第二个异常，最初根因的栈会彻底丢失。
            log.warn("Sandbox task failed: taskId={}, error={}", taskId, error.getMessage(), error);
            fail(taskId, error.getMessage() == null ? "沙箱执行失败" : error.getMessage());
        }
    }

    private String persistStoryboardTerminalMessage(AgentAsyncTask task,
                                                    StoryboardRunSnapshot snapshot,
                                                    String content) {
        if (task == null || snapshot == null) return "";
        AgentMessage existing = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, task.getConversationId())
                .apply("metadata->>'asyncTaskId' = {0}", task.getId())
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();
        AgentMessage message = new AgentMessage();
        message.setConversationId(task.getConversationId());
        message.setRole("assistant");
        message.setContent(content);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "storyboard-review");
        metadata.put("asyncTaskId", task.getId());
        metadata.put("storyboardReview", Map.of(
                "runId", snapshot.runId(),
                "stage", snapshot.stage().name(),
                "version", snapshot.version(),
                "pending", true));
        metadata.put("asyncTask", Map.of(
                "taskId", task.getId(),
                "kind", task.getKind(),
                "status", "completed",
                "assetIds", List.of(),
                "resultUrls", List.of(),
                "error", ""));
        message.setMetadata(metadata);
        try {
            messageMapper.insert(message);
            return message.getId() == null ? "" : message.getId();
        } catch (Exception duplicate) {
            log.debug("Storyboard terminal message already exists: taskId={}", task.getId());
            AgentMessage fallback = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, task.getConversationId())
                    .apply("metadata->>'asyncTaskId' = {0}", task.getId())
                    .last("LIMIT 1"));
            return fallback == null || fallback.getId() == null ? "" : fallback.getId();
        }
    }

    private void persistStoryboardPlotMessage(String taskId, String script) {
        if (script == null || script.isBlank()) return;
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        String marker = taskId + ":plot";
        AgentMessage existing = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, task.getConversationId())
                .apply("metadata->>'asyncTaskId' = {0}", marker)
                .last("LIMIT 1"));
        if (existing != null) return;
        AgentMessage message = new AgentMessage();
        message.setConversationId(task.getConversationId());
        message.setRole("assistant");
        message.setContent("## 优化后的完整剧情\n\n" + script);
        message.setMetadata(Map.of(
                "kind", "storyboard-plot",
                "asyncTaskId", marker,
                "storyboardTaskId", taskId));
        try {
            messageMapper.insert(message);
            publish(taskId);
        } catch (Exception duplicate) {
            log.debug("Storyboard plot message already exists: taskId={}", taskId);
        }
    }

    private void fail(String taskId, String error) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || "completed".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
            return;
        }
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .ne("status", "completed")
                .set("status", "failed")
                .set("stage", "failed")
                .set("result_summary", error)
                .set("error", error)
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(taskId);
        afterTerminal(taskId);
        enqueueWakeupIfNeeded(taskId, "任务失败：" + error);
    }

    private void enqueueWakeupIfNeeded(String taskId, String content) {
        if (!properties.isWakeupEnabled() || !messageBus.enabled()) return;
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"long-tool".equals(task.getKind())) return;
        if (List.of("enqueued", "running", "consumed").contains(string(task.getWakeupStatus()))) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .set("wakeup_status", "pending")
                .setSql("wakeup_attempts = COALESCE(wakeup_attempts, 0) + 1")
                .set("wakeup_error", null)
                .set("updated_at", now));
        publish(taskId);

        String hint = "<system-notification>\n"
                + "Background task finished.\n"
                + "taskId: " + string(task.getId()) + "\n"
                + "title: " + string(task.getTitle()) + "\n"
                + "status: " + string(task.getStatus()) + "\n"
                + "stage: " + string(task.getStage()) + "\n"
                + "summary: " + string(task.getResultSummary()) + "\n\n"
                + "result:\n" + content + "\n"
                + "</system-notification>";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "hint");
        payload.put("id", "wakeup-" + taskId);
        payload.put("hint", hint);
        payload.put("source", "background_task");
        messageBus.messageBus()
                .inboxPush(task.getConversationId(), payload)
                .then(messageBus.messageBus().enqueueWakeup(
                        task.getUserId(), task.getConversationId(), "moon-aigc"))
                .subscribe(
                        ignored -> {
                            taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                                    .eq("id", taskId)
                                    .set("wakeup_status", "enqueued")
                                    .set("wakeup_enqueued_at", OffsetDateTime.now())
                                    .set("updated_at", OffsetDateTime.now()));
                            publish(taskId);
                        },
                        error -> {
                            String message = error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage();
                            taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                                    .eq("id", taskId)
                                    .set("wakeup_status", "failed")
                                    .set("wakeup_error", message)
                                    .set("updated_at", OffsetDateTime.now()));
                            publish(taskId);
                            log.warn("Storyboard wakeup enqueue failed: taskId={}, error={}",
                                    taskId, message);
                        });
    }

    private String persistTerminalMessage(
            AgentAsyncTask task,
            String content,
            String status,
            List<String> assetIds,
            List<String> resultUrls,
            String error) {
        if (task == null) return "";
        AgentMessage message = new AgentMessage();
        message.setConversationId(task.getConversationId());
        message.setRole("assistant");
        message.setContent(content);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "assistant-reply");
        metadata.put("asyncTaskId", task.getId());
        metadata.put("asyncTask", Map.of(
                "taskId", task.getId(),
                "kind", task.getKind(),
                "status", status,
                "assetIds", assetIds,
                "resultUrls", resultUrls,
                "error", error == null ? "" : error));
        message.setMetadata(metadata);
        try {
            messageMapper.insert(message);
        } catch (Exception duplicate) {
            AgentMessage existing = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, task.getConversationId())
                    .apply("metadata->>'asyncTaskId' = {0}", task.getId())
                    .last("LIMIT 1"));
            return existing == null ? "" : existing.getId();
        }
        return message.getId();
    }

    private int claim(String taskId, String stage, int progress) {
        int claimed = taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .eq("status", "queued")
                .set("status", "running")
                .set("stage", stage)
                .set("progress", progress)
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now()));
        if (claimed > 0) publish(taskId);
        return claimed;
    }

    private boolean isCancelRequested(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        return task != null && Boolean.TRUE.equals(task.getCancelRequested());
    }

    private void cancelRunning(String taskId) {
        updateTask(taskId,
                "status", "cancelled",
                "stage", "cancelled",
                "result_summary", "任务已取消",
                "completed_at", OffsetDateTime.now());
        afterTerminal(taskId);
    }

    private void updateTask(String taskId, Object... values) {
        if (taskId == null || taskId.isBlank() || values == null || values.length == 0) return;
        UpdateWrapper<AgentAsyncTask> update = new UpdateWrapper<AgentAsyncTask>().eq("id", taskId);
        for (int index = 0; index + 1 < values.length; index += 2) {
            update.set(String.valueOf(values[index]), values[index + 1]);
        }
        update.setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now());
        taskMapper.update(null, update);
        publish(taskId);
    }

    /** 累积实时输出并按节流推给前端；只保留最近一段，避免长任务吃内存。 */
    private com.moon.moonagent.ai.storyboard.production.StoryboardProgressListener liveListener(
            String taskId) {
        return new com.moon.moonagent.ai.storyboard.production.StoryboardProgressListener() {
            @Override
            public void update(String stage, int progress) {
                if (stage != null && !stage.isBlank()) liveStages.put(taskId, stage);
                updateTask(taskId, "stage", stage, "progress", progress);
                persistLiveSnapshot(taskId, false);
            }

            @Override
            public void plotReady(String script) {
                persistStoryboardPlotMessage(taskId, script);
            }

            @Override
            public void stream(String stage, String delta) {
                appendLiveStream(taskId, stage, delta);
            }

            @Override
            public void thinking(String stage, String delta) {
                appendLiveThinking(taskId, stage, delta);
            }
        };
    }

    private void appendLiveStream(String taskId, String stage, String delta) {
        appendLive(taskId, delta, false);
    }

    private void appendLiveThinking(String taskId, String stage, String delta) {
        appendLive(taskId, delta, true);
    }

    private void appendLive(String taskId, String delta, boolean thinking) {
        if (taskId == null || taskId.isBlank() || delta == null || delta.isEmpty()) return;
        try {
            Map<String, StringBuilder> buffers = thinking ? liveThinking : liveStreams;
            StringBuilder buffer = buffers.computeIfAbsent(taskId, key -> new StringBuilder());
            synchronized (buffer) {
                buffer.append(delta);
                int overflow = buffer.length() - LIVE_STREAM_MAX_CHARS;
                if (overflow > 0) buffer.delete(0, overflow);
            }
            long now = System.currentTimeMillis();
            Long publishedAt = liveStreamPublishedAt.get(taskId);
            if (publishedAt != null && now - publishedAt < LIVE_STREAM_PUBLISH_INTERVAL_MILLIS) return;
            liveStreamPublishedAt.put(taskId, now);
            pushLiveStream(taskId);
            persistLiveSnapshot(taskId, false);
        } catch (Exception e) {
            // 实时显示只是附加能力：推送失败绝不能把模型流/生成流程带崩
            log.debug("任务实时输出推送失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private static String currentLive(Map<String, StringBuilder> buffers, String taskId) {
        StringBuilder buffer = buffers.get(taskId);
        if (buffer == null) return "";
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    private void pushLiveStream(String taskId) {
        pushLiveStream(taskId, false);
    }

    private void pushLiveStream(String taskId, boolean done) {
        String userId = liveUserIds.get(taskId);
        if (userId == null) {
            AgentAsyncTask task = taskMapper.selectById(taskId);
            if (task == null) return;
            userId = task.getUserId();
            if (userId == null || userId.isBlank()) return;
            liveUserIds.put(taskId, userId);
        }
        long seq = liveSequences.computeIfAbsent(taskId, ignored -> new AtomicLong())
                .incrementAndGet();
        taskEvents.publishStream(
                userId,
                taskId,
                seq,
                liveStages.getOrDefault(taskId, ""),
                currentLive(liveStreams, taskId),
                currentLive(liveThinking, taskId),
                done,
                OffsetDateTime.now());
    }

    private void persistLiveSnapshot(String taskId, boolean force) {
        if (taskId == null || taskId.isBlank()) return;
        long now = System.currentTimeMillis();
        Long persistedAt = liveStreamPersistedAt.get(taskId);
        if (!force && persistedAt != null
                && now - persistedAt < LIVE_STREAM_PERSIST_INTERVAL_MILLIS) {
            return;
        }
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        Map<String, Object> result = new LinkedHashMap<>(readMap(task.getResultJson()));
        result.put("liveText", currentLive(liveStreams, taskId));
        result.put("liveThinking", currentLive(liveThinking, taskId));
        result.put("liveStage", liveStages.getOrDefault(taskId, ""));
        result.put("liveUpdatedAt", OffsetDateTime.now().toString());
        result.put("liveDone", isTerminal(task.getStatus()));
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .set("result_json", writeJson(result))
                .set("updated_at", OffsetDateTime.now()));
        liveStreamPersistedAt.put(taskId, now);
    }

    /** 任务进入终态：持久化最终尾部并通知前端完成。 */
    private void clearLiveStream(String taskId) {
        boolean hadStream = liveStreams.containsKey(taskId) || liveThinking.containsKey(taskId);
        persistLiveSnapshot(taskId, true);
        if (hadStream) pushLiveStream(taskId, true);
        liveStreams.remove(taskId);
        liveThinking.remove(taskId);
        liveStreamPublishedAt.remove(taskId);
        liveStreamPersistedAt.remove(taskId);
        liveStages.remove(taskId);
        liveSequences.remove(taskId);
        liveUserIds.remove(taskId);
    }

    private void publish(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
        if (conversation == null) return;
        try {
            taskEvents.publish(task.getUserId(), view(task, conversation));
        } catch (RuntimeException pushFailure) {
            // 推送只是「已经发生的事实」的通知：任务行在此之前已经写完。
            // 让它抛出去会制造两个后果：调用方原本的失败原因被第二个异常顶掉（排障时只剩 NPE，
            // 看不到真正的根因），以及 liveListener 的进度回调能把整条生成链打断。
            // 因此这里显式降级为告警，绝不向上传播。
            log.warn("Task snapshot push failed: taskId={}, error={}",
                    taskId, pushFailure.getMessage());
        }
        notifyTerminalTask(task);
    }

    /**
     * 任务进入终态时写一条站内通知（完成 / 部分完成 / 失败）。
     *
     * <p>用户主动取消不通知：那通常是他刚点过按钮，再弹一条只是噪音。
     * {@code dedupeKey} 由 taskId + status 组成，同一终态被多次 publish 也只落一条。
     */
    private void notifyTerminalTask(AgentAsyncTask task) {
        if (notificationService == null || task == null || !isTerminal(task.getStatus())) return;
        if ("cancelled".equals(task.getStatus())) return;
        try {
            String label = switch (task.getStatus()) {
                case "completed" -> "生成任务完成";
                case "partial" -> "生成任务部分完成";
                default -> "生成任务失败";
            };
            String suffix = task.getTitle() == null || task.getTitle().isBlank()
                    ? "" : " · " + task.getTitle();
            boolean succeeded = "completed".equals(task.getStatus()) || "partial".equals(task.getStatus());
            String body = succeeded
                    ? (task.getResultSummary() == null || task.getResultSummary().isBlank()
                        ? "任务已结束，点开查看结果。" : task.getResultSummary())
                    : (task.getError() == null || task.getError().isBlank()
                        ? "任务未完成，点开查看详情。" : task.getError());
            notificationService.create(
                    task.getUserId(),
                    "TASK_COMPLETED",
                    label + suffix,
                    body,
                    "conversation",
                    task.getConversationId(),
                    "task:" + task.getId() + ":" + task.getStatus());
        } catch (RuntimeException notificationFailure) {
            log.warn("Task notification failed: taskId={}, error={}",
                    task.getId(), notificationFailure.getMessage());
        }
    }

    private void releaseChildren(String parentTaskId) {
        // 只在任务进入终态时调用：顺手清掉实时输出缓冲并通知前端收起。
        clearLiveStream(parentTaskId);
        releaseWaitingChildren(parentTaskId);
    }

    /**
     * 释放父任务下仍处于 {@code waiting_parent} 的子行：父任务正常结束→子行排队执行，
     * 父任务取消→子行一并取消。每个子行的状态迁移统一走 {@link #releaseChildTask(String)}。
     *
     * <p>与 {@link #releaseChildren(String)} 分开是刻意的：后者还会 flush 实时输出快照，
     * 而「取消一个从未运行过的任务」没有任何实时输出可 flush——多写一次
     * {@code persistLiveSnapshot} 会为一次逻辑终态制造第二次 DB 写入和第二个任务视图事件。
     */
    private void releaseWaitingChildren(String parentTaskId) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null) return;
        List<AgentAsyncTask> children = taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getParentTaskId, parentTaskId)
                .eq(AgentAsyncTask::getStatus, "waiting_parent")
                .orderByAsc(AgentAsyncTask::getCreatedAt));
        for (AgentAsyncTask child : children) {
            releaseChildTask(child.getId());
        }
    }

    private void submit(AgentAsyncTask task) {
        if (task == null) return;
        if (AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(task.getToolName())) {
            submitAdmitted(() -> runBatchImageTask(task.getId()));
            return;
        }
        if (AgentScopeVideoTools.GENERATE_VIDEO.equals(task.getToolName())
                && task.getParentTaskId() != null && !task.getParentTaskId().isBlank()) {
            // 复合旅程的视频子任务：父任务按「子任务终态」编排，所以这里不能只创建就返回。
            submitAdmitted(() -> runJourneyVideoTask(task.getId()));
            return;
        }
        submitStoryboardTask(task);
    }

    private void submitAdmitted(Runnable task) {
        executor.submit(() -> {
            boolean acquired = false;
            try {
                executorAdmission.acquire();
                acquired = true;
                task.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) executorAdmission.release();
            }
        });
    }

    private void submitStoryboardTask(AgentAsyncTask task) {
        if (task == null) return;
        if (AgentScopeStoryboardTools.START_STORYBOARD.equals(task.getToolName())) {
            submitAdmitted(() -> runStoryboardStartTask(task.getId()));
        } else if (AgentScopeStoryboardTools.APPROVE_STORYBOARD.equals(task.getToolName())) {
            submitAdmitted(() -> runStoryboardApprovalTask(task.getId()));
        } else if (AgentScopeStoryboardTools.REVISE_STORYBOARD.equals(task.getToolName())) {
            submitAdmitted(() -> runStoryboardRevisionTask(task.getId()));
        } else if (AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO.equals(task.getToolName())
                || AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS.equals(task.getToolName())) {
            submitAdmitted(() -> runStoryboardVideoTask(task.getId()));
        } else if (AgentScopeSandboxTools.RUN_PYTHON_SCRIPT.equals(task.getToolName())) {
            submitAdmitted(() -> runSandboxScriptTask(task.getId()));
        } else {
            fail(task.getId(), "不支持的后台任务类型: " + task.getToolName());
        }
    }

    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "partial".equals(status)
                || "failed".equals(status) || "cancelled".equals(status);
    }

    private static String completedStage(AgentAsyncTask task) {
        if (AgentScopeStoryboardTools.START_STORYBOARD.equals(task.getToolName())) {
            Map<String, Object> request = readMap(task.getRequestJson());
            return Boolean.TRUE.equals(request.get("auto"))
                    ? "completed" : "waiting_review";
        }
        if (AgentScopeStoryboardTools.APPROVE_STORYBOARD.equals(task.getToolName())) {
            Map<String, Object> request = readMap(task.getRequestJson());
            return "FINAL_REVIEW".equalsIgnoreCase(string(request.get("stage")))
                    ? "completed" : "waiting_review";
        }
        if (AgentScopeStoryboardTools.REVISE_STORYBOARD.equals(task.getToolName())) {
            return "waiting_review";
        }
        return "completed";
    }

    private static void applyStatusFilter(
            LambdaQueryWrapper<AgentAsyncTask> query, String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) return;
        if ("active".equalsIgnoreCase(status)) {
            query.in(AgentAsyncTask::getStatus, List.of(
                    "waiting_parent", "queued", "running", "cancelling"));
            return;
        }
        if ("terminal".equalsIgnoreCase(status)) {
            query.in(AgentAsyncTask::getStatus, List.of("completed", "partial", "failed", "cancelled"));
            return;
        }
        query.eq(AgentAsyncTask::getStatus, status);
    }

    private AgentTaskView view(AgentAsyncTask task, AgentConversation conversation) {
        if (task == null || conversation == null) return null;
        List<String> assetIds = readStringList(task.getAssetIdsJson());
        List<String> urls = readStringList(task.getResultUrlsJson());
        Map<String, Object> result = readMap(task.getResultJson());
        Map<String, Object> request = readMap(task.getRequestJson());
        List<AgentTaskSlotView> slots = batchSlots(result.get("slots"));
        String toolName = string(task.getToolName());
        boolean active = !isTerminal(task.getStatus()) && !"cancelling".equals(task.getStatus());
        boolean autoStoryboard = Boolean.TRUE.equals(request.get("auto"));
        boolean canFollowUp = !autoStoryboard
                && (AgentScopeStoryboardTools.START_STORYBOARD.equals(toolName)
                || AgentScopeStoryboardTools.APPROVE_STORYBOARD.equals(toolName));
        return new AgentTaskView(
                task.getId(),
                task.getConversationId(),
                conversation.getProjectId(),
                task.getTitle() == null || task.getTitle().isBlank()
                        ? fallbackTitle(task) : task.getTitle(),
                toolName,
                task.getKind(),
                task.getStatus(),
                task.getStage() == null ? "" : task.getStage(),
                task.getProgress(),
                task.getResultSummary() == null ? "" : task.getResultSummary(),
                task.getError() == null ? "" : task.getError(),
                task.getParentTaskId() == null ? "" : task.getParentTaskId(),
                parentToolCallId(task),
                task.getAnchorMessageId() == null ? "" : task.getAnchorMessageId(),
                task.getVersion() == null ? 0L : task.getVersion(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                assetIds,
                urls,
                slots,
                string(result.get("content")),
                string(result.get("liveText")),
                string(result.get("liveThinking")),
                string(result.get("liveStage")),
                offsetDateTime(result.get("liveUpdatedAt")),
                active || "cancelling".equals(task.getStatus()),
                "partial".equals(task.getStatus())
                        || "failed".equals(task.getStatus())
                        || "cancelled".equals(task.getStatus()),
                canFollowUp);
    }

    /**
     * 工具卡归属 id：只对复合旅程生效，且只复用任务行上已有的 {@code toolCallId}，不新造关联 id。
     *
     * <ul>
     *   <li>旅程父任务 → 自身的 {@code toolCallId}（发起旅程的那次工具调用）</li>
     *   <li>旅程子任务 → 父任务的 {@code toolCallId}（步骤进度折到同一张工具卡下）</li>
     *   <li>非旅程任务（含 retry 产生的父子关系）→ 空字符串，前端行为与今天完全一致</li>
     * </ul>
     */
    private String parentToolCallId(AgentAsyncTask task) {
        if (task == null) return "";
        if (AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY.equals(string(task.getToolName()))) {
            return string(task.getToolCallId());
        }
        String parentTaskId = string(task.getParentTaskId());
        if (parentTaskId.isBlank()) return "";
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null
                || !AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY.equals(string(parent.getToolName()))) {
            return "";
        }
        return string(parent.getToolCallId());
    }

    private static Map<String, Object> viewMap(AgentTaskView task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.taskId());
        out.put("conversationId", task.conversationId());
        out.put("projectId", task.projectId());
        out.put("title", task.title());
        out.put("kind", task.kind());
        out.put("toolName", task.toolName());
        out.put("status", task.status());
        out.put("stage", task.stage());
        out.put("progress", task.progress());
        out.put("resultSummary", task.resultSummary());
        out.put("error", task.error());
        out.put("parentTaskId", task.parentTaskId());
        out.put("parentToolCallId", task.parentToolCallId());
        out.put("anchorMessageId", task.anchorMessageId());
        out.put("version", task.version());
        out.put("createdAt", task.createdAt());
        out.put("updatedAt", task.updatedAt());
        out.put("completedAt", task.completedAt());
        out.put("assetIds", task.assetIds());
        out.put("assetId", task.assetIds().isEmpty() ? "" : task.assetIds().getFirst());
        out.put("resultUrls", task.resultUrls());
        out.put("url", task.resultUrls().isEmpty() ? "" : task.resultUrls().getFirst());
        out.put("slots", task.slots());
        out.put("result", task.result());
        out.put("liveText", task.liveText());
        out.put("liveThinking", task.liveThinking());
        out.put("liveStage", task.liveStage());
        out.put("liveUpdatedAt", task.liveUpdatedAt());
        out.put("followUpTaskId", "");
        out.put("messageId", task.anchorMessageId());
        out.put("canCancel", task.canCancel());
        out.put("canRetry", task.canRetry());
        out.put("canFollowUp", task.canFollowUp());
        return out;
    }

    private static List<AgentTaskSlotView> batchSlots(Object value) {
        return AgentScopeBatchImageSlots.fromMaps(value).stream()
                .map(slot -> new AgentTaskSlotView(
                        slot.index(),
                        slot.label(),
                        slot.status(),
                        slot.prompt(),
                        slot.url(),
                        slot.assetId(),
                        slot.error(),
                        slot.completedAt()))
                .toList();
    }

    private static String fallbackTitle(AgentAsyncTask task) {
        if (AgentScopeStoryboardTools.START_STORYBOARD.equals(task.getToolName())) return "剧情与分镜诊断";
        if (AgentScopeStoryboardTools.APPROVE_STORYBOARD.equals(task.getToolName())) return "生成分镜";
        if (AgentScopeStoryboardTools.REVISE_STORYBOARD.equals(task.getToolName())) return "修订分镜";
        if (AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO.equals(task.getToolName())) return "生成分镜视频";
        if (AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS.equals(task.getToolName())) return "批量生成分镜视频";
        if (AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(task.getToolName())) return "生成图片";
        return "智能体任务";
    }

    private static String summarize(String content) {
        if (content == null || content.isBlank()) return "";
        String plain = content
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[*_`>#-]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return plain.length() <= 240 ? plain : plain.substring(0, 240) + "…";
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return JSON.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 供复合旅程编排器解析任务行 JSON（无状态纯函数，不暴露内部 ObjectMapper）。 */
    static Map<String, Object> parseJsonMap(String value) {
        return readMap(value);
    }

    /** 供复合旅程编排器解析任务行里的 URL 数组。 */
    static List<String> parseJsonList(String value) {
        return readStringList(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf).toList();
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .map(item -> integer(item, -1))
                .filter(item -> item >= 0)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static String sceneIdAt(Map<String, Object> request, int index) {
        List<String> ids = stringList(request.get("sceneIds"));
        return index >= 0 && index < ids.size() ? ids.get(index) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> effectiveSources(Map<String, Object> request,
                                                 List<String> fallback,
                                                 int index) {
        Object raw = request.get("sourceUrlsBySlot");
        if (raw instanceof List<?> rows && index >= 0 && index < rows.size()) {
            Object row = rows.get(index);
            if (row instanceof List<?> values) {
                return values.stream().filter(value -> value != null && !String.valueOf(value).isBlank())
                        .map(String::valueOf).toList();
            }
        }
        return fallback == null ? List.of() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<AssetRef> assetRefs(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .map(item -> new AssetRef(
                        string(item.get("assetId")),
                        string(item.get("type")),
                        string(item.get("url")),
                        string(item.get("purpose"))))
                .toList();
    }

    /**
     * 阶段 A 的审阅内容：剧情梗概 + 镜头大纲表 + run_id。
     *
     * <p>两点是硬要求：
     * <ul>
     *   <li><b>必须带 {@code run_id}</b>——前端「确认」按钮靠 {@code storyboardRunIdFromTask}
     *       从这段文字里正则取 runId，缺了按钮就不出现（旧文案只写剧情稿，等于把审批入口弄丢）；</li>
     *   <li><b>必须列出每一镜</b>——用户审批的是「拍哪几镜、每镜一句画面、带哪张图」，
     *       而不是一整篇剧本。</li>
     * </ul>
     */
    private static String storyboardReviewContent(StoryboardRunSnapshot snapshot) {
        String synopsis = snapshot.selectedScript().isBlank()
                ? snapshot.originalScript() : snapshot.selectedScript();
        StringBuilder content = new StringBuilder();
        content.append("# 镜头大纲\n\n")
                .append(synopsis.isBlank() ? "未返回剧情梗概。" : synopsis)
                .append("\n\n")
                .append("- run_id: `").append(snapshot.runId()).append("`\n")
                .append("- 镜头数: ").append(snapshot.pseudoShots().size()).append("\n\n");
        if (!snapshot.pseudoShots().isEmpty()) {
            content.append("| # | 画面 | 参考资产 |\n")
                    .append("| --- | --- | --- |\n");
            for (PseudoShot shot : snapshot.pseudoShots()) {
                content.append("| ").append(shot.shotOrdinal()).append(" | ")
                        .append(escapeTableCell(shot.action())).append(" | ")
                        .append(shot.referenceAssetIds().isEmpty()
                                ? "—" : shot.referenceAssetIds().size() + " 个")
                        .append(" |\n");
            }
            content.append('\n');
        }
        content.append("请确认大纲；确认后逐镜补全景别、运镜、提示词与时长，并写入项目分镜。");
        return content.toString();
    }

    /** Markdown 表格单元转义：竖线与换行会把表格撑破。 */
    private static String escapeTableCell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("|", "｜").replace("\n", " ").trim();
    }

    private static boolean isStoryboardVideoTool(String toolName) {
        return AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO.equals(toolName)
                || AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS.equals(toolName);
    }

    private static String autoStoryboardContent(
            StoryboardRunSnapshot snapshot, StoryboardCommitMode commitMode) {
        String action = commitMode == StoryboardCommitMode.OVERWRITE ? "覆盖" : "追加";
        StringBuilder content = new StringBuilder("# 分镜已写入项目\n\n");
        if (!snapshot.selectedScript().isBlank()) {
            content.append("## 优化剧情\n\n")
                    .append(snapshot.selectedScript())
                    .append("\n\n");
        }
        content.append("- run_id: `").append(snapshot.runId()).append("`\n")
                .append("- 已生成 ").append(snapshot.finalShots().size()).append(" 个镜头\n")
                .append("- 写库方式: ").append(action).append("\n")
                .append("- 当前状态: 已写入项目分镜\n\n")
                .append("## 接下来可以做什么\n\n")
                .append("- **查看和编辑分镜**：检查每个镜头的脚本、画面提示词、镜头类型、运镜和时长。\n")
                .append("- **补充参考素材**：为镜头上传图片或关联项目资产。\n")
                .append("- **生成分镜图片**：批量补齐全部缺图，或只重做指定镜头。\n")
                .append("- **生成分镜视频**：选择已有图片生成单个或批量视频。\n")
                .append("- **调整分镜结构**：修改、删除或重新排序项目分镜。\n\n")
                .append("使用下方“查看分镜列表”直接打开项目分镜；也可以点击“检查缺图/缺视频”或“生成全部缺图”。");
        return content.toString();
    }

    private static String storyboardApprovalContent(StoryboardRunSnapshot snapshot) {
        StringBuilder content = new StringBuilder();
        if (snapshot.stage() == Stage.PSEUDO_REVIEW) {
            content.append("# 结构化伪分镜已生成\n\n")
                    .append("- run_id: `").append(snapshot.runId()).append("`\n")
                    .append("- 镜头数: ").append(snapshot.pseudoShots().size()).append("\n\n")
                    .append("| 镜号 | 叙事目的 | 人物 | 地点/时间 | 冲突 | 动作 | 情绪 |\n")
                    .append("| --- | --- | --- | --- | --- | --- | --- |\n");
            for (PseudoShot shot : snapshot.pseudoShots()) {
                content.append("| `").append(shot.shotId()).append("` | ")
                        .append(tableCell(shot.narrativePurpose())).append(" | ")
                        .append(tableCell(String.join("、", shot.characters()))).append(" | ")
                        .append(tableCell(shot.location())).append(" / ")
                        .append(tableCell(shot.time())).append(" | ")
                        .append(tableCell(shot.conflict())).append(" | ")
                        .append(tableCell(shot.action())).append(" | ")
                        .append(tableCell(shot.emotion())).append(" |\n");
            }
            content.append("\n请确认伪分镜。确认后再生成正式镜头。");
            return content.toString();
        }

        content.append("# 正式分镜生成结果\n\n")
                .append("- run_id: `").append(snapshot.runId()).append("`\n")
                .append("- 当前阶段: `").append(snapshot.stage()).append("`\n")
                .append("- 已生成镜头: ").append(snapshot.finalShots().size()).append("/")
                .append(snapshot.pseudoShots().size()).append("\n\n")
                .append("| 镜号 | 画面 | 镜头类型 | 运镜 | 时长(秒) |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (StoryboardShot shot : snapshot.finalShots()) {
            content.append("| `").append(shot.shotId()).append("` | ")
                    .append(tableCell(shot.sceneDescription())).append(" | ")
                    .append(tableCell(shot.shotType())).append(" | ")
                    .append(tableCell(shot.cameraMovement())).append(" | ")
                    .append(shot.durationSeconds()).append(" |\n");
        }
        if (!snapshot.error().isBlank()) {
            content.append("\n## 需要重试\n\n").append(snapshot.error()).append("\n");
        }
        return content.toString();
    }

    /** Escape a value so it can sit inside a Markdown table cell on one line. */
    private static String tableCell(String value) {
        if (value == null) return "";
        return value.trim()
                .replace("|", "\\|")
                .replaceAll("\\s*\\R\\s*", " ");
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return "";
    }

    private static OffsetDateTime offsetDateTime(Object value) {
        String text = string(value);
        if (text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Stage stage(String value) {
        try {
            return Stage.valueOf(value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("无效分镜阶段: " + value);
        }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public record StoryboardImageTarget(
            String label,
            String shotId,
            String sceneId,
            String prompt,
            List<String> sourceUrls
    ) {
        public StoryboardImageTarget {
            label = label == null ? "" : label;
            shotId = shotId == null ? "" : shotId;
            sceneId = sceneId == null ? "" : sceneId;
            prompt = prompt == null ? "" : prompt;
            sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
        }
    }

    // ------------------------------------------------------------------
    // 复合旅程（produce_video_journey）：父任务 + 逐步子任务，全部复用既有 service 方法
    // ------------------------------------------------------------------

    /**
     * 启动复合旅程父任务。父任务本身不下发媒体生成，只按分支串行调度子任务；
     * 每个子任务带 {@code parentTaskId} 且初始为 {@code waiting_parent}，
     * 由父任务释放成 {@code queued} 后进入既有执行链。
     */
    public String startJourneyTask(AgentConversation conversation,
                                   String prompt,
                                   List<String> prompts,
                                   List<String> sourceUrls,
                                   String size,
                                   String quality,
                                   String model,
                                   String aspectRatio,
                                   String resolution,
                                   String duration,
                                   int imageCount,
                                   boolean explicitStoryboard,
                                   AgentScopeJourneyPlanner.JourneyPlan plan,
                                   String toolCallId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", prompt == null ? "" : prompt);
        request.put("prompts", prompts == null ? List.of() : prompts);
        request.put("sourceUrls", sourceUrls == null ? List.of() : sourceUrls);
        request.put("size", size == null ? "" : size);
        request.put("quality", quality == null ? "" : quality);
        request.put("model", model == null ? "" : model);
        request.put("aspectRatio", aspectRatio == null ? "" : aspectRatio);
        request.put("resolution", resolution == null ? "" : resolution);
        request.put("duration", duration == null ? "" : duration);
        request.put("imageCount", Math.max(1, imageCount));
        request.put("explicitStoryboard", explicitStoryboard);
        request.put("branch", plan == null ? "short" : AgentScopeJourneyPlanner.branchName(plan.branch()));
        request.put("steps", plan == null ? List.of() : plan.steps().stream()
                .map(step -> Map.<String, Object>of(
                        "order", step.order(),
                        "kind", step.kind(),
                        "title", step.title(),
                        "unitCount", step.unitCount(),
                        "unitLabel", step.unitLabel()))
                .toList());
        request.put("threshold", properties.getJourneyDirectVideoMaxSeconds());
        String requestJson = writeJson(request);
        String idempotencyKey = childIdempotencyKey(conversation.getId(), "journey", toolCallId,
                requestJson);
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY);
        task.setKind("journey");
        task.setStatus("queued");
        task.setTitle("复合旅程：" + (plan == null ? "生图 → 视频"
                : AgentScopeJourneyPlanner.branchLabel(plan.branch())));
        task.setStage("accepted");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        submitJourney(task);
        return task.getId();
    }

    /** 按需解析复合旅程编排器；未装配（单测直接 new）时返回 null。 */
    private AgentScopeJourneyRunner journeyRunner() {
        ObjectProvider<AgentScopeJourneyRunner> provider = journeyRunnerProvider;
        return provider == null ? null : provider.getIfAvailable();
    }

    /** 父任务编排器是否可用（测试装配下可能缺席）。 */
    boolean journeyEnabled() {
        return journeyRunner() != null;
    }

    /**
     * 启动/释放一个批次生图子任务（父任务为空时等价于既有 {@code startBatchImageTask}）。
     */
    public String startJourneyImageChild(String parentTaskId,
                                         int stepIndex,
                                         String stepKey,
                                         AgentConversation conversation,
                                         String prompt,
                                         List<String> prompts,
                                         List<String> sourceUrls,
                                         String size,
                                         String quality,
                                         int count,
                                         boolean followUpVideo,
                                         String videoPrompt,
                                         String videoDuration,
                                         String videoAspectRatio,
                                         String videoResolution,
                                         String toolCallId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", prompt == null ? "" : prompt);
        request.put("prompts", prompts == null ? List.of() : prompts);
        request.put("sourceUrls", sourceUrls == null ? List.of() : sourceUrls);
        request.put("size", size == null ? "" : size);
        request.put("quality", quality == null ? "" : quality);
        request.put("count", Math.max(1, count));
        request.put("followUpVideo", followUpVideo);
        request.put("videoPrompt", videoPrompt == null ? "" : videoPrompt);
        request.put("videoDuration", videoDuration == null ? "" : videoDuration);
        request.put("videoAspectRatio", videoAspectRatio == null ? "" : videoAspectRatio);
        request.put("videoResolution", videoResolution == null ? "" : videoResolution);
        request.put("journeyStep", stepKey == null ? "" : stepKey);
        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        task.setKind("image-batch");
        task.setTitle(count > 1 ? "生成 " + count + " 张图片" : "生成图片");
        return insertJourneyChild(task, parentTaskId, stepIndex, stepKey, request);
    }

    /** 启动/释放一个逐镜配图子任务。 */
    public String startJourneyStoryboardImageChild(String parentTaskId,
                                                   int stepIndex,
                                                   AgentConversation conversation,
                                                   List<StoryboardImageTarget> targets,
                                                   boolean overwrite,
                                                   String toolCallId) {
        List<StoryboardImageTarget> safeTargets = targets == null ? List.of() : targets;
        if (safeTargets.isEmpty()) {
            throw new IllegalStateException("没有需要生成图片的分镜");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", "分镜图片批量生成");
        request.put("prompts", safeTargets.stream().map(StoryboardImageTarget::prompt).toList());
        request.put("slotLabels", safeTargets.stream().map(StoryboardImageTarget::label).toList());
        request.put("sceneIds", safeTargets.stream().map(StoryboardImageTarget::sceneId).toList());
        request.put("shotIds", safeTargets.stream().map(StoryboardImageTarget::shotId).toList());
        request.put("sourceUrlsBySlot",
                safeTargets.stream().map(StoryboardImageTarget::sourceUrls).toList());
        request.put("sourceUrls", List.of());
        request.put("count", safeTargets.size());
        request.put("overwrite", overwrite);
        request.put("followUpVideo", false);
        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        task.setKind("image-batch");
        task.setTitle("逐镜配图 " + safeTargets.size() + " 个镜头");
        return insertJourneyChild(task, parentTaskId, stepIndex, "storyboard_image", request);
    }

    /** 启动/释放一个逐镜视频子任务。 */
    public String startJourneyStoryboardVideoChild(String parentTaskId,
                                                   int stepIndex,
                                                   AgentConversation conversation,
                                                   List<String> sceneIds,
                                                   boolean overwrite,
                                                   String toolCallId) {
        List<String> targets = sceneIds == null ? List.of() : sceneIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalStateException("没有可生成视频的分镜");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sceneIds", targets);
        request.put("overwrite", overwrite);
        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(targets.size() == 1
                ? AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO
                : AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS);
        task.setKind("long-tool");
        task.setTitle("逐镜视频 " + targets.size() + " 个镜头");
        return insertJourneyChild(task, parentTaskId, stepIndex, "storyboard_video", request);
    }

    /** 插入复合旅程的子任务行（{@code waiting_parent}）；已存在同键子任务时幂等返回。 */
    /** 启动/释放一个复合旅程的视频子任务（短分支第二步：图生视频）。 */
    String startJourneyVideoChild(String parentTaskId,
                                  int stepIndex,
                                  AgentConversation conversation,
                                  String prompt,
                                  List<String> imageUrls,
                                  String model,
                                  String resolution,
                                  String aspectRatio,
                                  String duration,
                                  String toolCallId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", prompt == null ? "" : prompt);
        request.put("imageUrls", imageUrls == null ? List.of() : imageUrls);
        request.put("model", model == null ? "" : model);
        request.put("resolution", resolution == null ? "" : resolution);
        request.put("aspectRatio", aspectRatio == null ? "" : aspectRatio);
        request.put("duration", duration == null ? "" : duration);
        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolCallId(toolCallId);
        task.setToolName(AgentScopeVideoTools.GENERATE_VIDEO);
        task.setKind("long-tool");
        task.setTitle("生成视频");
        return insertJourneyChild(task, parentTaskId, stepIndex, "video", request);
    }

    /**
     * 启动/释放复合旅程的分镜子任务。
     *
     * <p>复用 {@link #startStoryboardTask} 的请求结构与执行链
     * （{@code startAutoStoryboard} → 写项目分镜），因此与 {@code start_storyboard_production}
     * 工具完全同源；额外带上 {@code parentTaskId} 与 {@code waiting_parent}。
     */
    String startStoryboardChildTask(String parentTaskId,
                                    int stepIndex,
                                    AgentConversation conversation,
                                    String prompt,
                                    String creationType,
                                    String requestedShotCount,
                                    List<String> referenceUrls) {
        List<AssetRef> assets = new ArrayList<>();
        int index = 0;
        for (String url : referenceUrls == null ? List.<String>of() : referenceUrls) {
            if (url == null || url.isBlank()) continue;
            index++;
            assets.add(new AssetRef("reference-" + index, "image", url, ""));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("originalScript", prompt == null ? "" : prompt);
        request.put("creationType", creationType == null || creationType.isBlank()
                ? "video" : creationType);
        request.put("requestedShotCount", requestedShotCount == null ? "" : requestedShotCount);
        request.put("assets", assets);
        request.put("roleHints", List.of());
        request.put("commitMode", StoryboardCommitMode.APPEND.name());
        request.put("auto", true);
        AgentAsyncTask task = new AgentAsyncTask();
        task.setConversationId(conversation.getId());
        task.setUserId(conversation.getUserId());
        task.setSessionId(conversation.getId());
        task.setToolName(AgentScopeStoryboardTools.START_STORYBOARD);
        task.setKind("long-tool");
        task.setTitle("生成剧情、诊断与分镜");
        return insertJourneyChild(task, parentTaskId, stepIndex, "storyboard", request);
    }

    /**
     * 等待视频资产行进入终态（复用 AgentRuntimeSupport 的后台轮询写回结果）。
     *
     * <p>{@code AgentGenerationService.createVideoTaskWithRole} 先建资产行再回填 taskId，
     * 因此这里要同时容忍「行已存在但 taskId 未写」与「taskId 已写」两种中间态。
     */
    private AgentAsset awaitVideoAsset(String childTaskId, String videoTaskId) {
        long deadline = System.nanoTime() + VIDEO_ASSET_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (isCancelRequested(childTaskId)) return null;
            List<AgentAsset> found = assetMapper.selectList(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getTaskId, videoTaskId)
                    .last("LIMIT 1"));
            if (!found.isEmpty()) {
                AgentAsset asset = found.getFirst();
                if (AgentAsset.STATUS_DELETED.equals(asset.getStatus())) return null;
                if ("completed".equals(asset.getStatus())
                        || "failed".equals(asset.getStatus())) {
                    return asset;
                }
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 复合旅程的视频子任务：复用 {@code AgentRuntimeSupport.startVideoGenerationAsyncWithRole}
     * （与 {@code generate_video} 工具同一入口），完成后把视频 URL/资产写回子任务行，
     * 供父任务继续串行编排。
     */
    private void runJourneyVideoTask(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null || !"queued".equals(task.getStatus())) return;
        int claimed = claim(taskId, "submitting", 20);
        if (claimed == 0) return;
        try {
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
            if (conversation == null) {
                fail(taskId, "会话不存在");
                return;
            }
            Map<String, Object> request = readMap(task.getRequestJson());
            List<String> imageUrls = stringList(request.get("imageUrls"));
            Map<String, String> params = new LinkedHashMap<>();
            String model = string(request.get("model"));
            String resolution = string(request.get("resolution"));
            if (!model.isBlank()) params.put("model", model);
            if (!resolution.isBlank()) params.put("resolution", resolution);
            List<ReferenceImage> references = imageUrls.isEmpty()
                    ? List.of()
                    : List.of(ReferenceImage.ofFirstFrame(imageUrls.getFirst()));
            OrchestrationRequest videoRequest =
                    new OrchestrationRequest(conversation, string(request.get("prompt")), List.of(), null);
            String videoTaskId = support.startVideoGenerationAsyncWithRole(
                    videoRequest,
                    string(request.get("prompt")),
                    string(request.get("duration")),
                    string(request.get("aspectRatio")),
                    references,
                    List.of(),
                    params);
            if (videoTaskId == null || videoTaskId.isBlank()) {
                fail(taskId, "视频任务创建失败");
                return;
            }
            updateTask(taskId, "stage", "video_generating", "progress", 40);
            // 复用既有视频链路：AgentRuntimeSupport 已在后台轮询并把结果写回 agent_assets；
            // 这里只等该资产行进入终态，把 URL/资产 ID 记到子任务行，父任务据此继续编排。
            AgentAsset asset = awaitVideoAsset(taskId, videoTaskId);
            if (asset == null) {
                fail(taskId, "视频生成超时未返回结果");
                return;
            }
            if ("failed".equals(asset.getStatus())) {
                fail(taskId, asset.getError() == null || asset.getError().isBlank()
                        ? "视频生成失败" : asset.getError());
                return;
            }
            List<String> urls = new ArrayList<>();
            if (asset.getUrl() != null && !asset.getUrl().isBlank()) urls.add(asset.getUrl());
            for (String url : readStringList(asset.getResultUrls())) {
                if (!urls.contains(url)) urls.add(url);
            }
            List<String> assetIds = asset.getId() == null || asset.getId().isBlank()
                    ? List.of() : List.of(asset.getId());
            complete(taskId,
                    "视频已生成完成：" + (urls.isEmpty() ? "" : urls.getFirst()),
                    assetIds, urls, "", false);
        } catch (Exception e) {
            log.warn("Journey video task failed: taskId={}, error={}", taskId, e.getMessage());
            if (isCancelRequested(taskId)) cancelRunning(taskId);
            else fail(taskId, e.getMessage() == null ? "视频任务创建失败" : e.getMessage());
        }
    }

    private String insertJourneyChild(AgentAsyncTask task,
                                      String parentTaskId,
                                      int stepIndex,
                                      String stepKey,
                                      Map<String, Object> request) {
        String requestJson = writeJson(request);
        String idempotencyKey = childIdempotencyKey(task.getConversationId(),
                "journey-step-" + stepKey, String.valueOf(stepIndex), requestJson);
        AgentAsyncTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (existing != null) return existing.getId();

        task.setStatus("waiting_parent");
        task.setStage("waiting_parent");
        task.setProgress(0);
        task.setRequestJson(requestJson);
        task.setParentTaskId(parentTaskId);
        task.setIdempotencyKey(idempotencyKey);
        task.setVersion(0L);
        task.setCancelRequested(false);
        task.setUpdatedAt(OffsetDateTime.now());
        taskMapper.insert(task);
        publish(task.getId());
        return task.getId();
    }

    /** 单步键：父任务 + 步骤 + 请求内容的确定性哈希。 */
    private static String childIdempotencyKey(String conversationId, String stepKey,
                                              String discriminator, String requestJson) {
        return UUID.nameUUIDFromBytes((conversationId + "|" + stepKey + "|" + discriminator
                + "|" + requestJson).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 释放单个子任务：{@code waiting_parent → queued} 并进入既有执行链。
     *
     * @return true 表示本次调用把子任务推进到了可执行状态
     */
    boolean releaseChildTask(String childTaskId) {
        AgentAsyncTask child = taskMapper.selectById(childTaskId);
        if (child == null || !"waiting_parent".equals(child.getStatus())) return false;
        AgentAsyncTask parent = child.getParentTaskId() == null
                ? null : taskMapper.selectById(child.getParentTaskId());
        if (parent == null) return false;
        boolean cancelled = "cancelled".equals(parent.getStatus());
        OffsetDateTime now = OffsetDateTime.now();
        int updated = taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", childTaskId)
                .eq("status", "waiting_parent")
                .set("status", cancelled ? "cancelled" : "queued")
                .set("stage", cancelled ? "cancelled" : "accepted")
                .set("progress", cancelled ? null : 0)
                .set("completed_at", cancelled ? now : null)
                .set("updated_at", now)
                .setSql("version = COALESCE(version, 0) + 1"));
        if (updated == 0) return false;
        AgentAsyncTask refreshed = taskMapper.selectById(childTaskId);
        if (refreshed == null) return false;
        publish(refreshed.getId());
        if (!cancelled) submit(refreshed);
        return true;
    }

    /** 父任务写入一步进度（只更新 result_json 与阶段/进度，不改状态）。 */
    void noteJourneyStep(String parentTaskId, String stepKey, int progress, int totalSteps,
                         String childTaskId) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null) return;
        Map<String, Object> result = new LinkedHashMap<>(readMap(parent.getResultJson()));
        result.put("currentStep", stepKey == null ? "" : stepKey);
        result.put("stepIndex", Math.max(0, progress));
        result.put("totalSteps", Math.max(1, totalSteps));
        result.put("pendingChildId", childTaskId == null ? "" : childTaskId);
        // 步骤进度自身也带上工具卡归属：任务视图事件（snapshot）与持久化的 result_json 一致，
        // 前端直播与刷新后都能把步骤折回同一次 produce_video_journey 工具卡。
        result.put("parentToolCallId", parentToolCallId(parent));
        Object steps = readMap(parent.getRequestJson()).get("steps");
        if (steps != null) result.put("steps", steps);
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", parentTaskId)
                .in("status", List.of("queued", "running"))
                .set("stage", "journey:" + (stepKey == null ? "" : stepKey))
                .set("progress", totalSteps <= 0 ? 0
                        : (int) Math.round(100.0 * Math.max(0, progress) / totalSteps))
                .set("result_json", writeJson(result))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now()));
        publish(parentTaskId);
    }

    /** 父任务写入步骤产出的结构化数据（如分镜 runId、写入的场景 ID）。 */
    void noteJourneyData(String parentTaskId, String key, Object value) {
        if (key == null || key.isBlank()) return;
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null) return;
        Map<String, Object> result = new LinkedHashMap<>(readMap(parent.getResultJson()));
        result.put(key, value);
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", parentTaskId)
                .set("result_json", writeJson(result))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now()));
    }

    /** 读取父任务已写入的结构化数据。 */
    Map<String, Object> journeyData(String parentTaskId) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        return parent == null ? Map.of() : readMap(parent.getResultJson());
    }

    /**
     * 等待子任务进入终态，供父任务串行编排使用。
     *
     * <p>先注册终态回调再检查一次当前状态（避免「释放即完成」的竞态），
     * 超时后返回 {@code null}，由调用方按失败收口。
     */
    AgentAsyncTask awaitChildTask(String parentTaskId, String childTaskId, Duration timeout) {
        if (childTaskId == null || childTaskId.isBlank()) return null;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        registerTerminalListener(childTaskId, finished -> latch.countDown());
        long deadline = System.nanoTime()
                + (timeout == null ? Duration.ofHours(2) : timeout).toNanos();
        try {
            while (System.nanoTime() < deadline) {
                AgentAsyncTask child = taskMapper.selectById(childTaskId);
                if (child == null) return null;
                if (isTerminal(child.getStatus()) || "cancelling".equals(child.getStatus())) {
                    return child;
                }
                if (parentCancelled(parentTaskId)) {
                    cancelChildTask(child);
                    return taskMapper.selectById(childTaskId);
                }
                if (latch.await(500, TimeUnit.MILLISECONDS)) {
                    AgentAsyncTask settled = taskMapper.selectById(childTaskId);
                    if (settled != null && isTerminal(settled.getStatus())) return settled;
                }
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return taskMapper.selectById(childTaskId);
        } finally {
            terminalListeners.remove(childTaskId);
        }
    }

    /** 注册子任务终态回调。 */
    private void registerTerminalListener(String taskId, Consumer<AgentAsyncTask> listener) {
        if (taskId == null || taskId.isBlank() || listener == null) return;
        terminalListeners.computeIfAbsent(taskId, key -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(listener);
    }

    /** 任务终态统一出口：先发布，再通知等待它的父任务。 */
    private void notifyTerminalListeners(String taskId) {
        List<Consumer<AgentAsyncTask>> listeners = terminalListeners.remove(taskId);
        if (listeners == null || listeners.isEmpty()) return;
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        for (Consumer<AgentAsyncTask> listener : listeners) {
            try {
                listener.accept(task);
            } catch (RuntimeException error) {
                log.warn("Task terminal listener failed: taskId={}, error={}", taskId, error.getMessage());
            }
        }
    }

    boolean parentCancelled(String parentTaskId) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null) return true;
        if (Boolean.TRUE.equals(parent.getCancelRequested())) return true;
        return "cancelled".equals(parent.getStatus()) || "cancelling".equals(parent.getStatus());
    }

    /** 取消一个仍处于 waiting_parent 的子任务（父任务失败/取消时收口）。 */
    private void cancelChildTask(AgentAsyncTask child) {
        if (child == null) return;
        if (!"waiting_parent".equals(child.getStatus())) return;
        updateTask(child.getId(),
                "status", "cancelled",
                "stage", "cancelled",
                "result_summary", "父任务已结束，子任务未执行",
                "completed_at", OffsetDateTime.now());
    }

    /** 父任务成功收口（内容 + 产物）。 */
    void completeJourneyTask(String parentTaskId, String content,
                             List<String> assetIds, List<String> urls) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null || isTerminal(parent.getStatus())) return;
        List<String> safeAssetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        List<String> safeUrls = urls == null ? List.of() : List.copyOf(urls);
        Map<String, Object> result = new LinkedHashMap<>(readMap(parent.getResultJson()));
        result.put("content", content == null ? "" : content);
        result.put("pendingChildId", "");
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", parentTaskId)
                .in("status", List.of("queued", "running", "cancelling"))
                .set("status", "completed")
                .set("stage", "completed")
                .set("progress", 100)
                .set("result_summary", summarize(content))
                .set("result_json", writeJson(result))
                .set("asset_ids_json", writeJson(safeAssetIds))
                .set("result_urls_json", writeJson(safeUrls))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(parentTaskId);
        releaseChildren(parentTaskId);
        notifyTerminalListeners(parentTaskId);
        enqueueWakeupIfNeeded(parentTaskId, content);
    }

    /** 父任务取消收口：释放尚未启动的子任务，保证没有孤儿后台生成。 */
    void cancelJourneyTask(String parentTaskId, String summary) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null || isTerminal(parent.getStatus())) return;
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", parentTaskId)
                .in("status", List.of("queued", "running", "cancelling"))
                .set("status", "cancelled")
                .set("stage", "cancelled")
                .set("cancel_requested", true)
                .set("result_summary", summary == null || summary.isBlank() ? "任务已取消" : summary)
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(parentTaskId);
        // 与 cancelTask 同一口径：取消路径只做一次终态写入 + 子行收口，不 flush 实时快照。
        releaseWaitingChildren(parentTaskId);
        notifyTerminalListeners(parentTaskId);
    }

    /** 父任务失败收口。 */
    void failJourneyTask(String parentTaskId, String error) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null || isTerminal(parent.getStatus())) return;
        String message = error == null || error.isBlank() ? "复合旅程执行失败" : error;
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", parentTaskId)
                .in("status", List.of("queued", "running", "cancelling"))
                .set("status", "failed")
                .set("stage", "failed")
                .set("result_summary", message)
                .set("error", message)
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(parentTaskId);
        releaseChildren(parentTaskId);
        notifyTerminalListeners(parentTaskId);
        enqueueWakeupIfNeeded(parentTaskId, "任务失败：" + message);
    }

    /** 子任务终态：发布后唤醒等待它的父任务；再顺手释放该子任务自己的待执行子行。 */
    private void afterTerminal(String taskId) {
        releaseChildren(taskId);
        notifyTerminalListeners(taskId);
    }

    private void submitJourney(AgentAsyncTask task) {
        AgentScopeJourneyRunner runner = journeyRunner();
        if (runner == null) {
            fail(task.getId(), "复合旅程执行器未启用");
            return;
        }
        submitAdmitted(() -> runner.run(task.getId()));
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
