package com.storyboard.service.agent.impl;

import com.storyboard.service.agent.AgentGenerationService;
import com.storyboard.controller.DifyAgentController;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
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

import java.util.List;
import java.util.Map;

/**
 * Agent 生成编排服务（智能体生成后端化重构）。
 *
 * 在 HITL 表单提交事件后由后端直接执行生成（写分镜 / 生图 / 生视频），
 * 替代原 Dify 工作流内 HTTP 节点回调（/api/ai/dify/**）。
 * 逻辑从 DifyAgentController 抽取复用；归属校验一律以 conversation.getProjectId() 为准。
 */
@Service
@RequiredArgsConstructor
public class AgentGenerationServiceImpl implements AgentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerationServiceImpl.class);

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;


    /** 批量写分镜（原 DifyAgentController.generateScript 逻辑，宽松 items 直接透传）；批量插入需保证原子性 */
    @Transactional
    public int writeScript(String projectId, List<DifyGenerateScriptRequest.SceneItem> scenes) {
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
     * 生图并落库。
     * sceneId 非空 → 更新真实分镜（返回 imageUrl）；为空 → 落 agent_assets（返回 imageUrl + assetId）。
     * mode="edit" 走图改图（generatedImageUrl 为源图）。
     */
    public Map<String, String> generateImage(AgentConversation conversation, String sceneId,
                                             String prompt, String model, String size, String mode,
                                             List<String> referenceImages, String generatedImageUrl) {
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            DifyAgentController.sanitize(prompt), DifyAgentController.sanitize(model),
            DifyAgentController.sanitize(size), null, null,
            referenceImages, mode, DifyAgentController.sanitize(generatedImageUrl));
        if (effectiveSceneId == null) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setType("image");
            asset.setUrl(imageUrl);
            asset.setPrompt(DifyAgentController.sanitize(prompt));
            asset.setModel(DifyAgentController.sanitize(model));
            asset.setStatus("completed");
            try {
                agentAssetMapper.insert(asset);
                log.info("Agent 生成编排：图片资产已落库 assetId={}, conversationId={}", asset.getId(), conversation.getId());
                return Map.of("imageUrl", imageUrl, "assetId", asset.getId());
            } catch (Exception e) {
                // 图片已生成（已计费），落库失败不能抛异常导致 URL 丢失、重试重复计费
                log.error("Agent 生成编排：图片资产落库失败(不影响已生成的图片), conversationId={}, 原因: {}", conversation.getId(), e.getMessage());
                return Map.of("imageUrl", imageUrl);
            }
        }
        return Map.of("imageUrl", imageUrl);
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
            DifyAgentController.sanitize(prompt), DifyAgentController.sanitize(model),
            DifyAgentController.sanitize(resolution), DifyAgentController.sanitize(size),
            DifyAgentController.sanitize(aspectRatio),
            durationInt, DifyAgentController.sanitize(negativePrompt), null,
            referenceImages, DifyAgentController.sanitize(generatedImageUrl));
        if (effectiveSceneId == null) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setType("video");
            asset.setPrompt(DifyAgentController.sanitize(prompt));
            asset.setModel(DifyAgentController.sanitize(model));
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
