package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.entity.AgentConversation;

import java.util.List;
import java.util.Map;

/**
 * Agent 生成编排服务（智能体生成后端化重构）。
 *
 * 在 HITL 表单提交事件后由后端直接执行生成（写分镜 / 生图 / 生视频），
 * 
 * 归属校验一律以 conversation.getProjectId() 为准。
 *
 * <p>实现：{@link com.moon.moonagent.ai.agent.impl.AgentGenerationServiceImpl}。
 */
public interface AgentGenerationService {

    /** 批量写分镜（宽松 items 直接透传）；批量插入需保证原子性 */
    int writeScript(String projectId, List<AgentSceneItem> scenes);

    /** 覆盖写分镜：同一事务内先清空项目现有分镜再批量写入（避免中途失败丢失现有分镜） */
    int replaceScript(String projectId, List<AgentSceneItem> scenes);

    /**
     * 只替换指定 scene_number 的分镜（部分替换）：更新这些编号的现有分镜（保留 id 与素材关联），
     * 不存在则新插入；不在 {@code scenes} 里出现的其他分镜完全不动。
     * 用于「只针对分镜N优化」——绝不清空/覆盖未指定的分镜。
     *
     * @return 被更新/插入的分镜数
     */
    int updateScenesByNumber(String projectId, List<AgentSceneItem> scenes);

    /**
     * 生图并落库（支持 quality/n 多张）。
     * sceneId 非空 → 更新真实分镜（返回 imageUrls）；为空 → 每张图落一条 agent_assets（返回 imageUrls + assetIds）。
     * mode="edit" 走图改图（generatedImageUrl 为源图，恒单张）。
     */
    Map<String, Object> generateImage(AgentConversation conversation, String sceneId,
                                      String prompt, String model, String size, String quality, Integer n,
                                      String mode, List<String> referenceImages, String generatedImageUrl);

    /**
     * 创建视频任务并落库（queued）。
     * duration 为字符串（快照 plan 中可能为 String），解析失败用默认值。
     */
    String createVideoTask(AgentConversation conversation, String sceneId,
                           String prompt, String model, String resolution, String size,
                           String aspectRatio, String duration, String negativePrompt,
                           List<String> referenceImages, String generatedImageUrl);

    /**
     * 创建视频任务（新版：支持带 role 的参考图列表）。
     * <p>
     * 每个参考图可指定用途（首帧/尾帧/参考图），由适配器负责转换为各模型特定格式。
     *
     * @param conversation      会话信息
     * @param sceneId           分镜 ID（可为空）
     * @param prompt            视频生成提示词
     * @param model             模型别名（可为空）
     * @param resolution        分辨率（可为空）
     * @param size              尺寸（可为空）
     * @param aspectRatio       宽高比（可为空）
     * @param duration          时长（可为空）
     * @param negativePrompt    负面提示词（可为空）
     * @param referenceImages   带 role 的参考图列表（可为空）
     * @param generatedImageUrl 已生成的图片 URL（首帧，可为空）
     * @return 视频任务 ID
     */
    String createVideoTaskWithRole(AgentConversation conversation, String sceneId,
                           String prompt, String model, String resolution, String size,
                           String aspectRatio, String duration, String negativePrompt,
                           List<ReferenceImage> referenceImages, String generatedImageUrl);

    /**
     * 轮询视频任务。
     * 资产更新由上游 VideoGenerationService 在终态时完成，此处只转发轮询结果。
     */
    Map<String, String> pollVideoTask(String taskId);
}



