package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.video.ReferenceImage;
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
            row.setRefImageUrls(join(item.refImageUrls()));
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
        LinkedHashSet<String> refs = new LinkedHashSet<>(split(row.getRefImageUrls()));
        if (row.getRefAssetIds() != null && !row.getRefAssetIds().isBlank()) {
            try {
                List<AssetVO> sceneAssets = assetService.sceneAssets(row.getSceneId(), "video");
                refs.addAll(assetService.buildReferenceImages(sceneAssets));
            } catch (Exception e) {
                log.warn("[分镜视频队列] 资产参考图聚合失败: {}", e.getMessage());
            }
        }
        List<ReferenceImage> refImages = refs.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(ReferenceImage::ofReference)
                .toList();

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

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(v -> !v.isBlank()).toList();
    }
}
