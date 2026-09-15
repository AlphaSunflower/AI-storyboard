package com.moon.xiaoyuecanvas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.ai.storyboard.StoryboardPlanningService;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.service.AgentConversationService;
import com.moon.moonagent.service.DesignAssistantCapability;
import com.moon.moonagent.service.ProjectService;
import com.moon.xiaoyuecanvas.dto.request.JobRequest;
import com.moon.xiaoyuecanvas.dto.response.CapabilityDescriptor;
import com.moon.xiaoyuecanvas.dto.response.JobSubmitResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.entity.CanvasJobEvent;
import com.moon.xiaoyuecanvas.mapper.CanvasJobMapper;
import com.moon.xiaoyuecanvas.repository.CanvasJobRepository;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import com.storyboard.common.BusinessException;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordTypes;
import com.storyboard.common.generation.GenerationRecordUpdate;
import com.storyboard.common.generation.GenerationRecordWriter;
import com.storyboard.common.generation.GenerationReference;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Morrow 设计助理作业服务：按 capabilityId 分发到 agent-core 的 AI 生成服务，落 canvas_jobs /
 * canvas_job_events / agent_assets，把进度/素材以 SSE 事件推给前端。
 *
 * <p>执行可靠性：
 * <ul>
 *   <li>queued 作业通过 canvas_jobs lease CAS 认领；有效 running lease 不会被重复执行。</li>
 *   <li>过期 running 作业先转 interrupted；带 provider_task_id 的视频任务可恢复轮询。</li>
 *   <li>终态状态更新、事件追加、last_event_seq 递增在同一事务内完成，SSE 只在事务返回后 best-effort 发送。</li>
 *   <li>SSE 断线重连按 jobId:seq 重放 canvas_job_events，并尊重 Last-Event-ID。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DesignJobService {

    private static final Logger log = LoggerFactory.getLogger(DesignJobService.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(30);
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final long VIDEO_POLL_TIMEOUT_MS = 15 * 60 * 1000L;

    private final CanvasJobMapper canvasJobMapper;
    private final CanvasJobRepository canvasJobRepository;
    private final AgentAssetMapper agentAssetMapper;
    private final CanvasAssetService canvasAssetService;
    private final AgentConversationMapper agentConversationMapper;
    private final AgentConversationService agentConversationService;
    private final DesignAssistantCapability designAssistantCapability;
    private final StoryboardPlanningService storyboardPlanningService;
    private final ProjectService projectService;
    private final ImageGenerationService imageGenerationService;
    private final VideoGenerationService videoGenerationService;
    private final GenerationRecordWriter generationRecordWriter;

    /** jobId -> 当前 SSE 发射器（订阅后置；重连替换）。 */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    /** jobId -> 事件追加/重放锁，保证 snapshot 与实时事件之间没有游标缺口。 */
    private final Map<String, ReentrantLock> jobLocks = new ConcurrentHashMap<>();

    private static final List<CapabilityDescriptor> CAPABILITIES = List.of(
            new CapabilityDescriptor("agent.request", "设计助理请求", "remote", true),
            new CapabilityDescriptor("storyboard.plan", "分镜规划", "remote", true),
            new CapabilityDescriptor("image.generate", "生成图片", "remote", true),
            new CapabilityDescriptor("image.redraw", "重绘", "remote", true),
            new CapabilityDescriptor("video.generate", "生成视频", "remote", true)
    );

    // ─────────────────────────── 对外 ───────────────────────────

    public List<CapabilityDescriptor> capabilities() {
        return CAPABILITIES;
    }

    /** 提交作业：幂等（clientRequestId）+ 会话归属校验 + 落库。 */
    public JobSubmitResponse submit(String userId, JobRequest req) {
        CanvasJob existed = canvasJobMapper.selectOne(new LambdaQueryWrapper<CanvasJob>()
                .eq(CanvasJob::getClientRequestId, req.clientRequestId()));
        if (existed != null) {
            return new JobSubmitResponse(existed.getId());
        }

        String projectId = req.context() != null ? req.context().projectId() : null;
        String aiProjectId = projectId != null ? projectService.get(userId, projectId).getId() : null;
        String conversationId = null;
        if (req.conversationId() != null) {
            AgentConversation conv = agentConversationMapper.selectById(req.conversationId());
            if (conv != null && userId.equals(conv.getUserId())) {
                conversationId = conv.getId();
            }
        }
        if (conversationId == null && aiProjectId != null) {
            conversationId = agentConversationService.createOrGetActive(userId, aiProjectId).getId();
        }

        Map<String, Object> storedParams = paramsWithCanvas(req);
        CanvasJob job = new CanvasJob();
        job.setUserId(userId);
        job.setClientRequestId(req.clientRequestId());
        job.setConversationId(conversationId);
        job.setProjectId(aiProjectId);
        job.setCapabilityId(req.capabilityId());
        job.setNodeIds(req.context() != null ? JsonUtil.toJson(req.context().nodeIds()) : null);
        job.setSelectedNodeId(req.context() != null ? req.context().selectedNodeId() : null);
        job.setSourceAssetIds(req.sourceAssetIds() != null ? JsonUtil.toJson(req.sourceAssetIds()) : null);
        job.setParams(JsonUtil.toJson(storedParams));
        job.setBatchId(stringValue(canvasValue(storedParams, "batchId")));
        job.setStatus("queued");
        job.setProgress(0);
        job.setAttempt(0);
        job.setStateVersion(0L);
        job.setLastEventSeq(0L);
        canvasJobRepository.insertQueued(job);
        return new JobSubmitResponse(job.getId());
    }

    /** 兼容旧调用：默认从 seq 0 重放。 */
    public SseEmitter subscribeAndRun(String userId, String jobId) {
        return subscribeAndRun(userId, jobId, null, null);
    }

    /**
     * 订阅事件并尝试通过 lease CAS 启动/恢复执行。
     *
     * <p>Replay 和实时事件发布共用 job lock；事件先提交后，才可能在订阅端出现，
     * 因而不会出现 snapshot 之后、emitter 注册之前的游标缺口。
     */
    public SseEmitter subscribeAndRun(String userId, String jobId, Long afterSeq, String lastEventId) {
        getOwnedJob(userId, jobId);
        long cursor = CanvasJobReplayCursor.resolve(afterSeq, lastEventId);
        OffsetDateTime now = OffsetDateTime.now();
        canvasJobRepository.recoverExpiredRunning(now);

        String owner = UUID.randomUUID().toString();
        CanvasJobRepository.Claim claim = canvasJobRepository.claim(
                jobId, owner, now, now.plus(LEASE_DURATION));

        SseEmitter emitter = new SseEmitter(0L);
        ReentrantLock lock = lockFor(jobId);
        CanvasJob snapshotJob;
        lock.lock();
        try {
            emitters.put(jobId, emitter);
            emitter.onCompletion(() -> emitters.remove(jobId, emitter));
            emitter.onTimeout(() -> emitters.remove(jobId, emitter));
            emitter.onError(e -> emitters.remove(jobId, emitter));

            CanvasJobRepository.Snapshot snapshot = canvasJobRepository.snapshot(jobId, cursor);
            if (snapshot == null) throw new BusinessException(40401, "作业不存在或无权访问");
            snapshotJob = snapshot.job();
            for (CanvasJobEvent event : snapshot.events()) {
                sendStoredEvent(emitter, event);
            }
            if (snapshot.events().isEmpty() && isTerminal(snapshotJob.getStatus())) {
                sendTerminalFallback(emitter, snapshotJob);
            }
        } finally {
            lock.unlock();
        }

        if (claim != null) {
            CanvasJob claimed = claim.job() != null ? claim.job() : snapshotJob;
            Thread.startVirtualThread(() -> runJob(claimed, owner));
        } else if (CanvasJobStateMachine.isFinalWithoutResume(snapshotJob, OffsetDateTime.now())) {
            completeEmitter(emitter);
        }
        return emitter;
    }

    /** 取消作业：CAS 置 cancelled；晚到的完成结果由 lease owner / cancel_requested_at 拒绝覆盖。 */
    public void cancel(String userId, String jobId) {
        getOwnedJob(userId, jobId);
        OffsetDateTime now = OffsetDateTime.now();
        CanvasJobEvent event = canvasJobRepository.cancel(jobId, now);
        if (event == null) return;
        ReentrantLock lock = lockFor(jobId);
        lock.lock();
        try {
            publishEvent(jobId, event);
            completeCurrent(jobId);
        } finally {
            lock.unlock();
        }
    }

    /** 前端 SSE 丢事件时的状态对账入口：只返回当前用户拥有的任务快照。 */
    public Map<String, Object> status(String userId, String jobId) {
        CanvasJob job = getOwnedJob(userId, jobId);
        if (job == null) throw new BusinessException(40401, "作业不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.getId());
        result.put("status", job.getStatus());
        result.put("progress", job.getProgress());
        List<String> resultAssetIds = new ArrayList<>(JsonUtil.parseList(job.getResultAssetIds()));
        if (resultAssetIds.isEmpty() && job.getResultAssetId() != null) {
            resultAssetIds.add(job.getResultAssetId());
        }
        List<Map<String, Object>> assets = new ArrayList<>();
        for (String assetId : resultAssetIds) {
            AgentAsset asset = agentAssetMapper.selectById(assetId);
            if (asset == null) continue;
            String name = asset.getUrl() == null || asset.getUrl().isBlank()
                    ? assetId
                    : asset.getUrl().substring(asset.getUrl().lastIndexOf('/') + 1);
            assets.add(Map.of(
                    "id", asset.getId(),
                    "name", name,
                    "mimeType", com.moon.moonagent.service.FileStorageService.contentType(name)
            ));
        }
        result.put("assets", assets);
        if (job.getError() != null) result.put("error", job.getError());
        return result;
    }

    /** 按项目列全部会话（画布侧边栏对话列表）。 */
    public List<AgentConversation> listConversations(String userId, String projectId) {
        String aiProjectId = projectService.get(userId, projectId).getId();
        return agentConversationMapper.selectList(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getProjectId, aiProjectId)
                        .orderByDesc(AgentConversation::getUpdatedAt));
    }

    /** 取或建共享会话：按项目键映射 ai分镜 project + 该项目的活跃 conversation。 */
    public AgentConversation getOrCreateConversation(String userId, String projectId) {
        String aiProjectId = projectService.get(userId, projectId).getId();
        return agentConversationService.createOrGetActive(userId, aiProjectId);
    }

    /** 会话消息历史（属主校验）。 */
    public List<AgentMessage> conversationMessages(String userId, String conversationId) {
        AgentConversation conv = agentConversationMapper.selectById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            throw new BusinessException(40401, "会话不存在或无权访问");
        }
        return agentConversationService.listMessages(conversationId);
    }

    /** 素材访问（ownership：生成记录 user_id、会话归属或作业 result_asset_id 反查）。 */
    public AgentAsset getOwnedAsset(String userId, String assetId) {
        AgentAsset asset = agentAssetMapper.selectById(assetId);
        if (asset == null) throw new BusinessException(40401, "素材不存在");
        if (userId.equals(asset.getUserId())) return asset;
        if (asset.getConversationId() != null) {
            AgentConversation conv = agentConversationMapper.selectById(asset.getConversationId());
            if (conv == null || !userId.equals(conv.getUserId())) {
                throw new BusinessException(40401, "素材不存在或无权访问");
            }
            return asset;
        }
        CanvasJob job = findOwnedJobContainingResult(userId, assetId);
        if (job == null) throw new BusinessException(40401, "素材不存在或无权访问");
        return asset;
    }

    // ─────────────────────────── 执行 ───────────────────────────

    void runJob(CanvasJob job, String owner) {
        AtomicBoolean heartbeatRunning = new AtomicBoolean(true);
        Thread heartbeat = startHeartbeat(job.getId(), owner, heartbeatRunning);
        try {
            switch (job.getCapabilityId()) {
                case "agent.request" -> handleAsk(job);
                case "storyboard.plan" -> handleStoryboardPlan(job);
                case "image.generate", "image.redraw" -> handleImage(job, owner);
                case "video.generate" -> handleVideo(job, owner);
                default -> throw new BusinessException(40001, "未知能力: " + job.getCapabilityId());
            }

            OffsetDateTime now = OffsetDateTime.now();
            CanvasJobEvent terminal = canvasJobRepository.complete(
                    job.getId(), owner, job.getResultAssetId(), job.getResultAssetIds(), now);
            if (terminal != null) {
                publishAfterCommit(job.getId(), terminal);
            } else {
                publishLateResultAudit(job.getId(), "completed", job.getResultAssetIds(), null, now);
            }
        } catch (Throwable error) {
            log.error("作业执行失败 jobId={}", job.getId(), error);
            String message = errorMessage(error);
            OffsetDateTime now = OffsetDateTime.now();
            CanvasJobEvent failed = canvasJobRepository.fail(job.getId(), owner, message, now);
            if (failed != null) {
                publishAfterCommit(job.getId(), failed);
            } else {
                publishLateResultAudit(job.getId(), "failed", null, message, now);
            }
            if (error instanceof Error fatal) throw fatal;
        } finally {
            heartbeatRunning.set(false);
            if (heartbeat != null) heartbeat.interrupt();
        }
    }

    private Thread startHeartbeat(String jobId, String owner, AtomicBoolean running) {
        return Thread.startVirtualThread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running.get()) return;
                OffsetDateTime now = OffsetDateTime.now();
                if (!canvasJobRepository.renewLease(jobId, owner, now, now.plus(LEASE_DURATION))) {
                    log.warn("作业 lease 续约失败，停止心跳 jobId={} owner={}", jobId, owner);
                    return;
                }
            }
        });
    }

    private void handleAsk(CanvasJob job) {
        String prompt = extractPrompt(job);
        String messageId = UUID.randomUUID().toString();
        designAssistantCapability.answer(prompt, job.getConversationId(), t ->
                recordAndPublish(job.getId(), "agent.message", Map.of(
                        "jobId", job.getId(),
                        "messageId", messageId,
                        "message", t,
                        "delta", true
        )));
    }

    /**
     * Structured planning stays inline-data: one storyboard_plan generation record is written for audit,
     * then the plan is published as a storyboard.created event. No image/video asset is created.
     */
    void handleStoryboardPlan(CanvasJob job) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        String script = extractPrompt(job);
        List<String> referenceImages = stringList(params, "referenceImages");
        List<GenerationReference> references = new ArrayList<>();
        references.addAll(references("image", referenceImages, sourceRole(job)));
        if (references.isEmpty()) references.addAll(sourceReferences(job));
        long startedMs = System.currentTimeMillis();

        List<StoryboardShot> shots;
        try {
            shots = referenceImages.isEmpty()
                    ? storyboardPlanningService.plan(script)
                    : storyboardPlanningService.plan(script, referenceImages);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedMs;
            Map<String, Object> failedParams = new LinkedHashMap<>(params);
            failedParams.put("planVersion", StoryboardPlanningService.PLAN_VERSION);
            generationRecordWriter.create(new GenerationRecord(
                    job.getUserId(), job.getProjectId(), job.getConversationId(), null,
                    GenerationRecordTypes.STORYBOARD_PLAN, GenerationRecordTypes.STORYBOARD_PLAN,
                    script, str(params, "model"), null, null, List.of(), references,
                    failedParams, GenerationRecordTypes.FAILED, errorMessage(e), durationMs,
                    OffsetDateTime.now()));
            throw e;
        }

        long durationMs = System.currentTimeMillis() - startedMs;
        Map<String, Object> auditParams = new LinkedHashMap<>(params);
        auditParams.put("planVersion", StoryboardPlanningService.PLAN_VERSION);
        auditParams.put("shots", shots);
        String recordId = generationRecordWriter.create(new GenerationRecord(
                job.getUserId(), job.getProjectId(), job.getConversationId(), null,
                GenerationRecordTypes.STORYBOARD_PLAN, GenerationRecordTypes.STORYBOARD_PLAN,
                script, str(params, "model"), null, null, List.of(), references,
                auditParams, GenerationRecordTypes.COMPLETED, null, durationMs,
                OffsetDateTime.now()));
        if (recordId == null || recordId.isBlank()) {
            throw new BusinessException(50201, "分镜规划记录落库失败");
        }

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("jobId", job.getId());
        eventData.put("shots", shots);
        eventData.put("planVersion", StoryboardPlanningService.PLAN_VERSION);
        recordAndPublish(job.getId(), "storyboard.created", eventData);
    }

    private void handleImage(CanvasJob job, String owner) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        String prompt = extractPrompt(job);
        String model = str(params, "model");
        String size = str(params, "size");
        String quality = str(params, "quality");
        String aspectRatio = str(params, "aspectRatio");
        Integer n = intVal(params, "n");

        List<String> referenceImages = new ArrayList<>();
        Object refParam = params.get("referenceImages");
        if (refParam instanceof List<?> list) {
            for (Object item : list) if (item instanceof String s && !s.isBlank()) referenceImages.add(s);
        }
        if (referenceImages.isEmpty()) {
            for (String aid : JsonUtil.parseList(job.getSourceAssetIds())) {
                AgentAsset asset = agentAssetMapper.selectById(aid);
                if (asset != null && asset.getUrl() != null) referenceImages.add(toDataUriOrUrl(asset.getUrl()));
            }
        }
        String mode = "image.redraw".equals(job.getCapabilityId()) ? "edit" : null;
        if (mode == null && !referenceImages.isEmpty()) mode = "edit";

        List<GenerationReference> references = references("image", referenceImages, sourceRole(job));
        String genType = explicitGenType(params,
                GenerationRecordTypes.classifyImage(mode, referenceImages, null, n));
        long startedMs = System.currentTimeMillis();
        ProgressPulse pulse = null;
        try {
            if (!updateProgress(job.getId(), owner, 5)) {
                throw new BusinessException(40901, "任务已取消或执行租约已失效");
            }
            pulse = startProgressPulse(job.getId(), owner, 5, 90);
            List<String> paths = imageGenerationService.generateImages(
                    null, prompt, model, size, quality, aspectRatio, referenceImages, mode, null, n);
            if (paths == null || paths.isEmpty()) {
                throw new BusinessException(50201, "图片生成失败");
            }
            pulse.close();
            pulse = null;
            if (!updateProgress(job.getId(), owner, 95)) {
                throw new BusinessException(40901, "任务已取消或执行租约已失效");
            }

            long durationMs = System.currentTimeMillis() - startedMs;
            List<String> assetIds = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i);
                String assetId = generationRecordWriter.create(new GenerationRecord(
                        job.getUserId(), job.getProjectId(), job.getConversationId(), null,
                        GenerationRecordTypes.IMAGE, genType, prompt, model, null, path,
                        List.of(path), references, params, GenerationRecordTypes.COMPLETED,
                        null, durationMs, OffsetDateTime.now()));
                if (assetId == null || assetId.isBlank()) {
                    throw new BusinessException(50201, "图片生成记录落库失败");
                }
                assetIds.add(assetId);
                recordAndPublish(job.getId(), "asset.created", Map.of(
                        "jobId", job.getId(),
                        "asset", Map.of(
                                "id", assetId,
                                "name", "image_" + i + ".png",
                                "mimeType", "image/png"
                        )
                ));
            }
            setResultAssets(job, assetIds);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedMs;
            generationRecordWriter.create(new GenerationRecord(
                    job.getUserId(), job.getProjectId(), job.getConversationId(), null,
                    GenerationRecordTypes.IMAGE, genType, prompt, model, null, null,
                    List.of(), references, params, GenerationRecordTypes.FAILED,
                    errorMessage(e), durationMs, OffsetDateTime.now()));
            throw e;
        } finally {
            if (pulse != null) pulse.close();
        }
    }

    void handleVideo(CanvasJob job, String owner) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        String prompt = extractPrompt(job);
        String alias = str(params, "model");
        String resolution = str(params, "resolution");
        String size = str(params, "size");
        String aspectRatio = str(params, "aspectRatio");
        Integer duration = intVal(params, "duration");

        List<String> referenceImages = new ArrayList<>();
        List<String> referenceVideos = new ArrayList<>();
        List<String> referenceRoles = new ArrayList<>();
        Object imgParam = params.get("referenceImages");
        if (imgParam instanceof List<?> list) {
            for (Object item : list) if (item instanceof String s && !s.isBlank()) referenceImages.add(s);
        }
        Object vidParam = params.get("referenceVideos");
        if (vidParam instanceof List<?> list) {
            for (Object item : list) if (item instanceof String s && !s.isBlank()) referenceVideos.add(s);
        }
        Object roleParam = params.get("referenceRoles");
        Map<String, String> roleByAssetId = new HashMap<>();
        if (roleParam instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> roleMap) {
                    Object assetId = roleMap.get("assetId");
                    Object role = roleMap.get("role");
                    String roleValue = role == null ? "REFERENCE" : role.toString();
                    referenceRoles.add(roleValue);
                    if (assetId != null) roleByAssetId.put(assetId.toString(), roleValue);
                } else if (item instanceof String role && !role.isBlank()) {
                    referenceRoles.add(role);
                }
            }
        }
        List<String> referenceAssetIds = stringList(params, "referenceAssetIds");
        if (!referenceAssetIds.isEmpty()) {
            referenceImages.clear();
            referenceRoles.clear();
            for (String assetId : referenceAssetIds) {
                CanvasAsset asset = canvasAssetService.getOwned(job.getUserId(), assetId);
                if ("video".equals(asset.getType())) {
                    referenceVideos.add(asset.getObjectUrl());
                } else {
                    referenceImages.add(asset.getObjectUrl());
                    referenceRoles.add(roleByAssetId.getOrDefault(assetId, sourceRole(job)));
                }
            }
        }
        if (referenceImages.isEmpty() && referenceVideos.isEmpty()) {
            for (String aid : JsonUtil.parseList(job.getSourceAssetIds())) {
                AgentAsset asset = agentAssetMapper.selectById(aid);
                if (asset == null || asset.getUrl() == null) continue;
                String url = toDataUriOrUrl(asset.getUrl());
                if ("video".equals(asset.getType())) referenceVideos.add(url);
                else referenceImages.add(url);
            }
        }
        if (referenceImages.stream().anyMatch(url -> url != null && url.startsWith("data:"))) {
            throw new BusinessException(40001, "参考图片尚未持久化，请重新选择后再生成");
        }

        List<GenerationReference> references = new ArrayList<>();
        List<ReferenceImage> roleAwareImages = new ArrayList<>();
        for (int index = 0; index < referenceImages.size(); index++) {
            String role = index < referenceRoles.size() ? referenceRoles.get(index) : sourceRole(job);
            roleAwareImages.add(new ReferenceImage(referenceImages.get(index), mapVideoRole(role)));
        }
        references.addAll(references("image", referenceImages, sourceRole(job), referenceRoles));
        references.addAll(references("video", referenceVideos, sourceRole(job)));
        String genType = explicitGenType(params, GenerationRecordTypes.classifyVideo(referenceImages, null));
        long startedMs = System.currentTimeMillis();
        String taskId = job.getProviderTaskId();
        String recordId = findAssetIdByTaskId(taskId);

        try {
            if (taskId == null || taskId.isBlank()) {
                taskId = videoGenerationService.createVideoTask(
                        null, prompt, alias, resolution, size, aspectRatio, duration, null, null,
                        roleAwareImages, null, referenceVideos, null, true);
                job.setProviderTaskId(taskId);
                if (!canvasJobRepository.attachProviderTaskId(job.getId(), taskId, job.getBatchId())) {
                    throw new BusinessException(50201, "视频任务句柄持久化失败，已停止自动恢复以避免重复生成");
                }
            }

            if (recordId == null || recordId.isBlank()) {
                recordId = generationRecordWriter.create(new GenerationRecord(
                        job.getUserId(), job.getProjectId(), job.getConversationId(), null,
                        GenerationRecordTypes.VIDEO, genType, prompt, alias, taskId, null,
                        List.of(), references, params, GenerationRecordTypes.GENERATING,
                        null, null, OffsetDateTime.now()));
            }
            if (recordId == null || recordId.isBlank()) {
                throw new BusinessException(50201, "视频生成记录落库失败");
            }

            int progress = 5;
            if (!updateProgress(job.getId(), owner, progress)) {
                throw new BusinessException(40901, "任务已取消或执行租约已失效");
            }
            long pollStartMs = System.currentTimeMillis();
            while (true) {
                if (System.currentTimeMillis() - pollStartMs > VIDEO_POLL_TIMEOUT_MS) {
                    throw new BusinessException(50201, "视频生成超时（超过15分钟未完成），请重试");
                }
                Map<String, String> poll = videoGenerationService.pollVideoTask(taskId);
                String status = poll.getOrDefault("status", "processing");
                if ("completed".equals(status)) {
                    String url = poll.get("videoUrl");
                    long durationMs = System.currentTimeMillis() - startedMs;
                    generationRecordWriter.update(recordId, GenerationRecordUpdate.completed(
                            url, List.of(url), durationMs));
                    setResultAssets(job, List.of(recordId));
                    recordAndPublish(job.getId(), "asset.created", Map.of(
                            "jobId", job.getId(),
                            "asset", Map.of(
                                    "id", recordId,
                                    "name", "video.mp4",
                                    "mimeType", "video/mp4"
                            )
                    ));
                    return;
                }
                if ("failed".equals(status)) {
                    throw new BusinessException(50201, poll.getOrDefault("error", "视频生成失败"));
                }

                progress = GenerationProgress.fromProvider(poll.get("progress"), progress);
                if (!updateProgress(job.getId(), owner, progress)) {
                    throw new BusinessException(40901, "任务已取消或执行租约已失效");
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(40901, "任务执行被中断");
                }
            }
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startedMs;
            GenerationRecordUpdate update = GenerationRecordUpdate.failed(errorMessage(e), durationMs);
            if (recordId == null || recordId.isBlank()) {
                generationRecordWriter.create(new GenerationRecord(
                        job.getUserId(), job.getProjectId(), job.getConversationId(), null,
                        GenerationRecordTypes.VIDEO, genType, prompt, alias, taskId, null,
                        List.of(), references, params, GenerationRecordTypes.FAILED,
                        errorMessage(e), durationMs, OffsetDateTime.now()));
            } else {
                generationRecordWriter.update(recordId, update);
            }
            throw e;
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private void recordAndPublish(String jobId, String type, Map<String, Object> data) {
        if (canvasJobRepository.isCancellationRequested(jobId)) return;
        ReentrantLock lock = lockFor(jobId);
        lock.lock();
        try {
            CanvasJobEvent event = canvasJobRepository.appendEvent(jobId, type, data);
            publishEvent(jobId, event);
        } finally {
            lock.unlock();
        }
    }

    private void publishAfterCommit(String jobId, CanvasJobEvent event) {
        ReentrantLock lock = lockFor(jobId);
        lock.lock();
        try {
            publishEvent(jobId, event);
            if ("job.completed".equals(event.getType())
                    || "job.failed".equals(event.getType())
                    || "job.cancelled".equals(event.getType())) {
                SseEmitter current = emitters.get(jobId);
                if (current != null) completeEmitter(current);
            }
        } finally {
            lock.unlock();
        }
    }

    private void publishLateResultAudit(String jobId, String lateType, String resultAssetIds,
                                        String error, OffsetDateTime now) {
        try {
            CanvasJobEvent event = canvasJobRepository.appendLateResultAudit(
                    jobId, lateType, resultAssetIds, error, now);
            publishAfterCommit(jobId, event);
        } catch (Exception e) {
            log.warn("晚到结果审计事件写入失败 jobId={}: {}", jobId, e.getMessage());
        }
    }

    private CanvasJob getOwnedJob(String userId, String jobId) {
        CanvasJob job = canvasJobMapper.selectById(jobId);
        if (job == null || !userId.equals(job.getUserId())) {
            throw new BusinessException(40401, "作业不存在或无权访问");
        }
        return job;
    }

    private CanvasJob findOwnedJobContainingResult(String userId, String assetId) {
        List<CanvasJob> jobs = canvasJobMapper.selectList(new LambdaQueryWrapper<CanvasJob>()
                .eq(CanvasJob::getUserId, userId)
                .orderByDesc(CanvasJob::getCreatedAt));
        for (CanvasJob job : jobs) {
            if (assetId.equals(job.getResultAssetId())
                    || JsonUtil.parseList(job.getResultAssetIds()).contains(assetId)) {
                return job;
            }
        }
        return null;
    }

    private String findAssetIdByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        AgentAsset asset = agentAssetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getTaskId, taskId)
                .orderByDesc(AgentAsset::getCreatedAt)
                .last("LIMIT 1"));
        return asset != null ? asset.getId() : null;
    }

    private void setResultAssets(CanvasJob job, List<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return;
        job.setResultAssetId(assetIds.getFirst());
        job.setResultAssetIds(JsonUtil.toJson(assetIds));
    }

    private ReentrantLock lockFor(String jobId) {
        return jobLocks.computeIfAbsent(jobId, ignored -> new ReentrantLock());
    }

    private void sendStoredEvent(SseEmitter emitter, CanvasJobEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.getJobId() + ":" + event.getSeq())
                    .name(event.getType())
                    .data(event.getPayload()));
        } catch (Exception e) {
            log.warn("SSE 重放 {}:{} 失败: {}", event.getJobId(), event.getSeq(), e.getMessage());
        }
    }

    private void sendTerminalFallback(SseEmitter emitter, CanvasJob job) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", job.getId());
        if (job.getError() != null && !job.getError().isBlank()) payload.put("error", job.getError());
        long seq = job.getLastEventSeq() == null ? 0L : job.getLastEventSeq();
        try {
            emitter.send(SseEmitter.event()
                    .id(job.getId() + ":" + seq)
                    .name("job." + job.getStatus())
                    .data(JsonUtil.toJson(payload)));
        } catch (Exception e) {
            log.warn("SSE 终态兜底发送失败 jobId={}: {}", job.getId(), e.getMessage());
        }
    }

    private void publishEvent(String jobId, CanvasJobEvent event) {
        SseEmitter current = emitters.get(jobId);
        if (current == null || event == null) return;
        sendStoredEvent(current, event);
    }

    private boolean updateProgress(String jobId, String owner, int progress) {
        OffsetDateTime now = OffsetDateTime.now();
        CanvasJobEvent event = canvasJobRepository.updateProgress(
                jobId, owner, Math.max(0, Math.min(100, progress)), now, now.plus(LEASE_DURATION));
        if (event == null) return false;
        publishAfterCommit(jobId, event);
        return true;
    }

    private ProgressPulse startProgressPulse(String jobId, String owner, int start, int max) {
        return new ProgressPulse(jobId, owner, start, max);
    }

    private final class ProgressPulse implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        private ProgressPulse(String jobId, String owner, int start, int max) {
            AtomicInteger progress = new AtomicInteger(start);
            this.thread = Thread.startVirtualThread(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    int next = GenerationProgress.tick(progress.get(), max, 5);
                    if (next == progress.get()) continue;
                    if (!updateProgress(jobId, owner, next)) return;
                    progress.set(next);
                }
            });
        }

        @Override
        public void close() {
            running.set(false);
            thread.interrupt();
        }
    }

    private void completeCurrent(String jobId) {
        SseEmitter current = emitters.get(jobId);
        if (current != null) completeEmitter(current);
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 完成失败: {}", e.getMessage());
        }
    }

    private boolean isTerminal(String status) {
        return CanvasJobStateMachine.isTerminal(status);
    }

    private String extractPrompt(CanvasJob job) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        Object prompt = params.get("prompt");
        if (prompt == null || prompt.toString().isBlank()) {
            throw new BusinessException(40001, "prompt 不能为空");
        }
        return prompt.toString();
    }

    private static String explicitGenType(Map<String, Object> params, String fallback) {
        String value = str(params, "genType");
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sourceRole(CanvasJob job) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        String role = stringValue(canvasValue(params, "sourceRole"));
        return role == null || role.isBlank() ? "REFERENCE" : role;
    }

    private static List<GenerationReference> references(String type, List<String> urls, String role) {
        List<GenerationReference> references = new ArrayList<>();
        if (urls == null) return references;
        for (String url : urls) {
            if (url != null && !url.isBlank()) references.add(new GenerationReference(type, url, role));
        }
        return references;
    }

    private static List<GenerationReference> references(String type, List<String> urls, String fallbackRole, List<String> roles) {
        List<GenerationReference> references = new ArrayList<>();
        if (urls == null) return references;
        for (int index = 0; index < urls.size(); index++) {
            String url = urls.get(index);
            if (url == null || url.isBlank()) continue;
            String role = index < roles.size() ? roles.get(index) : fallbackRole;
            references.add(new GenerationReference(type, url, role));
        }
        return references;
    }

    private static ReferenceImageRole mapVideoRole(String role) {
        if (role == null) return ReferenceImageRole.REFERENCE;
        return switch (role.toUpperCase()) {
            case "FIRST_FRAME", "首帧" -> ReferenceImageRole.FIRST_FRAME;
            case "LAST_FRAME", "尾帧" -> ReferenceImageRole.LAST_FRAME;
            default -> ReferenceImageRole.REFERENCE;
        };
    }

    private List<GenerationReference> sourceReferences(CanvasJob job) {
        List<GenerationReference> references = new ArrayList<>();
        for (String assetId : JsonUtil.parseList(job.getSourceAssetIds())) {
            AgentAsset asset = agentAssetMapper.selectById(assetId);
            if (asset == null || asset.getUrl() == null || asset.getUrl().isBlank()) continue;
            String type = "video".equals(asset.getType()) ? "video" : "image";
            references.add(new GenerationReference(type, toDataUriOrUrl(asset.getUrl()), sourceRole(job)));
        }
        return references;
    }

    private static Map<String, Object> paramsWithCanvas(JobRequest req) {
        Map<String, Object> source = req.params() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(req.params());
        Map<String, Object> canvas = new LinkedHashMap<>();
        Object existing = source.get("canvas");
        if (existing instanceof Map<?, ?> existingMap) {
            for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
                canvas.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        putIfMissing(canvas, "canvasId", firstNonBlank(
                stringValue(source.get("canvasId")), stringValue(canvas.get("canvasId"))));
        putIfMissing(canvas, "producerNodeId", firstNonBlank(
                stringValue(source.get("producerNodeId")),
                stringValue(canvas.get("producerNodeId")),
                req.context() != null ? req.context().selectedNodeId() : null));
        putIfMissing(canvas, "batchId", firstNonBlank(
                stringValue(source.get("batchId")), stringValue(canvas.get("batchId"))));
        putIfMissing(canvas, "sourceRole", firstNonBlank(
                stringValue(source.get("sourceRole")), stringValue(canvas.get("sourceRole"))));
        source.put("canvas", canvas);
        return source;
    }

    private static Object canvasValue(Map<String, Object> params, String key) {
        Object canvas = params != null ? params.get("canvas") : null;
        return canvas instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static void putIfMissing(Map<String, Object> map, String key, Object value) {
        if (!map.containsKey(key) || map.get(key) == null) map.put(key, value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String errorMessage(Throwable error) {
        if (error instanceof BusinessException businessException
                && businessException.getMessage() != null
                && !businessException.getMessage().isBlank()) {
            return businessException.getMessage();
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "生成失败，请稍后重试"
                : error.getMessage();
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        return value == null ? null : value.toString();
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static Integer intVal(Map<String, Object> map, String key) {
        Object value = map != null ? map.get(key) : null;
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String toDataUriOrUrl(String url) {
        return url;
    }
}
