package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.ai.storyboard.production.StoryboardPromptAssembly;
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
import com.moon.moonagent.ai.agent.storyboard.VideoGenerationPolicy;
import com.moon.moonagent.service.AssetService;
import com.moon.moonagent.service.StoryboardVideoQueueService;
import com.moon.moonagent.util.PollingBackoff;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 调度单飞锁：同一时刻只存在一组 drain worker。 */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final StoryboardVideoQueueMapper queueMapper;
    private final AgentConversationMapper conversationMapper;
    private final StoryboardClient storyboardClient;
    private final AssetService assetService;
    private final AgentGenerationService generationService;
    private final ExecutorService drainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final PollingBackoff pollBackoff = PollingBackoff.video();

    @Value("${storyboard.video.queue-concurrency:2}")
    private int queueConcurrency = 2;

    @Override
    public List<StoryboardVideoQueue> enqueue(StoryboardVideoEnqueueRequest request) {
        List<StoryboardVideoQueue> created = new ArrayList<>();
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("入队分镜不能为空");
        }
        for (StoryboardVideoEnqueueItem item : request.items()) {
            if (item == null || item.sceneId() == null || item.sceneId().isBlank()) continue;
            String sceneId = item.sceneId().trim();
            Long active = queueMapper.selectCount(new LambdaQueryWrapper<StoryboardVideoQueue>()
                    .eq(StoryboardVideoQueue::getSceneId, sceneId)
                    .in(StoryboardVideoQueue::getStatus, List.of("queued", "running")));
            if (active != null && active > 0) {
                log.info("[分镜视频队列] 已有活动任务，跳过重复入队: sceneId={}", sceneId);
                continue;
            }
            StoryboardVideoQueue row = new StoryboardVideoQueue();
            row.setSceneId(sceneId);
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
        drainExecutor.submit(() -> {
            try {
                int workers = Math.max(1, Math.min(8, queueConcurrency));
                List<CompletableFuture<Void>> running = new java.util.ArrayList<>(workers);
                for (int index = 0; index < workers; index++) {
                    running.add(CompletableFuture.runAsync(this::drain, drainExecutor));
                }
                CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).join();
            } finally {
                processing.set(false);
                // 补上 drain 退出与 processing 复位之间入队的任务。
                if (queueMapper.findNextQueued() != null) {
                    process();
                }
            }
        });
    }

    private void drain() {
        try {
            while (true) {
                StoryboardVideoQueue next = queueMapper.claimNextQueued();
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
        if (scene == null) {
            failRow(row, "分镜不存在，无法生成视频");
            return;
        }
        markSceneRunning(scene);

        List<Scene> orderedScenes = List.of(scene);
        try {
            orderedScenes = storyboardClient.getProjectScenesFresh(scene.getProjectId()).stream()
                    .sorted(java.util.Comparator.comparing(
                            Scene::getSceneNumber,
                            java.util.Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        } catch (Exception e) {
            log.warn("[分镜视频队列] 读取项目分镜顺序失败: projectId={}, error={}",
                    scene.getProjectId(), e.getMessage());
        }
        int sceneIndex = indexOfScene(orderedScenes, scene.getId());
        Scene nextScene = sceneIndex >= 0 && sceneIndex + 1 < orderedScenes.size()
                ? orderedScenes.get(sceneIndex + 1) : null;

        try {
            ensureSceneImage(conversation, scene);
        } catch (Exception e) {
            failRow(row, e.getMessage() == null ? "当前分镜图片生成失败" : e.getMessage());
            return;
        }
        if (nextScene != null) {
            try {
                ensureSceneImage(conversation, nextScene);
            } catch (Exception e) {
                log.warn("[分镜视频队列] 下一镜图片生成失败，降级仅首帧: sceneId={}, error={}",
                        nextScene.getId(), e.getMessage());
            }
        }

        String prompt = row.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = scene != null && scene.getVideoPrompt() != null && !scene.getVideoPrompt().isBlank()
                    ? scene.getVideoPrompt() : (scene != null && scene.getScriptContent() != null && !scene.getScriptContent().isBlank()
                    ? scene.getScriptContent() : "分镜视频生成");
        }
        // 衔接说明（继承项/禁改项）在送进模型前合并，保证跨镜承接不断
        if (scene != null) {
            prompt = StoryboardPromptAssembly.withContinuity(prompt, scene.getContinuityNote());
        }

        // 参考图：分镜已生成图作首帧 + 场景关联「视频用途」资产图 + 用户上传参考图
        String sourceImageUrl = row.getSourceImageUrl();
        if (sourceImageUrl == null || sourceImageUrl.isBlank()) {
            sourceImageUrl = scene != null ? scene.getImageUrl() : null;
        }
        List<ReferenceImage> refImages = buildRefImages(row.getRefImageUrls());

        // 资产参考图（视频用途）在角色图之后以 REFERENCE 追加（不覆盖首尾帧角色）。
        // 直接按 scene_assets 关联聚合，不依赖入队时是否显式传 refAssetIds，
        // 避免分镜已关联资产但队列行 refAssetIds 为空导致 asset 图被丢成纯文生视频。
        LinkedHashSet<String> assetRefs = new LinkedHashSet<>();
        try {
            List<AssetVO> sceneAssets = assetService.sceneAssets(row.getSceneId(), "video");
            if (sceneAssets != null && !sceneAssets.isEmpty()) {
                assetRefs.addAll(assetService.buildReferenceImages(sceneAssets));
            }
        } catch (Exception e) {
            log.warn("[分镜视频队列] 资产参考图聚合失败: {}", e.getMessage());
        }
        // 分镜已挂载的参考图（Agent 自动挂载）按 role 追加，且【优先】：
        // 先加入带 role 的图（FIRST_LAST_FRAME → 首帧/尾帧），避免被随后纯 REFERENCE 的资产/用户图
        // 按 URL 去重挤掉，否则 2 张资产图会退化成「一张 REFERENCE」，模型只读到首张。
        List<ReferenceImage> combined = new ArrayList<>();
        List<Map<String, Object>> sceneRefs = List.of();
        try {
            prepareManagedFrames(conversation, scene, nextScene);
            sceneRefs = storyboardClient.getSceneReferences(row.getSceneId());
            List<ReferenceImage> roleRefs = mapSceneReferenceImages(sceneRefs);
            for (ReferenceImage ref : roleRefs) {
                boolean dup = combined.stream().anyMatch(r -> ref.url().equals(r.url()));
                if (!dup) combined.add(ref);
            }
        } catch (Exception e) {
            log.warn("[分镜视频队列] 读取分镜参考素材失败: {}", e.getMessage());
        }
        // 队列行/用户显式参考图（可能已带 role，不覆盖分镜参考的角色）
        for (ReferenceImage ref : refImages) {
            boolean dup = combined.stream().anyMatch(r -> ref.url().equals(r.url()));
            if (!dup) combined.add(ref);
        }
        // 资产参考图（视频用途）以纯 REFERENCE 追加，仅在未被角色图覆盖时加入
        for (String u : assetRefs) {
            if (u == null || u.isBlank()) continue;
            boolean dup = combined.stream().anyMatch(r -> u.equals(r.url()));
            if (!dup) combined.add(ReferenceImage.ofReference(u));
        }
        refImages = combined;
        List<String> referenceVideos = sceneReferenceVideoUrls(sceneRefs);
        // H3 约束：多模态参考素材与首帧图生视频互斥。已有参考图（含角色）时不再传 scene.imageUrl，
        // 参考图即唯一素材输入（用户行内参考图/资产图优先于场景首帧图）。
        if (!refImages.isEmpty()) {
            sourceImageUrl = null;
        }

        String taskId;
        try {
            String duration = row.getDuration() == null ? null : String.valueOf(row.getDuration());
            VideoGenerationPolicy.requireStoryboardShotAllowed(duration);
            taskId = generationService.createVideoTaskWithRole(
                    conversation, row.getSceneId(), prompt, row.getModel(), row.getResolution(),
                    row.getSize(), row.getAspectRatio(),
                    duration,
                    row.getNegativePrompt(), refImages, sourceImageUrl, referenceVideos, false);
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
        Integer lastProgress = null;
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
                if (progress != null && !progress.equals(lastProgress)) {
                    row.setProgress(progress);
                    queueMapper.updateById(row);
                    lastProgress = progress;
                }
            } catch (Exception e) {
                log.warn("[分镜视频队列] 轮询异常(不终止): queueId={}, error={}", row.getId(), e.getMessage());
            }
            try {
                Thread.sleep(pollBackoff.delayMillis(attempts));
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

    private void markSceneRunning(Scene scene) {
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

    /**
     * Ensure the target has a first frame and, when possible, use the next
     * storyboard's primary image as the last frame. User-provided frame
     * references always win and are never replaced here.
     */
    void prepareManagedFrames(AgentConversation conversation, Scene scene, Scene nextScene) {
        List<Map<String, Object>> existing;
        try {
            existing = storyboardClient.getSceneReferences(scene.getId());
        } catch (Exception e) {
            log.warn("[分镜视频队列] 读取已有首尾帧失败: sceneId={}, error={}",
                    scene.getId(), e.getMessage());
            existing = List.of();
        }
        List<Map<String, Object>> managed = new ArrayList<>();
        String firstUrl = primaryImageUrl(scene);
        if (!firstUrl.isBlank() && !hasExplicitFrame(existing, "FIRST_FRAME")) {
            managed.add(managedFrame(firstUrl, scene.getId(), "FIRST_FRAME"));
        }
        String lastUrl = primaryImageUrl(nextScene);
        if (!lastUrl.isBlank() && !lastUrl.equals(firstUrl)
                && !hasExplicitFrame(existing, "LAST_FRAME")) {
            managed.add(managedFrame(lastUrl, nextScene.getId(), "LAST_FRAME"));
        }
        try {
            storyboardClient.replaceManagedVideoFrames(scene.getId(), managed);
        } catch (Exception e) {
            throw new IllegalStateException("首尾帧素材写入失败: " + e.getMessage(), e);
        }
    }

    private void ensureSceneImage(AgentConversation conversation, Scene scene) {
        if (scene == null || !primaryImageUrl(scene).isBlank()) return;
        String prompt = nonBlank(scene.getImagePrompt(), scene.getScriptContent(), "分镜画面");
        Map<String, Object> generated = generationService.generateImage(
                conversation,
                scene.getId(),
                prompt,
                scene.getImageModel(),
                scene.getImageSize(),
                scene.getImageQuality(),
                scene.getImageN() == null || scene.getImageN() <= 0 ? 1 : scene.getImageN(),
                "generate",
                List.of(),
                null);
        if (generated == null || generated.isEmpty()) {
            throw new IllegalStateException("分镜图片生成失败，无法继续创建视频");
        }
        Object urls = generated.get("imageUrls");
        if (urls instanceof List<?> list && !list.isEmpty()) {
            String url = String.valueOf(list.getFirst());
            if (!url.isBlank()) {
                scene.setImageUrl(url);
            }
        }
    }

    @PreDestroy
    void shutdownDrainExecutor() {
        drainExecutor.shutdownNow();
    }

    private static boolean hasExplicitFrame(List<Map<String, Object>> refs, String role) {
        if (refs == null || refs.isEmpty()) return false;
        return refs.stream().anyMatch(ref -> {
            if (ref == null || !isType(ref, "image")) return false;
            String value = ref.get("role") == null ? "" : String.valueOf(ref.get("role"));
            if (!role.equalsIgnoreCase(value)) return false;
            String sourceType = ref.get("sourceType") == null ? "" : String.valueOf(ref.get("sourceType"));
            return !"scene_image".equalsIgnoreCase(sourceType);
        });
    }

    private static Map<String, Object> managedFrame(String url, String sourceSceneId, String role) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("url", url);
        frame.put("type", "image");
        frame.put("purpose", "video");
        frame.put("role", role);
        frame.put("sourceType", "scene_image");
        frame.put("sourceId", sourceSceneId);
        return frame;
    }

    private static int indexOfScene(List<Scene> scenes, String sceneId) {
        if (scenes == null || sceneId == null) return -1;
        for (int i = 0; i < scenes.size(); i++) {
            if (sceneId.equals(scenes.get(i).getId())) return i;
        }
        return -1;
    }

    private static Scene findScene(List<Scene> scenes, String sceneId) {
        int index = indexOfScene(scenes, sceneId);
        return index < 0 ? null : scenes.get(index);
    }

    static String primaryImageUrl(Scene scene) {
        if (scene == null) return "";
        if (scene.getImageUrl() != null && !scene.getImageUrl().isBlank()) {
            return scene.getImageUrl().trim();
        }
        if (scene.getImageUrls() != null && !scene.getImageUrls().isBlank()) {
            return split(scene.getImageUrls()).stream().findFirst().orElse("");
        }
        return "";
    }

    private static String nonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return "";
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
            return JSON.writeValueAsString(clean);
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
            var arr = JSON.readTree(t);
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

    /** 分镜挂载参考图 → ReferenceImage（仅 image；FIRST_LAST_FRAME 按首/尾/中间拆分）。 */
    static List<ReferenceImage> mapSceneReferenceImages(List<Map<String, Object>> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        List<Map<String, Object>> images = refs.stream()
                .filter(ref -> ref != null && isType(ref, "image"))
                .toList();
        List<ReferenceImage> out = new ArrayList<>();
        int n = images.size();
        for (int i = 0; i < n; i++) {
            Map<String, Object> ref = images.get(i);
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

    static List<String> sceneReferenceVideoUrls(List<Map<String, Object>> refs) {
        if (refs == null || refs.isEmpty()) return List.of();
        return refs.stream()
                .filter(ref -> ref != null && isType(ref, "video"))
                .map(ref -> ref.get("url") == null ? "" : String.valueOf(ref.get("url")))
                .filter(url -> !url.isBlank())
                .distinct()
                .limit(1)
                .toList();
    }

    private static boolean isType(Map<String, Object> ref, String expected) {
        Object type = ref.get("type");
        return type == null || type.toString().isBlank() || expected.equalsIgnoreCase(type.toString());
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(v -> !v.isBlank()).toList();
    }
}
