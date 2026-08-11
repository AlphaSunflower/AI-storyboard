package com.storyboard.service.agent;

import com.storyboard.entity.AgentConversation;

import java.util.List;
import java.util.Map;

/**
 * Agent 生成编排服务（智能体生成后端化重构）。
 *
 * 在 HITL 表单提交事件后由后端直接执行生成（写分镜 / 生图 / 生视频），
 * 
 * 归属校验一律以 conversation.getProjectId() 为准。
 *
 * <p>实现：{@link com.storyboard.service.agent.impl.AgentGenerationServiceImpl}。
 */
public interface AgentGenerationService {

    /** 批量写分镜（宽松 items 直接透传）；批量插入需保证原子性 */
    int writeScript(String projectId, List<AgentSceneItem> scenes);

    /**
     * 生图并落库。
     * sceneId 非空 → 更新真实分镜（返回 imageUrl）；为空 → 落 agent_assets（返回 imageUrl + assetId）。
     * mode="edit" 走图改图（generatedImageUrl 为源图）。
     */
    Map<String, String> generateImage(AgentConversation conversation, String sceneId,
                                      String prompt, String model, String size, String mode,
                                      List<String> referenceImages, String generatedImageUrl);

    /**
     * 创建视频任务并落库（queued）。
     * duration 为字符串（快照 plan 中可能为 String），解析失败用默认值。
     */
    String createVideoTask(AgentConversation conversation, String sceneId,
                           String prompt, String model, String resolution, String size,
                           String aspectRatio, String duration, String negativePrompt,
                           List<String> referenceImages, String generatedImageUrl);

    /**
     * 轮询视频任务。
     * 资产更新由上游 VideoGenerationService 在终态时完成，此处只转发轮询结果。
     */
    Map<String, String> pollVideoTask(String taskId);
}
