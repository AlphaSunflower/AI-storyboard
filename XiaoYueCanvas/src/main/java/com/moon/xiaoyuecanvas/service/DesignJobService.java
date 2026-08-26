package com.moon.xiaoyuecanvas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.VideoGenerationService;
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
import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.mapper.CanvasJobMapper;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.storyboard.common.BusinessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Morrow 设计助理作业服务：按 capabilityId 分发到 agent-core 的 AI 生成服务，落 canvas_jobs / agent_assets，
 * 把进度/素材以 SSE 事件推给前端（契约 events: queued/started/progress/agent.message/asset.created/completed/failed/cancelled）。
 *
 * <p>能力映射（MVP）：
 * <ul>
 *   <li>{@code agent.request} → Spring AI ChatClient 流式回答（打字机 agent.message 增量）</li>
 *   <li>{@code image.generate} → agent-core ImageGenerationService 文生图</li>
 *   <li>{@code image.redraw} → agent-core ImageGenerationService 图改图（sourceAssetIds → referenceImages）</li>
 *   <li>{@code video.generate} → agent-core VideoGenerationService 创建+轮询</li>
 * </ul>
 * 资产存 agent_assets（conversationId 归属）；无会话时以作业 user_id + result_asset_id 反查归属。
 */
@Service
@RequiredArgsConstructor
public class DesignJobService {

    private static final Logger log = LoggerFactory.getLogger(DesignJobService.class);

    private final CanvasJobMapper canvasJobMapper;
    private final AgentAssetMapper agentAssetMapper;
    private final AgentConversationMapper agentConversationMapper;
    private final AgentConversationService agentConversationService;
    private final DesignAssistantCapability designAssistantCapability;
    private final ProjectService projectService;
    private final ImageGenerationService imageGenerationService;
    private final VideoGenerationService videoGenerationService;

    /** jobId -> 当前 SSE 发射器（订阅后置；仅单发射器，重连会替换） */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    /** jobId -> 是否已启动执行 */
    private final Map<String, AtomicBoolean> started = new ConcurrentHashMap<>();

    private static final List<CapabilityDescriptor> CAPABILITIES = List.of(
            new CapabilityDescriptor("agent.request", "设计助理请求", "remote", true),
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
        // 幂等：同 clientRequestId 复用已存在作业
        CanvasJob existed = canvasJobMapper.selectOne(new LambdaQueryWrapper<CanvasJob>()
                .eq(CanvasJob::getClientRequestId, req.clientRequestId()));
        if (existed != null) {
            return new JobSubmitResponse(existed.getId());
        }
        // 项目映射：画布项目键 → ai分镜 projects 行（会话 project_id 外键依赖 projects，必须先建项目）
        String projectId = req.context() != null ? req.context().projectId() : null;
        String aiProjectId = projectId != null ? projectService.get(userId, projectId).getId() : null;
        // 会话解析：客户端会话若无/无效/非本用户 → 按项目取或建共享活跃会话（画布与 MoonAgent 同一条 conversation）
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
        CanvasJob job = new CanvasJob();
        job.setUserId(userId);
        job.setClientRequestId(req.clientRequestId());
        job.setConversationId(conversationId);
        job.setProjectId(aiProjectId);
        job.setCapabilityId(req.capabilityId());
        job.setNodeIds(req.context() != null ? JsonUtil.toJson(req.context().nodeIds()) : null);
        job.setSelectedNodeId(req.context() != null ? req.context().selectedNodeId() : null);
        job.setSourceAssetIds(req.sourceAssetIds() != null ? JsonUtil.toJson(req.sourceAssetIds()) : null);
        job.setParams(req.params() != null ? JsonUtil.toJson(req.params()) : null);
        job.setStatus("queued");
        job.setProgress(0);
        canvasJobMapper.insert(job);
        return new JobSubmitResponse(job.getId());
    }

    /** 订阅事件并启动执行（虚拟线程）。 */
    public SseEmitter subscribeAndRun(String userId, String jobId) {
        CanvasJob job = getOwnedJob(userId, jobId);
        SseEmitter emitter = new SseEmitter(0L); // 不限时（生成可能 2-5 分钟）
        emitters.put(jobId, emitter);
        emitter.onCompletion(() -> emitters.remove(jobId));
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onError(e -> emitters.remove(jobId));

        // 若作业已到终态（重连），直接重放终态事件
        if (isTerminal(job.getStatus())) {
            send(emitter, "job." + job.getStatus(), Map.of("jobId", jobId));
            emitter.complete();
            return emitter;
        }

        if (started.computeIfAbsent(jobId, k -> new AtomicBoolean()).compareAndSet(false, true)) {
            Thread.startVirtualThread(() -> runJob(userId, job, emitter));
        }
        return emitter;
    }

