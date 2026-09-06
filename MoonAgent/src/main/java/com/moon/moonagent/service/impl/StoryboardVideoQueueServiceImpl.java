package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueItem;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueRequest;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.entity.StoryboardVideoQueue;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.StoryboardVideoQueueMapper;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.service.AssetService;
import com.moon.moonagent.service.StoryboardVideoQueueService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分镜视频批量生成队列实现。
 *
 * <p>串行执行：{@code processing} 单飞锁保证同一时刻只推进一个 queued 任务；
 * 参考素材聚合：分镜已生成图作首帧 + 场景关联「视频用途」资产图 + 用户行内上传参考图，合并后去重；
 * 终态回写：视频任务创建后由 {@code VideoPollExecutor} 按 taskId 反查 scene 回写 videoUrl/videoStatus，
 * 本实现只同步队列行的状态与进度。
 */
@Service
@RequiredArgsConstructor
public class StoryboardVideoQueueServiceImpl implements StoryboardVideoQueueService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardVideoQueueServiceImpl.class);

    /** 单飞锁：同一时刻只跑一个队列任务（一个分镜一个分镜生成）。 */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final StoryboardVideoQueueMapper queueMapper;
    private final AgentConversationMapper conversationMapper;
    private final StoryboardClient storyboardClient;
    private final AssetService assetService;
    private final AgentGenerationService generationService;

    @Override
    public List<StoryboardVideoQueue> enqueue(StoryboardVideoEnqueueRequest request) {
        List<StoryboardVideoQueue> created = new ArrayList<>();
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("入队分镜不能为空");
        }
        for (StoryboardVideoEnqueueItem item : request.items()) {
            if (item == null || item.sceneId() == null || item.sceneId().isBlank()) continue;
            StoryboardVideoQueue row = new StoryboardVideoQueue();
            row.setSceneId(item.sceneId().trim());
            row.setProjectId(request.projectId());
            row.setConversationId(request.conversationId());
            row.setStatus("queued");
            row.setPrompt(item.prompt());
            row.setModel(item.model());
            row.setResolution(item.resolution());
            row.setSize(item.size());
            row.setAspectRatio(item.aspectRatio());
            row.setDuration(item.duration());
            row.setNegativePrompt(item.negativePrompt());
            row.setSourceImageUrl(item.sourceImageUrl());
            // 带角色的参考图以 JSON 数组存 refImageUrls（兼容旧逗号列表：无角色时按 REFERENCE）
            row.setRefImageUrls(item.refImageRoles() == null || item.refImageRoles().isEmpty()
                    ? join(item.refImageUrls())
                    : rolesToJson(item.refImageRoles()));
            row.setRefAssetIds(join(item.refAssetIds()));
            row.setProgress(0);
            queueMapper.insert(row);
            markSceneQueued(row.getSceneId());
            created.add(row);
            log.info("[分镜视频队列] 入队: queueId={}, sceneId={}, prompt={}", row.getId(), row.getSceneId(), row.getPrompt());
        }
        // 触发一次串行处理（若当前无任务在跑）
        process();
        return created;
    }

    @Override
    public List<StoryboardVideoQueue> listByProject(String projectId, String status) {
        if (projectId == null || projectId.isBlank()) return List.of();
        if ("active".equalsIgnoreCase(status)) return queueMapper.findActiveByProject(projectId);
        return queueMapper.findByProjectId(projectId);
    }

    @Override
    public String getProjectId(String queueId) {
        if (queueId == null || queueId.isBlank()) return null;
        StoryboardVideoQueue row = queueMapper.selectById(queueId);
        return row == null ? null : row.getProjectId();
    }

    @Override
    public void cancel(String queueId) {
        StoryboardVideoQueue row = queueMapper.selectById(queueId);
        if (row == null) return;
        // 已终态：无可取消
        if (!"queued".equals(row.getStatus()) && !"running".equals(row.getStatus())) return;
        // running with an upstream task id: try to cancel upstream first (vendor may reject running)
        if ("running".equals(row.getStatus()) && row.getTaskId() != null && !row.getTaskId().isBlank()) {
            try {
                generationService.cancelVideoTask(row.getTaskId());
            } catch (Exception e) {
                log.warn("[分镜视频队列] 上游取消失败（将仅本地标记）: taskId={}, error={}", row.getTaskId(), e.getMessage());
            }
        }
        row.setStatus("cancelled");
        row.setFinishedAt(OffsetDateTime.now());
        queueMapper.updateById(row);
        // 分镜状态回写为 cancelled（若还在 queued/running）
        markSceneCancelled(row.getSceneId());
        log.info("[分镜视频队列] 取消: queueId={}, sceneId={}", queueId, row.getSceneId());
    }

    /** 串行处理队列：单飞锁，始终只推进最早的 queued 任务。 */
    private void process() {
        if (!processing.compareAndSet(false, true)) return;
        new Thread(this::drain, "storyboard-video-queue-drainer").start();
    }

    private void drain() {
        try {
            while (true) {
                StoryboardVideoQueue next = queueMapper.findNextQueued();
                if (next == null) break;
                runOne(next);
            }
        } catch (Exception e) {
            log.error("[分镜视频队列] 队列执行异常: {}", e.getMessage(), e);
        } finally {
            processing.set(false);
        }
    }

    /** 运行单条队列任务：置 running → 聚合参考素材 → 创建视频任务 → 轮询至终态。 */
    private void runOne(StoryboardVideoQueue row) {
        row.setStatus("running");
        row.setStartedAt(OffsetDateTime.now());
        row.setError(null);
        queueMapper.updateById(row);
        markSceneRunning(row.getSceneId());

        AgentConversation conversation = conversationMapper.selectById(row.getConversationId());
        if (conversation == null) {
            failRow(row, "会话上下文缺失，无法创建视频任务");
            return;
        }

        // 读取分镜（首帧 + 资产图）
        Scene scene = null;
        try {
            scene = storyboardClient.getScene(row.getSceneId());
        } catch (Exception e) {
            log.warn("[分镜视频队列] 读取分镜失败: sceneId={}, error={}", row.getSceneId(), e.getMessage());
        }

        String prompt = row.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = scene != null && scene.getVideoPrompt() != null && !scene.getVideoPrompt().isBlank()
                    ? scene.getVideoPrompt() : (scene != null && scene.getScriptContent() != null && !scene.getScriptContent().isBlank()
                    ? scene.getScriptContent() : "分镜视频生成");
        }

        // 参考图：分镜已生成图作首帧 + 场景关联「视频用途」资产图 + 用户上传参考图
        String sourceImageUrl = row.getSourceImageUrl();
        if (sourceImageUrl == null || sourceImageUrl.isBlank()) {
            sourceImageUrl = scene != null ? scene.getImageUrl() : null;
        }
        List<ReferenceImage> refImages = buildRefImages(row.getRefImageUrls());

        // 资产参考图（视频用途）在角色图之后以 REFERENCE 追加（不覆盖首尾帧角色）
        LinkedHashSet<String> assetRefs = new LinkedHashSet<>();
        if (row.getRefAssetIds() != null && !row.getRefAssetIds().isBlank()) {
            try {
                List<AssetVO> sceneAssets = assetService.sceneAssets(row.getSceneId(), "video");
                assetRefs.addAll(assetService.buildReferenceImages(sceneAssets));
            } catch (Exception e) {
                log.warn("[分镜视频队列] 资产参考图聚合失败: {}", e.getMessage());
            }
        }
        List<ReferenceImage> combined = new ArrayList<>(refImages);
        for (String u : assetRefs) {
            if (u == null || u.isBlank()) continue;
            boolean dup = combined.stream().anyMatch(r -> u.equals(r.url()));
            if (!dup) combined.add(ReferenceImage.ofReference(u));
        }
        // 分镜已挂载的参考图（SceneReviewCard/Agent 自动挂载）按 role 追加；不覆盖首尾帧角色
        try {
            List<Map<String, Object>> sceneRefs = storyboardClient.getSceneReferences(row.getSceneId());
            List<ReferenceImage> roleRefs = mapSceneReferenceRoles(sceneRefs);
            for (ReferenceImage ref : roleRefs) {
                boolean dup = combined.stream().anyMatch(r -> ref.url().equals(r.url()));
                if (!dup) combined.add(ref);
            }
        } catch (Exception e) {
            log.warn("[分镜视频队列] 读取分镜参考素材失败: {}", e.getMessage());
        }
        refImages = combined;
        // H3 约束：多模态参考素材与首帧图生视频互斥。已有参考图（含角色）时不再传 scene.imageUrl，
        // 参考图即唯一素材输入（用户行内参考图/资产图优先于场景首帧图）。
        if (!refImages.isEmpty()) {
            sourceImageUrl = null;
        }

        String taskId;
        try {
            taskId = generationService.createVideoTaskWithRole(
                    conversation, row.getSceneId(), prompt, row.getModel(), row.getResolution(),
                    row.getSize(), row.getAspectRatio(),
                    row.getDuration() == null ? null : String.valueOf(row.getDuration()),
                    row.getNegativePrompt(), refImages, sourceImageUrl);
        } catch (Exception e) {
            failRow(row, e.getMessage() != null ? e.getMessage() : "视频任务创建失败");
            return;
        }
        if (taskId == null || taskId.isBlank()) {
            failRow(row, "视频任务创建失败：返回空 taskId");
            return;
        }

        row.setTaskId(taskId);
        queueMapper.updateById(row);

        // 轮询直至终态（复用 VideoPollExecutor：终态按 taskId 反查 scene 回写 videoUrl/videoStatus）
        int attempts = 0;
        while (true) {
            // 取消检查：running 期间被取消则停止跟踪（上游无法真正停止）
            StoryboardVideoQueue live = queueMapper.selectById(row.getId());
            if (live == null || "cancelled".equals(live.getStatus())) {
                log.info("[分镜视频队列] 运行中已取消，停止跟踪: queueId={}", row.getId());
                return;
            }
            if (attempts >= 90) {
                failRow(row, "视频生成超时，请重试");
                return;
            }
            attempts++;
            try {
                Map<String, String> result = generationService.pollVideoTask(taskId);
                String status = result.get("status");
                Integer progress = parseProgress(result.get("progress"));
                if ("completed".equals(status)) {
                    finishRow(row, "completed");
                    return;
                }
                if ("failed".equals(status)) {
                    failRow(row, result.getOrDefault("error", "视频生成失败"));
                    return;
                }
                if (progress != null) {
                    row.setProgress(progress);
                    queueMapper.updateById(row);
                }
            } catch (Exception e) {
                log.warn("[分镜视频队列] 轮询异常(不终止): queueId={}, error={}", row.getId(), e.getMessage());
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void finishRow(StoryboardVideoQueue row, String status) {
        StoryboardVideoQueue live = queueMapper.selectById(row.getId());
        if (live == null || "cancelled".equals(live.getStatus())) return;
        live.setStatus(status);
        live.setProgress(100);
        live.setFinishedAt(OffsetDateTime.now());
        live.setError(null);
        queueMapper.updateById(live);
        recordVideoGeneration(live, status, null);
    }

    private void failRow(StoryboardVideoQueue row, String error) {
        StoryboardVideoQueue live = queueMapper.selectById(row.getId());
        if (live == null) return;
        live.setStatus("failed");
        live.setError(error);
        live.setFinishedAt(OffsetDateTime.now());
        queueMapper.updateById(live);
        markSceneFailed(row.getSceneId(), error);
        recordVideoGeneration(live, "failed", error);
    }

    /** 终态后写一条 agent_assets 生成记录，供「生成记录」展示（best-effort，失败仅记日志）。 */
    private void recordVideoGeneration(StoryboardVideoQueue row, String status, String error) {
        if (row.getConversationId() == null || row.getConversationId().isBlank()) return;
        AgentConversation conversation = conversationMapper.selectById(row.getConversationId());
        if (conversation == null) return;
        Scene scene = null;
        try {
            scene = storyboardClient.getScene(row.getSceneId());
        } catch (Exception e) {
            log.warn("[分镜视频队列] 生成记录读取分镜失败: sceneId={}, error={}", row.getSceneId(), e.getMessage());
        }
        String url = null;
        List<String> resultUrls = List.of();
        if (scene != null && scene.getVideoUrl() != null && !scene.getVideoUrl().isBlank()) {
            url = scene.getVideoUrl();
            resultUrls = List.of(scene.getVideoUrl());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (row.getModel() != null && !row.getModel().isBlank()) params.put("model", row.getModel());
        if (row.getResolution() != null && !row.getResolution().isBlank()) params.put("resolution", row.getResolution());
        if (row.getSize() != null && !row.getSize().isBlank()) params.put("size", row.getSize());
        if (row.getAspectRatio() != null && !row.getAspectRatio().isBlank()) params.put("aspectRatio", row.getAspectRatio());
        if (row.getDuration() != null) params.put("duration", row.getDuration());
        Long durationMs = null;
        if (row.getStartedAt() != null && row.getFinishedAt() != null) {
            durationMs = Duration.between(row.getStartedAt(), row.getFinishedAt()).toMillis();
        }
        generationService.recordStoryboardGeneration(conversation, row.getSceneId(), "video", "storyboard_video",
                row.getPrompt(), row.getModel(), row.getTaskId(), url, resultUrls, status, error, durationMs, params);
    }

    private void markSceneQueued(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) return;
        Scene scene = storyboardClient.getScene(sceneId);
        if (scene == null) return;
        scene.setVideoStatus("queued");
        storyboardClient.updateScene(scene);
    }

    private void markSceneRunning(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) return;
        Scene scene = storyboardClient.getScene(sceneId);
        if (scene == null) return;
        scene.setVideoStatus("generating");
        storyboardClient.updateScene(scene);
    }

    private void markSceneCancelled(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) return;
        Scene scene = storyboardClient.getScene(sceneId);
        if (scene == null) return;
        scene.setVideoStatus("cancelled");
        storyboardClient.updateScene(scene);
    }

    private void markSceneFailed(String sceneId, String error) {
        if (sceneId == null || sceneId.isBlank()) return;
        Scene scene = storyboardClient.getScene(sceneId);
        if (scene == null) return;
        scene.setVideoStatus("failed");
        storyboardClient.updateScene(scene);
    }

    private static Integer parseProgress(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int v = Integer.parseInt(raw);
            return Math.max(0, Math.min(100, v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return String.join(",", values.stream().filter(v -> v != null && !v.isBlank()).toList());
    }

    /** 带角色参考图 → JSON 数组字符串（存 refImageUrls）。 */
    private static String rolesToJson(List<java.util.Map<String, String>> roles) {
        if (roles == null || roles.isEmpty()) return null;
        List<java.util.Map<String, String>> clean = roles.stream()
                .filter(m -> m != null && m.get("url") != null && !m.get("url").isBlank())
                .toList();
        if (clean.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(clean);
        } catch (Exception e) {
            log.warn("[分镜视频队列] 参考图角色序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解析参考图（带角色 JSON 或旧逗号列表），全部转 ReferenceImage；非法 role 回退 REFERENCE。 */
    private static List<ReferenceImage> buildRefImages(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String t = raw.trim();
        // 旧格式：逗号分隔 URL（全部 REFERENCE）
        if (!t.startsWith("[")) {
            return split(raw).stream()
                    .filter(u -> u != null && !u.isBlank())
                    .map(ReferenceImage::ofReference)
                    .toList();
        }
        // 新格式：[{url, role}]
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(t);
            List<ReferenceImage> out = new ArrayList<>();
            for (var node : arr) {
                String url = node.path("url").asText("");
                if (url.isBlank()) continue;
                String role = node.path("role").asText("");
                out.add(new ReferenceImage(url, mapRole(role)));
            }
            return out;
        } catch (Exception e) {
            log.warn("[分镜视频队列] 参考图角色 JSON 解析失败，按逗号回退: {}", e.getMessage());
            return split(raw).stream().map(ReferenceImage::ofReference).toList();
        }
    }

    private static ReferenceImageRole mapRole(String role) {
        if (role == null || role.isBlank()) return ReferenceImageRole.REFERENCE;
        return switch (role.trim().toUpperCase()) {
            case "FIRST_FRAME", "首帧" -> ReferenceImageRole.FIRST_FRAME;
            case "LAST_FRAME", "尾帧" -> ReferenceImageRole.LAST_FRAME;
            default -> ReferenceImageRole.REFERENCE;
        };
    }

    /** 分镜挂载参考图 → ReferenceImage（含 role；FIRST_LAST_FRAME 按首/尾/中间拆分）。 */
    private static List<ReferenceImage> mapSceneReferenceRoles(List<Map<String, Object>> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        List<ReferenceImage> out = new ArrayList<>();
        int n = refs.size();
        for (int i = 0; i < n; i++) {
            Map<String, Object> ref = refs.get(i);
            String url = ref.get("url") == null ? "" : String.valueOf(ref.get("url"));
            if (url.isBlank()) continue;
            String role = ref.get("role") == null ? "" : String.valueOf(ref.get("role"));
            ReferenceImageRole r;
            if ("FIRST_LAST_FRAME".equalsIgnoreCase(role)) {
                r = i == 0 ? ReferenceImageRole.FIRST_FRAME
                        : i == n - 1 ? ReferenceImageRole.LAST_FRAME
                        : ReferenceImageRole.REFERENCE;
            } else {
                r = mapRole(role);
            }
            out.add(new ReferenceImage(url, r));
        }
        return out;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(v -> !v.isBlank()).toList();
    }
}
