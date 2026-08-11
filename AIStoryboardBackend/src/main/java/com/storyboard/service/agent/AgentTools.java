package com.storyboard.service.agent;

import com.storyboard.entity.AgentConversation;
import com.storyboard.mapper.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Agent 编排工具面（@Tool 注册给 LLM 调用）。
 *
 * 方法体复用 {@link AgentGenerationService}（写分镜 / 生图 / 生视频），
 * 编排确认（HITL checkpoint used）后才由 LLM 调用；工具方法内部 try-catch，
 * 错误转为稳定错误 Map 返回（Spring AI 2.0 工具异常不向上抛，LLM 会自行消化——
 * 需要明确错误信息时必须在工具内返回错误对象，spike 实测）。
 *
 * <p>工具组件不接口化（参照 LLM 网关 UpstreamClient 约定），独立成 Maven 模块时
 * 依赖面 = AgentGenerationService + AgentConversationMapper（agent 自有依赖）。
 */
@Component
@RequiredArgsConstructor
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final AgentGenerationService generationService;
    private final AgentConversationMapper conversationMapper;

    /** 通用错误响应（稳定结构，LLM 可读） */
    private static Map<String, Object> error(String code, String message) {
        return Map.of("ok", false, "code", code, "message", message);
    }

    /**
     * 批量写入分镜（剧本确认后调用）。
     *
     * @param projectId 项目 ID
     * @param scenes    分镜列表（8 字段，见 {@link AgentSceneItem}）
     * @return {ok, count} 或 {ok:false, code, message}
     */
    @Tool(description = "把确认后的分镜方案批量写入项目（每个元素含 sceneNumber/scriptContent/imagePrompt/videoPrompt/negativePrompt/cameraMovement/shotType/soundDesign）")
    public Map<String, Object> writeScenes(
            @ToolParam(description = "项目 ID") String projectId,
            @ToolParam(description = "分镜列表") List<AgentSceneItem> scenes) {
        try {
            int count = generationService.writeScript(projectId, scenes);
            log.info("AgentTools.writeScenes: projectId={} count={}", projectId, count);
            return Map.of("ok", true, "count", count);
        } catch (Exception e) {
            log.error("AgentTools.writeScenes 失败: {}", e.getMessage(), e);
            return error("50001", "分镜写入失败：" + e.getMessage());
        }
    }

    /**
     * 完善/生成图片（图生图或图改图，sceneId=null 落 agent_assets）。
     *
     * @param conversationId 会话 ID（校验归属用）
     * @param prompt        生成提示词
     * @param picUrl        源图（参考图 / 已生成图 URL，走 edits 图改图）
     * @return {ok, imageUrl, assetId} 或 {ok:false, code, message}
     */
    @Tool(description = "生成或完善一张图片（提供 picUrl 时基于该图改图），结果落库到会话产出素材")
    public Map<String, Object> refineImage(
            @ToolParam(description = "会话 ID") String conversationId,
            @ToolParam(description = "图片生成提示词") String prompt,
            @ToolParam(description = "源图 URL（可空，空则纯文生图）") String picUrl) {
        try {
            AgentConversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) {
                return error("40401", "会话不存在");
            }
            String mode = (picUrl != null && !picUrl.isBlank()) ? "edit" : null;
            Map<String, String> result = generationService.generateImage(
                    conv, null, prompt, null, null, mode, null, picUrl);
            String url = result.get("imageUrl");
            if (url == null || url.isBlank()) {
                return error("50202", "图片生成失败，请稍后重试");
            }
            return Map.of("ok", true, "imageUrl", url,
                    "assetId", result.getOrDefault("assetId", ""));
        } catch (Exception e) {
            log.error("AgentTools.refineImage 失败: {}", e.getMessage(), e);
            return error("50202", "图片生成失败：" + e.getMessage());
        }
    }

    /**
     * 生成视频（文生视频 / 图生视频），同步创建任务并轮询至终态。
     *
     * @param conversationId 会话 ID
     * @param prompt        视频提示词
     * @param duration      时长秒（可空用默认）
     * @param aspectRatio   画幅（可空；图生视频恒 adaptive）
     * @param picUrl        参考图 URL（可空）
     * @return {ok, videoUrl} 或 {ok:false, code, message}
     */
    @Tool(description = "生成一段视频（提供 picUrl 时基于该图生成），同步等待生成完成，结果落库到会话产出素材")
    public Map<String, Object> generateVideo(
            @ToolParam(description = "会话 ID") String conversationId,
            @ToolParam(description = "视频提示词") String prompt,
            @ToolParam(description = "时长秒（可空）") String duration,
            @ToolParam(description = "画幅如 16:9 或 9:16（可空）") String aspectRatio,
            @ToolParam(description = "参考图 URL（可空）") String picUrl) {
        try {
            AgentConversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) {
                return error("40401", "会话不存在");
            }
            String taskId = generationService.createVideoTask(
                    conv, null, prompt, null, null, null, aspectRatio,
                    duration, null, null, picUrl);
            if (taskId == null || taskId.isBlank()) {
                return error("50202", "视频任务创建失败，请稍后重试");
            }
            // 同步轮询直至终态（虚拟线程阻塞安全）
            Map<String, String> poll = generationService.pollVideoTask(taskId);
            String status = poll.get("status");
            if ("completed".equals(status)) {
                return Map.of("ok", true, "videoUrl", poll.getOrDefault("videoUrl", ""));
            }
            return error("50202", poll.getOrDefault("error", "视频生成失败：" + status));
        } catch (Exception e) {
            log.error("AgentTools.generateVideo 失败: {}", e.getMessage(), e);
            return error("50202", "视频生成失败：" + e.getMessage());
        }
    }
}
