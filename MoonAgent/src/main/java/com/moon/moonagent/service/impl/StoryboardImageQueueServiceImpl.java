package com.moon.moonagent.service.impl;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.StoryboardImageEnqueueItem;
import com.moon.moonagent.dto.request.StoryboardImageEnqueueRequest;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.entity.StoryboardImageTask;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.StoryboardImageTaskMapper;
import com.moon.moonagent.service.StoryboardImageQueueService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分镜图片队列实现。
 *
 * <p>串行执行：{@code processing} 单飞锁保证同一时刻只推进一个 queued 任务；
 * 支持编辑已有图片（mode=edit）和自动生成分镜图片（mode=generate），
 * 终态由 {@code ImageGenerationServiceImpl} 按 sceneId 回写 scenes.imageUrl。
 */
@Service
@RequiredArgsConstructor
public class StoryboardImageQueueServiceImpl implements StoryboardImageQueueService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardImageQueueServiceImpl.class);

    /** 单飞锁：同一时刻只跑一个队列任务（一个分镜一个分镜完善）。 */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final StoryboardImageTaskMapper queueMapper;
    private final AgentConversationMapper conversationMapper;
    private final StoryboardClient storyboardClient;
    private final AgentGenerationService generationService;

    @Override
    public List<StoryboardImageTask> enqueue(StoryboardImageEnqueueRequest request) {
        List<StoryboardImageTask> created = new ArrayList<>();
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("入队完善图片分镜不能为空");
        }
        for (StoryboardImageEnqueueItem item : request.items()) {
            if (item == null || item.sceneId() == null || item.sceneId().isBlank()) continue;
            StoryboardImageTask row = new StoryboardImageTask();
            row.setSceneId(item.sceneId().trim());
            row.setProjectId(request.projectId());
            row.setConversationId(request.conversationId());
            row.setStatus("queued");
            row.setPrompt(item.prompt());
            row.setModel(item.model());
            row.setSize(item.size());
            row.setQuality(item.quality());
            row.setN(item.n() != null && item.n() > 0 ? item.n() : 1);
            row.setMode(normalizeMode(item.mode()));
            row.setSourceImageUrl(item.sourceImageUrl());
            row.setRefImageUrls(join(item.refImageUrls()));
            row.setProgress(0);
            queueMapper.insert(row);
            created.add(row);
            log.info("[分镜图片队列] 入队: queueId={}, sceneId={}, mode={}, prompt={}",
                    row.getId(), row.getSceneId(), row.getMode(), row.getPrompt());
        }
        process();
        return created;
    }

    @Override
    public List<StoryboardImageTask> listByProject(String projectId, String status) {
        if (projectId == null || projectId.isBlank()) return List.of();
        if ("active".equalsIgnoreCase(status)) return queueMapper.findActiveByProject(projectId);
        return queueMapper.findByProjectId(projectId);
    }

    @Override
    public String getProjectId(String queueId) {
        if (queueId == null || queueId.isBlank()) return null;
        StoryboardImageTask row = queueMapper.selectById(queueId);
        return row == null ? null : row.getProjectId();
    }

    @Override
    public void cancel(String queueId) {
        StoryboardImageTask row = queueMapper.selectById(queueId);
        if (row == null) return;
        if (!"queued".equals(row.getStatus()) && !"running".equals(row.getStatus())) return;
        row.setStatus("cancelled");
        row.setFinishedAt(OffsetDateTime.now());
        queueMapper.updateById(row);
        log.info("[分镜完善图片队列] 取消: queueId={}, sceneId={}", queueId, row.getSceneId());
    }

    /** 串行处理队列：单飞锁，始终只推进最早的 queued 任务。 */
    private void process() {
        if (!processing.compareAndSet(false, true)) return;
        new Thread(this::drain, "storyboard-image-queue-drainer").start();
    }

    private void drain() {
        try {
            while (true) {
                StoryboardImageTask next = queueMapper.findNextQueued();
                if (next == null) break;
                runOne(next);
            }
        } catch (Exception e) {
            log.error("[分镜完善图片队列] 队列执行异常: {}", e.getMessage(), e);
        } finally {
            processing.set(false);
        }
    }

    /** 运行单条队列任务：置 running → 生图/图改图（同步） → 终态回写由 imageService 处理。 */
    private void runOne(StoryboardImageTask row) {
        row.setStatus("running");
        row.setStartedAt(OffsetDateTime.now());
        row.setError(null);
        queueMapper.updateById(row);

        AgentConversation conversation = conversationMapper.selectById(row.getConversationId());
        if (conversation == null) {
            failRow(row, "会话上下文缺失，无法完善图片");
            return;
        }

        String mode = normalizeMode(row.getMode());
        // edit 模式源图：显式传入 > scene.imageUrl；generate 模式不使用源图。
        String sourceImageUrl = row.getSourceImageUrl();
        if ("edit".equals(mode) && (sourceImageUrl == null || sourceImageUrl.isBlank())) {
            try {
                Scene scene = storyboardClient.getScene(row.getSceneId());
                if (scene != null) sourceImageUrl = scene.getImageUrl();
            } catch (Exception e) {
                log.warn("[分镜完善图片队列] 读取分镜失败: sceneId={}, error={}", row.getSceneId(), e.getMessage());
            }
        }

        // 完善提示词：入队传入 > 分镜 imagePrompt > 分镜脚本
        String prompt = row.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            try {
                Scene scene = storyboardClient.getScene(row.getSceneId());
                if (scene != null) {
                    prompt = scene.getImagePrompt() != null && !scene.getImagePrompt().isBlank()
                            ? scene.getImagePrompt()
                            : (scene.getScriptContent() != null && !scene.getScriptContent().isBlank()
                            ? scene.getScriptContent() : "完善这张图片");
                }
            } catch (Exception e) {
                prompt = "完善这张图片";
            }
        }

        List<String> refImages = split(row.getRefImageUrls());
        try {
            Map<String, Object> result = generationService.generateImage(
                    conversation, row.getSceneId(), prompt, row.getModel(), row.getSize(),
                    row.getQuality(), row.getN(), mode, refImages,
                    "edit".equals(mode) ? sourceImageUrl : null);
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) result.getOrDefault("imageUrls", List.of());
            if (urls.isEmpty()) {
                failRow(row, "图片生成返回空 URL");
                return;
            }
            finishRow(row, "completed");
        } catch (Exception e) {
            failRow(row, e.getMessage() != null ? e.getMessage() : "图片完善失败");
        }
    }

    private void finishRow(StoryboardImageTask row, String status) {
        StoryboardImageTask live = queueMapper.selectById(row.getId());
        if (live == null || "cancelled".equals(live.getStatus())) return;
        live.setStatus(status);
        live.setProgress(100);
        live.setFinishedAt(OffsetDateTime.now());
        live.setError(null);
        queueMapper.updateById(live);
    }

    private void failRow(StoryboardImageTask row, String error) {
        StoryboardImageTask live = queueMapper.selectById(row.getId());
        if (live == null) return;
        live.setStatus("failed");
        live.setError(error);
        live.setFinishedAt(OffsetDateTime.now());
        queueMapper.updateById(live);
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

    static String normalizeMode(String mode) {
        return "generate".equalsIgnoreCase(mode) ? "generate" : "edit";
    }
}