    /** 取消作业。 */
    public void cancel(String userId, String jobId) {
        CanvasJob job = getOwnedJob(userId, jobId);
        if (isTerminal(job.getStatus())) return;
        job.setStatus("cancelled");
        canvasJobMapper.updateById(job);
        SseEmitter emitter = emitters.get(jobId);
        if (emitter != null) {
            send(emitter, "job.cancelled", Map.of("jobId", jobId));
            emitter.complete();
        }
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

    /** 素材访问（ownership：会话归属或作业 result_asset_id 反查）。 */
    public AgentAsset getOwnedAsset(String userId, String assetId) {
        AgentAsset asset = agentAssetMapper.selectById(assetId);
        if (asset == null) throw new BusinessException(40401, "素材不存在");
        if (asset.getConversationId() != null) {
            AgentConversation conv = agentConversationMapper.selectById(asset.getConversationId());
            if (conv == null || !userId.equals(conv.getUserId())) {
                throw new BusinessException(40401, "素材不存在或无权访问");
            }
            return asset;
        }
        // 无会话：作业属主反查
        CanvasJob job = canvasJobMapper.selectOne(new LambdaQueryWrapper<CanvasJob>()
                .eq(CanvasJob::getResultAssetId, assetId).eq(CanvasJob::getUserId, userId));
        if (job == null) throw new BusinessException(40401, "素材不存在或无权访问");
        return asset;
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private void runJob(String userId, CanvasJob job, SseEmitter emitter) {
        String jobId = job.getId();
        send(emitter, "job.queued", Map.of("jobId", jobId));
        update(job, "running", 0, null);
        send(emitter, "job.started", Map.of("jobId", jobId));
        try {
            switch (job.getCapabilityId()) {
                case "agent.request" -> handleAsk(job, emitter);
                case "image.generate", "image.redraw" -> handleImage(job, emitter);
                case "video.generate" -> handleVideo(job, emitter);
                default -> throw new BusinessException(40001, "未知能力: " + job.getCapabilityId());
            }
            update(job, "completed", 100, null);
            send(emitter, "job.completed", Map.of("jobId", jobId));
            emitter.complete();
        } catch (Exception e) {
            log.error("作业执行失败 jobId={}", jobId, e);
            String msg = e instanceof BusinessException be ? be.getMessage() : "生成失败，请稍后重试";
            update(job, "failed", 0, msg);
            safeSend(emitter, "job.failed", Map.of("jobId", jobId, "error", msg));
            emitter.complete();
        }
    }

    private void handleAsk(CanvasJob job, SseEmitter emitter) {
        String prompt = extractPrompt(job);
        String messageId = UUID.randomUUID().toString();
        // 回答能力下沉 agent-core：内部持久化 user/assistant 消息到共享会话 + 流式增量（仅 delta，防前端重复气泡）
        designAssistantCapability.answer(prompt, job.getConversationId(), t ->
                safeSend(emitter, "agent.message",
                        Map.of("jobId", job.getId(), "messageId", messageId, "message", t, "delta", true)));
    }

    private void handleImage(CanvasJob job, SseEmitter emitter) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        String prompt = extractPrompt(job);
        String model = str(params, "model");
        String size = str(params, "size");
        String quality = str(params, "quality");
        Integer n = intVal(params, "n");
        List<String> referenceImages = new ArrayList<>();
        String mode = "image.redraw".equals(job.getCapabilityId()) ? "edit" : null;
        List<String> sourceAssetIds = JsonUtil.parseList(job.getSourceAssetIds());
        for (String aid : sourceAssetIds) {
            AgentAsset a = agentAssetMapper.selectById(aid);
            if (a != null && a.getUrl() != null) referenceImages.add(toDataUriOrUrl(a.getUrl()));
        }
        List<String> paths = imageGenerationService.generateImages(
                null, prompt, model, size, quality, null, referenceImages, mode, null, n);
        if (paths == null || paths.isEmpty()) {
            throw new BusinessException(50201, "图片生成失败");
        }
        for (int i = 0; i < paths.size(); i++) {
            AgentAsset asset = saveAsset(job, "image", paths.get(i), null, model, "completed");
            send(emitter, "asset.created", Map.of("jobId", job.getId(),
                    "asset", Map.of("id", asset.getId(), "name", "image_" + i + ".png", "mimeType", "image/png")));
        }
    }

    private void handleVideo(CanvasJob job, SseEmitter emitter) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        String prompt = extractPrompt(job);
        String alias = str(params, "model");
        String resolution = str(params, "resolution");
        String size = str(params, "size");
        String aspectRatio = str(params, "aspectRatio");
        Integer duration = intVal(params, "duration");
        List<String> referenceImages = new ArrayList<>();
        int progress = 0;
        String taskId = videoGenerationService.createVideoTask(
                null, prompt, alias, resolution, size, aspectRatio, duration, null, null, referenceImages, null);
        saveAsset(job, "video", null, taskId, alias, "generating");
        while (true) {
            Map<String, String> poll = videoGenerationService.pollVideoTask(taskId);
            String status = poll.getOrDefault("status", "processing");
            if ("completed".equals(status)) {
                String url = poll.get("videoUrl");
                saveAsset(job, "video", url, taskId, alias, "completed");
                send(emitter, "asset.created", Map.of("jobId", job.getId(),
                        "asset", Map.of("id", lastAssetId(job, taskId), "name", "video.mp4", "mimeType", "video/mp4")));
                return;
            } else if ("failed".equals(status)) {
                throw new BusinessException(50201, poll.getOrDefault("error", "视频生成失败"));
            }
            progress = Math.min(95, progress + 5);
            update(job, "running", progress, null);
            safeSend(emitter, "job.progress", Map.of("jobId", job.getId(), "progress", progress / 100.0, "message", "正在生成视频"));
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private AgentAsset saveAsset(CanvasJob job, String type, String url, String taskId, String model, String status) {
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(job.getConversationId());
        asset.setType(type);
        asset.setUrl(url);
        asset.setTaskId(taskId);
        asset.setModel(model);
        asset.setStatus(status);
        agentAssetMapper.insert(asset);
        if ("completed".equals(status) && asset.getId() != null) {
            job.setResultAssetId(asset.getId());
            canvasJobMapper.updateById(job);
        }
        return asset;
    }

    private String lastAssetId(CanvasJob job, String taskId) {
        AgentAsset a = agentAssetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getTaskId, taskId).eq(AgentAsset::getStatus, "completed").last("LIMIT 1"));
        return a != null ? a.getId() : null;
    }

