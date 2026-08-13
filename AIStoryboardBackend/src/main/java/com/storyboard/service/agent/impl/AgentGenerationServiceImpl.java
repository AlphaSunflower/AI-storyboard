package com.storyboard.service.agent.impl;

import com.storyboard.service.agent.AgentGenerationService;
import com.storyboard.service.agent.AgentSceneItem;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 生成编排服务（智能体生成后端化重构）。
 *
 * 在 HITL 表单提交事件后由后端直接执行生成（写分镜 / 生图 / 生视频），
 * 替代原 Dify 工作流内 HTTP 节点回调（/api/ai/dify/**）。
 * 归属校验一律以 conversation.getProjectId() 为准。
 */
@Service
@RequiredArgsConstructor
public class AgentGenerationServiceImpl implements AgentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerationServiceImpl.class);

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;


    /** 清洗外部传入参数：未解析的 Dify 变量引用（含 structured_output.）视为无效值返回 null（原 DifyAgentController.sanitize 迁移） */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.contains("structured_output.")) return null;
        return value;
    }

    /** 覆盖写分镜：同一事务内先清空现有再批量写入（replaceScript 事务包裹，writeScript 内部调用共享事务） */
    @Transactional
    public int replaceScript(String projectId, List<AgentSceneItem> scenes) {
        sceneMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Scene>()
                .eq(Scene::getProjectId, projectId));
        return writeScript(projectId, scenes);
    }

    /** 批量写分镜（宽松 items 直接透传）；批量插入需保证原子性 */
    @Transactional
    public int writeScript(String projectId, List<AgentSceneItem> scenes) {
        if (scenes == null || scenes.isEmpty()) return 0;
        int count = 0;
        for (var item : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(projectId);
            scene.setSceneNumber(item.sceneNumber());
            scene.setScriptContent(item.scriptContent());
            scene.setImagePrompt(item.imagePrompt());
            scene.setVideoPrompt(item.videoPrompt());
            scene.setNegativePrompt(item.negativePrompt());
            scene.setCameraMovement(item.cameraMovement());
            scene.setShotType(item.shotType());
            scene.setSoundDesign(item.soundDesign());
            sceneMapper.insert(scene);
            count++;
        }
        log.info("Agent 生成编排：写入 {} 个分镜到项目 {}", count, projectId);
        return count;
    }

    /**
     * 生图并落库（支持 quality/n 多张）。
     * sceneId 非空 → 更新真实分镜（返回 imageUrls）；为空 → 每张图落一条 agent_assets（返回 imageUrls + assetIds）。
     * mode="edit" 走图改图（generatedImageUrl 为源图，恒单张）。
     */
    public Map<String, Object> generateImage(AgentConversation conversation, String sceneId,
                                             String prompt, String model, String size, String quality, Integer n,
                                             String mode, List<String> referenceImages, String generatedImageUrl) {
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        List<String> urls = imageService.generateImages(
            effectiveSceneId,
            sanitize(prompt), sanitize(model),
            sanitize(size), sanitize(quality), null,
            referenceImages, mode, sanitize(generatedImageUrl),
            (n != null && n > 0) ? n : 1);
        Map<String, Object> out = new HashMap<>();
        if (effectiveSceneId == null) {
            List<String> assetIds = new ArrayList<>();
            for (String url : urls) {
                AgentAsset asset = new AgentAsset();
                asset.setConversationId(conversation.getId());
                asset.setType("image");
                asset.setUrl(url);
                asset.setPrompt(sanitize(prompt));
                asset.setModel(sanitize(model));
                asset.setStatus("completed");
                try {
                    agentAssetMapper.insert(asset);
                    assetIds.add(asset.getId());
                } catch (Exception e) {
                    // 图片已生成（已计费），落库失败不能抛异常导致 URL 丢失、重试重复计费
                    log.error("Agent 生成编排：图片资产落库失败(不影响已生成的图片), conversationId={}, 原因: {}", conversation.getId(), e.getMessage());
                }
            }
            out.put("imageUrls", urls);
            out.put("assetIds", assetIds);
            return out;
        }
        out.put("imageUrls", urls);
        out.put("assetIds", List.of());
        return out;
    }

    /**
     * 创建视频任务并落库（queued）。
     * duration 为字符串（快照 plan 中可能为 String），解析失败用默认值。
     */
    public String createVideoTask(AgentConversation conversation, String sceneId,
                                  String prompt, String model, String resolution, String size,
                                  String aspectRatio, String duration, String negativePrompt,
                                  List<String> referenceImages, String generatedImageUrl) {
        Integer durationInt = null;
        if (duration != null && !duration.isBlank()) {
            try {
                durationInt = Integer.parseInt(duration);
            } catch (NumberFormatException e) {
                log.warn("Agent 生成编排：视频 duration 值非法({}), 使用 service 默认值", duration);
            }
        }
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        String taskId = videoService.createVideoTask(
            effectiveSceneId,
            sanitize(prompt), sanitize(model),
            sanitize(resolution), sanitize(size),
            sanitize(aspectRatio),
            durationInt, sanitize(negativePrompt), null,
            referenceImages, sanitize(generatedImageUrl));
        if (effectiveSceneId == null) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setType("video");
            asset.setPrompt(sanitize(prompt));
            asset.setModel(sanitize(model));
            asset.setStatus("queued");
            asset.setTaskId(taskId);
            try {
                agentAssetMapper.insert(asset);
                log.info("Agent 生成编排：视频资产已落库 assetId={}, taskId={}", asset.getId(), taskId);
            } catch (Exception e) {
                // 资产落库失败不影响已创建的 Laozhang 任务（避免白扣费）
                log.error("Agent 生成编排：视频资产落库失败(不影响任务), taskId={}, 原因: {}", taskId, e.getMessage());
            }
        }
        return taskId;
    }

    /**
     * 轮询视频任务。
     * 资产更新由上游 VideoGenerationService 在终态时完成，此处只转发轮询结果。
     */
    public Map<String, String> pollVideoTask(String taskId) {
        return videoService.pollVideoTask(taskId);
    }
}
