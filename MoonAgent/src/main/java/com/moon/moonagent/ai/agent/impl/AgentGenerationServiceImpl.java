package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordTypes;
import com.storyboard.common.generation.GenerationRecordUpdate;
import com.storyboard.common.generation.GenerationRecordWriter;
import com.storyboard.common.generation.GenerationReference;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 生成编排服务（智能体生成后端化重构）。
 *
 * 在 HITL 表单提交事件后由后端直接执行生成（写分镜 / 生图 / 生视频），
 * 替代原工作流内 HTTP 节点回调（/api/**）。
 * 归属校验一律以 conversation.getProjectId() 为准。
 */
@Service
@RequiredArgsConstructor
public class AgentGenerationServiceImpl implements AgentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerationServiceImpl.class);

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final StoryboardClient storyboardClient;
    private final GenerationRecordWriter generationRecordWriter;

    /** 单条参考 URL 上限（base64 过大不落库，详情显示为「参考素材过大」占位） */
    private static final int MAX_REF_URL_LENGTH = 8192;


    /** 清洗外部传入参数：未解析的变量引用（含 structured_output.）视为无效值返回 null） */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.contains("structured_output.")) return null;
        return value;
    }

    /** 覆盖写分镜：先清空现有再按 1..N 连续编号批量写入（HTTP 两步调用，非事务） */
    public int replaceScript(String projectId, List<AgentSceneItem> scenes) {
        return replaceScript(projectId, null, scenes);
    }

    @Override
    public int replaceScript(String projectId, String conversationId, List<AgentSceneItem> scenes) {
        storyboardClient.deleteProjectScenes(projectId);
        if (scenes == null || scenes.isEmpty()) return 0;
        List<AgentSceneItem> normalized = new ArrayList<>(scenes.size());
        int number = 1;
        for (AgentSceneItem item : scenes) {
            normalized.add(new AgentSceneItem(number++, item.scriptContent(), item.imagePrompt(),
                    item.videoPrompt(), item.negativePrompt(), item.cameraMovement(),
                    item.shotType(), item.soundDesign(), item.duration()));
        }
        return writeScript(projectId, conversationId, normalized);
    }

    /** 批量写分镜（HTTP 批量插入） */
    public int writeScript(String projectId, List<AgentSceneItem> scenes) {
        return writeScript(projectId, null, scenes);
    }

    @Override
    public int writeScript(String projectId, String conversationId, List<AgentSceneItem> scenes) {
        if (scenes == null || scenes.isEmpty()) return 0;
        List<Scene> batch = new ArrayList<>();
        for (var item : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(projectId);
            scene.setConversationId(conversationId);
            scene.setSceneNumber(item.sceneNumber());
            scene.setScriptContent(item.scriptContent());
            scene.setImagePrompt(item.imagePrompt());
            scene.setVideoPrompt(item.videoPrompt());
            scene.setNegativePrompt(item.negativePrompt());
            scene.setCameraMovement(item.cameraMovement());
            scene.setShotType(item.shotType());
            scene.setSoundDesign(item.soundDesign());
            scene.setDuration(item.duration());
            batch.add(scene);
        }
        storyboardClient.batchInsertScenes(batch);
        log.info("Agent 生成编排：写入 {} 个分镜到项目 {}", batch.size(), projectId);
        return batch.size();
    }

    /**
     * 只替换指定 scene_number 的分镜（部分替换，2026-08-28）：更新这些编号的现有分镜（保留 id 与素材关联），
     * 不存在则新插入；不在 {@code scenes} 里出现的其他分镜完全不动。
     * 用于「只针对分镜N优化」——绝不清空/覆盖未指定的分镜（原 replaceScript 会 deleteProjectScenes 清空全部）。
     */
    @Override
    public int updateScenesByNumber(String projectId, List<AgentSceneItem> scenes) {
        return updateScenesByNumber(projectId, null, scenes);
    }

    @Override
    public int updateScenesByNumber(String projectId, String conversationId, List<AgentSceneItem> scenes) {
        if (scenes == null || scenes.isEmpty()) return 0;
        Map<Integer, Scene> byNumber = new HashMap<>();
        for (Scene s : storyboardClient.getProjectScenes(projectId)) {
            byNumber.put(s.getSceneNumber(), s);
        }
        int updated = 0;
        for (var item : scenes) {
            Scene newScene = new Scene();
            newScene.setProjectId(projectId);
            newScene.setConversationId(conversationId);
            newScene.setSceneNumber(item.sceneNumber());
            newScene.setScriptContent(item.scriptContent());
            newScene.setImagePrompt(item.imagePrompt());
            newScene.setVideoPrompt(item.videoPrompt());
            newScene.setNegativePrompt(item.negativePrompt());
            newScene.setCameraMovement(item.cameraMovement());
            newScene.setShotType(item.shotType());
            newScene.setSoundDesign(item.soundDesign());
            newScene.setDuration(item.duration());
            Scene existing = byNumber.get(item.sceneNumber());
            if (existing != null) {
                // 同编号已存在 → 更新（带原 id，保留 imageUrl/videoUrl/素材关联等已生成资产不丢）
                newScene.setId(existing.getId());
                newScene.setImageUrl(existing.getImageUrl());
                newScene.setVideoUrl(existing.getVideoUrl());
                newScene.setImageStatus(existing.getImageStatus());
                newScene.setVideoStatus(existing.getVideoStatus());
                newScene.setVideoTaskId(existing.getVideoTaskId());
                newScene.setImageTaskId(existing.getImageTaskId());
                storyboardClient.updateScene(newScene);
            } else {
                // 不存在 → 新插入
                storyboardClient.insertScene(newScene);
            }
            updated++;
        }
        log.info("Agent 生成编排：按编号部分替换 {} 个分镜到项目 {}", updated, projectId);
        return updated;
    }

    /**
     * 生图并落库（支持 quality/n 多张）。
     * sceneId 非空 → 更新真实分镜（返回 imageUrls）；为空 → 每张图落一条 agent_assets（返回 imageUrls + assetIds）。
     * mode="edit" 走图改图（generatedImageUrl 为源图，恒单张）。
     */
    public Map<String, Object> generateImage(AgentConversation conversation, String sceneId,
                                             String prompt, String model, String size, String quality, Integer n,
                                             String mode, List<String> referenceImages, String generatedImageUrl) {
        return generateImage(conversation, sceneId, prompt, model, size, quality, null, n,
                mode, referenceImages, generatedImageUrl);
    }

    @Override
    public Map<String, Object> generateImage(AgentConversation conversation, String sceneId,
                                             String prompt, String model, String size, String quality,
                                             String aspectRatio, Integer n,
                                             String mode, List<String> referenceImages, String generatedImageUrl) {
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        String cleanPrompt = sanitize(prompt);
        String cleanModel = sanitize(model);
        String cleanSize = sanitize(size);
        String cleanQuality = sanitize(quality);
        String cleanAspectRatio = sanitize(aspectRatio);
        String cleanSource = sanitize(generatedImageUrl);
        int count = (n != null && n > 0) ? n : 1;
        String genType = classifyImageGenType(mode, referenceImages, cleanSource, count);
        String recordId = newRecord(conversation, "image", genType, cleanPrompt, cleanModel,
                effectiveSceneId, buildImageParams(cleanSize, cleanQuality, count, mode),
                buildReferenceUrls(referenceImages, cleanSource), "generating");
        long startMs = System.currentTimeMillis();
        try {
            List<String> urls = imageService.generateImages(
                effectiveSceneId, cleanPrompt, cleanModel, cleanSize, cleanQuality, cleanAspectRatio,
                referenceImages, mode, cleanSource, count);
            updateRecord(recordId, "completed", urls.isEmpty() ? null : urls.getFirst(), urls, null, startMs);
            Map<String, Object> out = new HashMap<>();
            out.put("imageUrls", urls);
            out.put("assetIds", recordId == null ? List.of() : List.of(recordId));
            return out;
        } catch (Exception e) {
            updateRecord(recordId, "failed", null, null, friendlyMessage(e), startMs);
            throw e;
        }
    }

    /**
     * 创建视频任务并落库（queued）。
     * duration 为字符串（快照 plan 中可能为 String），解析失败用默认值。
     */
    public String createVideoTask(AgentConversation conversation, String sceneId,
                                  String prompt, String model, String resolution, String size,
                                  String aspectRatio, String duration, String negativePrompt,
                                  List<String> referenceImages, String generatedImageUrl) {
        Integer durationInt = parseDuration(duration);
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        String cleanPrompt = sanitize(prompt);
        String cleanModel = sanitize(model);
        String cleanResolution = sanitize(resolution);
        String cleanSize = sanitize(size);
        String cleanAspectRatio = sanitize(aspectRatio);
        String cleanNegative = sanitize(negativePrompt);
        String cleanSource = sanitize(generatedImageUrl);
        String genType = classifyVideoGenType(referenceImages, cleanSource);
        // 每次创建任务都先落 queued 记录；终态由 VideoPollExecutor 按 taskId 同步更新。
        String recordId = newRecord(conversation, "video", genType, cleanPrompt, cleanModel, effectiveSceneId,
                buildVideoParams(cleanResolution, cleanSize, cleanAspectRatio, duration, cleanNegative),
                buildReferenceUrls(referenceImages, cleanSource), "queued");
        long startMs = System.currentTimeMillis();
        String taskId;
        try {
            taskId = videoService.createVideoTask(
                effectiveSceneId, cleanPrompt, cleanModel, cleanResolution, cleanSize, cleanAspectRatio,
                durationInt, cleanNegative, null, referenceImages, cleanSource);
        } catch (Exception e) {
            updateRecord(recordId, "failed", null, null, friendlyMessage(e), startMs);
            throw e;
        }
        if (taskId != null && !taskId.isBlank() && recordId != null) {
            attachTaskId(recordId, taskId);
            log.info("Agent 生成编排：视频资产已落库 assetId={}, taskId={}", recordId, taskId);
        }
        return taskId;
    }
    /**
     * 创建视频任务（新版：支持带 role 的参考图列表）。
     * <p>
     * 每个参考图可指定用途（首帧/尾帧/参考图），由适配器负责转换为各模型特定格式。
     * <p>
     * 向后兼容：如果调用方没有 role 信息，可使用 {@link ReferenceImage#ofReference(String)} 包装。
     */
    @Override
    public String createVideoTaskWithRole(AgentConversation conversation, String sceneId,
                                  String prompt, String model, String resolution, String size,
                                  String aspectRatio, String duration, String negativePrompt,
                                  List<ReferenceImage> referenceImages, String generatedImageUrl) {
        return createVideoTaskWithRole(conversation, sceneId, prompt, model, resolution, size,
                aspectRatio, duration, negativePrompt, referenceImages, generatedImageUrl, List.of());
    }

    @Override
    public String createVideoTaskWithRole(AgentConversation conversation, String sceneId,
                                  String prompt, String model, String resolution, String size,
                                  String aspectRatio, String duration, String negativePrompt,
                                  List<ReferenceImage> referenceImages, String generatedImageUrl,
                                  List<String> referenceVideos) {
        return createVideoTaskWithRole(conversation, sceneId, prompt, model, resolution, size,
                aspectRatio, duration, negativePrompt, referenceImages, generatedImageUrl,
                referenceVideos, true);
    }

    @Override
    public String createVideoTaskWithRole(AgentConversation conversation, String sceneId,
                                  String prompt, String model, String resolution, String size,
                                  String aspectRatio, String duration, String negativePrompt,
                                  List<ReferenceImage> referenceImages, String generatedImageUrl,
                                  List<String> referenceVideos, boolean recordGeneration) {
        Integer durationInt = parseDuration(duration);
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        String cleanPrompt = sanitize(prompt);
        String cleanModel = sanitize(model);
        String cleanResolution = sanitize(resolution);
        String cleanSize = sanitize(size);
        String cleanAspectRatio = sanitize(aspectRatio);
        String cleanNegative = sanitize(negativePrompt);
        String cleanSource = sanitize(generatedImageUrl);

        // 从 ReferenceImage 列表提取 URL 用于记录（role 信息在 videoService 中处理）
        List<String> refUrls = referenceImages != null
                ? referenceImages.stream().map(ReferenceImage::url).toList()
                : List.of();
        String genType = classifyVideoGenType(refUrls, cleanSource);

        // 分镜队列自行写 storyboard_video 时传 false，避免同一 taskId 产生两条生成记录。
        String recordId = recordGeneration
                ? newRecord(conversation, "video", genType, cleanPrompt, cleanModel, effectiveSceneId,
                        buildVideoParams(cleanResolution, cleanSize, cleanAspectRatio, duration, cleanNegative),
                        buildReferenceUrls(refUrls, cleanSource), "queued")
                : null;
        long startMs = System.currentTimeMillis();
        String taskId;
        try {
            taskId = videoService.createVideoTask(
                    effectiveSceneId, cleanPrompt, cleanModel, cleanResolution, cleanSize, cleanAspectRatio,
                    durationInt, cleanNegative, null, referenceImages, cleanSource,
                    referenceVideos, null, true);
        } catch (Exception e) {
            updateRecord(recordId, "failed", null, null, friendlyMessage(e), startMs);
            throw e;
        }
        if (taskId != null && !taskId.isBlank() && recordId != null) {
            attachTaskId(recordId, taskId);
            log.info("Agent 生成编排：视频资产已落库 assetId={}, taskId={}", recordId, taskId);
        }
        return taskId;
    }


    /** 解析视频 duration 字符串，非法值返回 null（走 service 默认） */
    private Integer parseDuration(String duration) {
        if (duration == null || duration.isBlank()) return null;
        try {
            return Integer.parseInt(duration);
        } catch (NumberFormatException e) {
            log.warn("Agent 生成编排：视频 duration 值非法({}), 使用 service 默认值", duration);
            return null;
        }
    }

    /** 生成类型分类：图片。有源图（generatedImageUrl）或参考图（referenceImages）或显式 edit → 图生图；否则文生图。 */
    static String classifyImageGenType(String mode, List<String> referenceImages, String generatedImageUrl) {
        return GenerationRecordTypes.classifyImage(mode, referenceImages, generatedImageUrl, 1);
    }

    /** 多图输入且要求多图输出时单独记录为 multi_image_to_multi_image。 */
    static String classifyImageGenType(String mode, List<String> referenceImages,
                                       String generatedImageUrl, Integer n) {
        return GenerationRecordTypes.classifyImage(mode, referenceImages, generatedImageUrl, n);
    }

    /** 生成类型分类：视频。有参考素材或首帧源 → 参考生视频；否则文生视频。首帧/首尾帧未实现，仅预留枚举。 */
    static String classifyVideoGenType(List<String> referenceImages, String generatedImageUrl) {
        return GenerationRecordTypes.classifyVideo(referenceImages, generatedImageUrl);
    }

    /** 新建一条生成记录（每次生成尝试一条；sceneId 非空也落库以便用户级记录列表可见）。落库失败不影响生成，返回 null。 */
    private String newRecord(AgentConversation conversation, String type, String genType,
                                 String prompt, String model, String sceneId,
                                 Map<String, Object> params, List<GenerationReference> referenceUrls, String status) {
        try {
            return generationRecordWriter.create(new GenerationRecord(
                    conversation.getUserId(), conversation.getProjectId(), conversation.getId(), sceneId,
                    type, genType, prompt, model, null, null, List.of(), referenceUrls,
                    params, status, null, null, OffsetDateTime.now()));
        } catch (Exception e) {
            log.error("Agent 生成编排：生成记录落库失败(不影响生成), conversationId={}, 原因: {}", conversation.getId(), e.getMessage());
            return null;
        }
    }

    /** 更新生成记录终态/进度；记录为 null（落库失败）时安全跳过。 */
    private void updateRecord(String recordId, String status, String url, List<String> resultUrls, String error, long startMs) {
        if (recordId == null) return;
        try {
            generationRecordWriter.update(recordId, new GenerationRecordUpdate(
                    status, url, resultUrls, error, System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.error("Agent 生成编排：生成记录状态更新失败, assetId={}, 原因: {}", recordId, e.getMessage());
        }
    }

    private void attachTaskId(String recordId, String taskId) {
        try {
            generationRecordWriter.attachTaskId(recordId, taskId);
        } catch (Exception e) {
            log.error("Agent 生成编排：记录关联 taskId 失败(不影响生成), assetId={}, 原因: {}",
                    recordId, e.getMessage());
        }
    }

    /** 生成参数 JSON（图片）：size / quality / n / mode。null 值不写入。 */
    private Map<String, Object> buildImageParams(String size, String quality, int n, String mode) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (size != null && !size.isBlank()) params.put("size", size);
        if (quality != null && !quality.isBlank()) params.put("quality", quality);
        params.put("n", n);
        if (mode != null && !mode.isBlank()) params.put("mode", mode);
        return params;
    }

    /** 生成参数 JSON（视频）：resolution / size / aspectRatio / duration / negativePrompt。null 值不写入。 */
    private Map<String, Object> buildVideoParams(String resolution, String size, String aspectRatio, String duration, String negativePrompt) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (resolution != null && !resolution.isBlank()) params.put("resolution", resolution);
        if (size != null && !size.isBlank()) params.put("size", size);
        if (aspectRatio != null && !aspectRatio.isBlank()) params.put("aspectRatio", aspectRatio);
        if (duration != null && !duration.isBlank()) params.put("duration", duration);
        if (negativePrompt != null && !negativePrompt.isBlank()) params.put("negativePrompt", negativePrompt);
        return params;
    }

    /** 参考素材 JSON（[{type,url,role?}]）：referenceImages → image；generatedImageUrl → image+source。超大 base64 截断为占位。 */
    private List<GenerationReference> buildReferenceUrls(List<String> referenceImages, String generatedImageUrl) {
        List<GenerationReference> refs = new ArrayList<>();
        if (referenceImages != null) {
            for (String ref : referenceImages) {
                if (ref == null || ref.isBlank()) continue;
                refs.add(new GenerationReference("image", safeRefUrl(ref), "REFERENCE"));
            }
        }
        if (generatedImageUrl != null && !generatedImageUrl.isBlank()) {
            refs.add(new GenerationReference("image", safeRefUrl(generatedImageUrl), "source"));
        }
        return refs;
    }

    /** 参考 URL 安全截断：超过上限存 null 占位，前端显示「参考素材过大」 */
    private String safeRefUrl(String url) {
        if (url.length() > MAX_REF_URL_LENGTH) return null;
        return url;
    }

    /** 错误文案截断，避免超长异常撑爆详情 */
    private String friendlyMessage(Exception e) {
        String msg = e == null ? "" : e.getMessage();
        if (msg == null || msg.isBlank()) msg = "生成失败";
        return msg.length() > 600 ? msg.substring(0, 600) : msg;
    }

    /**
     * 轮询视频任务。
     * 资产更新由上游 VideoGenerationService 在终态时完成，此处只转发轮询结果。
     */
    public Map<String, String> pollVideoTask(String taskId) {
        return videoService.pollVideoTask(taskId);
    }

    @Override
    public Map<String, String> cancelVideoTask(String taskId) {
        return videoService.cancelVideoTask(taskId);
    }

    @Override
    public void recordStoryboardGeneration(AgentConversation conversation, String sceneId, String type, String genType,
                                           String prompt, String model, String taskId, String url,
                                           List<String> resultUrls, String status, String error,
                                           Long durationMs, Map<String, Object> params) {
        try {
            if (conversation == null) return;
            generationRecordWriter.create(new GenerationRecord(
                    conversation.getUserId(), conversation.getProjectId(), conversation.getId(), sceneId,
                    type, genType, prompt, model, taskId, url,
                    resultUrls, List.of(), params, status, error, durationMs, OffsetDateTime.now()));
        } catch (Exception e) {
            log.error("生成记录落库失败(不影响队列), sceneId={}, genType={}, 原因: {}", sceneId, genType, e.getMessage());
        }
    }
}