    private CanvasJob getOwnedJob(String userId, String jobId) {
        CanvasJob job = canvasJobMapper.selectById(jobId);
        if (job == null || !userId.equals(job.getUserId())) {
            throw new BusinessException(40401, "作业不存在或无权访问");
        }
        return job;
    }

    private void update(CanvasJob job, String status, Integer progress, String error) {
        CanvasJob upd = new CanvasJob();
        upd.setId(job.getId());
        upd.setStatus(status);
        upd.setProgress(progress);
        upd.setError(error);
        canvasJobMapper.updateById(upd);
        job.setStatus(status);
        if (progress != null) job.setProgress(progress);
        job.setError(error);
    }

    private boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private String extractPrompt(CanvasJob job) {
        Map<String, Object> params = JsonUtil.parse(job.getParams());
        Object p = params != null ? params.get("prompt") : null;
        if (p == null || p.toString().isBlank()) throw new BusinessException(40001, "prompt 不能为空");
        return p.toString();
    }

    private void send(SseEmitter emitter, String event, Map<String, Object> data) {
        safeSend(emitter, event, data);
    }

    private void safeSend(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(JsonUtil.toJson(data)));
        } catch (Exception e) {
            log.warn("SSE 发送 {} 失败: {}", event, e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m != null ? m.get(k) : null;
        return v == null ? null : v.toString();
    }

    private static Integer intVal(Map<String, Object> m, String k) {
        Object v = m != null ? m.get(k) : null;
        if (v == null) return null;
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    private static String toDataUriOrUrl(String url) {
        return url; // MVP：服务端素材路径（/api/files/...）直接透传；图改图分支由 ImageGenerationService 处理
    }
}
