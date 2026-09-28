package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.recovery.AgentRecoverySource;
import com.moon.moonagent.ai.agent.recovery.RecoveryCandidate;
import com.moon.moonagent.ai.agent.recovery.RecoveryOutcome;
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
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardCommitMode;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.entity.AgentAsyncTask;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired(required = false)
    private transient StoryboardClient storyboardClient;

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
                        .in(AgentAsyncTask::getToolName, List.of(
                                AgentScopeStoryboardTools.START_STORYBOARD,
                                AgentScopeStoryboardTools.APPROVE_STORYBOARD))
                        .eq(AgentAsyncTask::getStatus, "queued")
                        .orderByAsc(AgentAsyncTask::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .forEach(task -> candidates.add(candidate(task)));
        taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                        .eq(AgentAsyncTask::getKind, "long-tool")
                        .in(AgentAsyncTask::getToolName, List.of(
                                AgentScopeStoryboardTools.START_STORYBOARD,
                                AgentScopeStoryboardTools.APPROVE_STORYBOARD))
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
            fail(task.getId(), "服务重启后无法安全确认原任务状态，请重新发起。");
            return RecoveryOutcome.completed();
        }
        if ("queued".equals(task.getStatus())) {
            if ("long-tool".equals(task.getKind())) {
                if (AgentScopeStoryboardTools.APPROVE_STORYBOARD.equals(task.getToolName())) {
                    executor.submit(() -> runStoryboardApprovalTask(task.getId()));
                } else {
                    executor.submit(() -> runStoryboardStartTask(task.getId()));
                }
                return RecoveryOutcome.deferred("storyboard task submitted");
            }
            executor.submit(() -> runBatchImageTask(task.getId()));
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
        executor.submit(() -> runBatchImageTask(task.getId()));
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
        executor.submit(() -> runBatchImageTask(task.getId()));
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
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("originalScript", productionRequest.originalScript());
        request.put("creationType", productionRequest.creationType());
        request.put("requestedShotCount", productionRequest.requestedShotCount());
        request.put("assets", productionRequest.assets());
        request.put("roleHints", productionRequest.roleHints());
        request.put("commitMode",
                (commitMode == null ? StoryboardCommitMode.APPEND : commitMode).name());
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
        executor.submit(() -> runStoryboardStartTask(task.getId()));
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
        executor.submit(() -> runStoryboardApprovalTask(task.getId()));
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
        List<AgentTaskView> records = taskMapper.selectList(query
                        .orderByDesc(AgentAsyncTask::getUpdatedAt)
                        .last("LIMIT " + safeSize + " OFFSET " + ((safePage - 1) * safeSize)))
                .stream()
                .map(task -> {
                    AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
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
        return taskMapper.selectList(query).stream()
                .map(task -> {
                    AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
                    if (conversation == null || !userId.equals(conversation.getUserId())) return null;
                    return view(task, conversation);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
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
            releaseChildren(taskId);
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
                    stringList(request.get("roleHints")));
            boolean auto = Boolean.TRUE.equals(request.get("auto"));
            StoryboardRunSnapshot generated = auto
                    ? storyboardProductionService.startAutoStoryboard(
                    productionRequest,
                    new com.moon.moonagent.ai.storyboard.production.StoryboardProgressListener() {
                        @Override
                        public void update(String stage, int progress) {
                            updateTask(taskId, "stage", stage, "progress", progress);
                        }

                        @Override
                        public void plotReady(String script) {
                            persistStoryboardPlotMessage(taskId, script);
                        }
                    })
                    : storyboardProductionService.start(productionRequest);
            if (isCancelRequested(taskId)) {
                cancelRunning(taskId);
                return;
            }
            if (!auto) {
                completeStoryboard(taskId, storyboardReviewContent(generated), generated);
                return;
            }
            if (generated.stage() != Stage.FINAL_REVIEW) {
                completeStoryboard(taskId, storyboardApprovalContent(generated), generated);
                return;
            }
            StoryboardCommitMode mode = commitMode(string(request.get("commitMode")));
            StoryboardRunSnapshot snapshot = commitFinalStoryboard(conversation, generated.runId(), mode);
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
            if (Stage.FINAL_REVIEW.name().equalsIgnoreCase(requestedStage)) {
                snapshot = commitFinalStoryboard(
                        conversation,
                        string(request.get("runId")),
                        latestStoryboardCommitMode(conversation.getId()));
                updateTask(taskId, "progress", 90);
                completeStoryboard(
                        taskId,
                        autoStoryboardContent(snapshot, latestStoryboardCommitMode(conversation.getId())),
                        snapshot);
                return;
            } else {
                Approval approval = new Approval(
                        stage(string(request.get("stage"))),
                        string(request.get("decision")),
                        "", List.of(), List.of());
                if (Stage.PLOT_REVIEW.name().equalsIgnoreCase(requestedStage)) {
                    snapshot = storyboardProductionService.approveToFinal(
                            string(request.get("runId")), approval);
                    if (snapshot.stage() == Stage.FINAL_REVIEW) {
                        StoryboardCommitMode commitMode =
                                latestStoryboardCommitMode(conversation.getId());
                        snapshot = commitFinalStoryboard(
                                conversation, snapshot.runId(), commitMode);
                        updateTask(taskId, "progress", 90);
                        completeStoryboard(
                                taskId, autoStoryboardContent(snapshot, commitMode), snapshot);
                        return;
                    }
                } else {
                    snapshot = storyboardProductionService.approve(
                            string(request.get("runId")), approval);
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
                    string(request.get("shotId")));
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
        if (storyboardClient == null) {
            throw new IllegalStateException("主后端分镜写入客户端未启用");
        }
        StoryboardRunSnapshot current = storyboardProductionService.getOwned(
                runId, conversation.getUserId(), conversation.getProjectId());
        if (current.stage() != Stage.FINAL_REVIEW) {
            throw new IllegalStateException("正式分镜尚不可提交: " + current.stage());
        }
        List<Scene> scenes = current.finalShots().stream()
                .map(shot -> sceneForStoryboard(conversation, shot))
                .toList();
        List<String> sceneIds = commitMode == StoryboardCommitMode.APPEND
                ? storyboardClient.appendProjectScenes(conversation.getProjectId(), scenes)
                : storyboardClient.replaceProjectScenes(conversation.getProjectId(), scenes);
        if (sceneIds.size() != current.finalShots().size()) {
            throw new IllegalStateException("项目分镜写入结果不完整");
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        for (int index = 0; index < current.finalShots().size(); index++) {
            mappings.put(current.finalShots().get(index).shotId(), sceneIds.get(index));
        }
        return storyboardProductionService.markCompleted(
                current.runId(), conversation.getUserId(), conversation.getProjectId(), mappings);
    }

    private static StoryboardCommitMode commitMode(String value) {
        try {
            return StoryboardCommitMode.valueOf(
                    value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return StoryboardCommitMode.APPEND;
        }
    }

    private static Scene sceneForStoryboard(AgentConversation conversation,
                                            StoryboardShot shot) {
        Scene scene = new Scene();
        scene.setProjectId(conversation.getProjectId());
        scene.setConversationId(conversation.getId());
        scene.setSceneNumber(shot.shotOrdinal());
        scene.setScriptContent(shot.sceneDescription());
        scene.setImagePrompt(shot.imagePrompt());
        scene.setVideoPrompt(shot.videoPrompt());
        scene.setNegativePrompt(shot.negativePrompt());
        scene.setCameraMovement(shot.cameraMovement());
        scene.setShotType(shot.shotType());
        scene.setDuration(shot.durationSeconds());
        scene.setImageStatus("pending");
        scene.setVideoStatus("pending");
        return scene;
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
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .in("status", List.of("running", "cancelling"))
                .set("status", "partial")
                .set("stage", "partial_failure")
                .set("progress", 100)
                .set("result_summary", error)
                .set("error", error)
                .set("result_json", writeJson(Map.of("content", content)))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(taskId);
        releaseChildren(taskId);
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
        taskMapper.update(null, new UpdateWrapper<AgentAsyncTask>()
                .eq("id", taskId)
                .in("status", List.of("running", "cancelling"))
                .set("status", "completed")
                .set("stage", stage)
                .set("progress", 100)
                .set("result_summary", resultSummary)
                .set("result_json", writeJson(Map.of(
                        "content", content,
                        "followUpTaskId", followUpTaskId == null ? "" : followUpTaskId)))
                .set("asset_ids_json", writeJson(assetIds))
                .set("result_urls_json", writeJson(urls))
                .setSql("version = COALESCE(version, 0) + 1")
                .set("updated_at", OffsetDateTime.now())
                .set("completed_at", OffsetDateTime.now()));
        publish(taskId);
        releaseChildren(taskId);
        if (enqueueWakeup) enqueueWakeupIfNeeded(taskId, content);
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
        releaseChildren(taskId);
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
        releaseChildren(taskId);
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

    private void publish(String taskId) {
        AgentAsyncTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        AgentConversation conversation = conversationMapper.selectById(task.getConversationId());
        if (conversation == null) return;
        taskEvents.publish(task.getUserId(), view(task, conversation));
    }

    private void releaseChildren(String parentTaskId) {
        AgentAsyncTask parent = taskMapper.selectById(parentTaskId);
        if (parent == null) return;
        List<AgentAsyncTask> children = taskMapper.selectList(new LambdaQueryWrapper<AgentAsyncTask>()
                .eq(AgentAsyncTask::getParentTaskId, parentTaskId)
                .eq(AgentAsyncTask::getStatus, "waiting_parent")
                .orderByAsc(AgentAsyncTask::getCreatedAt));
        for (AgentAsyncTask child : children) {
            if ("cancelled".equals(parent.getStatus())) {
                updateTask(child.getId(), "status", "cancelled", "stage", "cancelled",
                        "completed_at", OffsetDateTime.now());
                continue;
            }
            updateTask(child.getId(), "status", "queued", "stage", "accepted", "progress", 0);
            submit(taskMapper.selectById(child.getId()));
        }
    }

    private void submit(AgentAsyncTask task) {
        if (task == null) return;
        if (AgentScopeImageTools.GENERATE_IMAGE_BATCH.equals(task.getToolName())) {
            executor.submit(() -> runBatchImageTask(task.getId()));
            return;
        }
        submitStoryboardTask(task);
    }

    private void submitStoryboardTask(AgentAsyncTask task) {
        if (task == null) return;
        if (AgentScopeStoryboardTools.START_STORYBOARD.equals(task.getToolName())) {
            executor.submit(() -> runStoryboardStartTask(task.getId()));
        } else if (AgentScopeStoryboardTools.APPROVE_STORYBOARD.equals(task.getToolName())) {
            executor.submit(() -> runStoryboardApprovalTask(task.getId()));
        } else if (AgentScopeStoryboardTools.REVISE_STORYBOARD.equals(task.getToolName())) {
            executor.submit(() -> runStoryboardRevisionTask(task.getId()));
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

    private static AgentTaskView view(AgentAsyncTask task, AgentConversation conversation) {
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
                task.getAnchorMessageId() == null ? "" : task.getAnchorMessageId(),
                task.getVersion() == null ? 0L : task.getVersion(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                assetIds,
                urls,
                slots,
                string(result.get("content")),
                active || "cancelling".equals(task.getStatus()),
                "partial".equals(task.getStatus())
                        || "failed".equals(task.getStatus())
                        || "cancelled".equals(task.getStatus()),
                canFollowUp);
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

    private static String storyboardReviewContent(StoryboardRunSnapshot snapshot) {
        PlotAssessment assessment = snapshot.assessment();
        StringBuilder content = new StringBuilder();
        content.append("# 分镜诊断完成\n\n")
                .append("- run_id: `").append(snapshot.runId()).append("`\n")
                .append("- 当前阶段: `").append(snapshot.stage()).append("`\n")
                .append("- 剧情判断: ").append(assessment.complete() ? "完整" : "需要补充").append("\n\n");
        appendList(content, "缺口", assessment.missingElements());
        appendList(content, "风险", assessment.risks());
        if (!assessment.summary().isBlank()) {
            content.append("## 诊断结论\n\n").append(assessment.summary()).append("\n\n");
        }
        if (!assessment.expansionPlan().isBlank()) {
            content.append("## 建议修正\n\n").append(assessment.expansionPlan()).append("\n\n");
        }
        content.append("## 当前剧情稿\n\n")
                .append(snapshot.selectedScript().isBlank() ? "未返回修正稿。" : snapshot.selectedScript())
                .append("\n\n");
        if (!snapshot.expansionScript().isBlank()
                && !snapshot.expansionScript().equals(snapshot.selectedScript())) {
            content.append("## 备用完整稿\n\n").append(snapshot.expansionScript()).append("\n\n");
        }
        if (!snapshot.roles().isEmpty()) {
            content.append("## 专业角色匹配\n\n");
            for (RoleProfile role : snapshot.roles()) {
                content.append("- ").append(role.label()).append("（`").append(role.id()).append("`）");
                if (!role.instructions().isBlank()) {
                    content.append("：").append(role.instructions());
                }
                content.append("\n");
            }
            content.append("\n");
        }
        content.append("请确认是否采用当前剧情稿；确认后继续生成正式分镜。");
        return content.toString();
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
                    .append("- 镜头数: ").append(snapshot.pseudoShots().size()).append("\n\n");
            for (PseudoShot shot : snapshot.pseudoShots()) {
                content.append("## ").append(shot.shotId()).append("\n\n")
                        .append("- 叙事目的: ").append(shot.narrativePurpose()).append("\n")
                        .append("- 人物: ").append(String.join("、", shot.characters())).append("\n")
                        .append("- 地点/时间: ").append(shot.location()).append(" / ")
                        .append(shot.time()).append("\n")
                        .append("- 冲突: ").append(shot.conflict()).append("\n")
                        .append("- 动作: ").append(shot.action()).append("\n")
                        .append("- 情绪: ").append(shot.emotion()).append("\n\n");
            }
            content.append("请确认伪分镜。确认后再生成正式镜头。");
            return content.toString();
        }

        content.append("# 正式分镜生成结果\n\n")
                .append("- run_id: `").append(snapshot.runId()).append("`\n")
                .append("- 当前阶段: `").append(snapshot.stage()).append("`\n")
                .append("- 已生成镜头: ").append(snapshot.finalShots().size()).append("/")
                .append(snapshot.pseudoShots().size()).append("\n\n");
        for (StoryboardShot shot : snapshot.finalShots()) {
            content.append("- `").append(shot.shotId()).append("`：")
                    .append(shot.sceneDescription())
                    .append("，").append(shot.shotType())
                    .append("，").append(shot.cameraMovement())
                    .append("，").append(shot.durationSeconds()).append(" 秒\n");
        }
        if (!snapshot.error().isBlank()) {
            content.append("\n## 需要重试\n\n").append(snapshot.error()).append("\n");
        }
        return content.toString();
    }

    private static void appendList(StringBuilder content, String title, List<String> values) {
        if (values == null || values.isEmpty()) return;
        content.append("## ").append(title).append("\n\n");
        values.forEach(value -> content.append("- ").append(value).append("\n"));
        content.append("\n");
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
