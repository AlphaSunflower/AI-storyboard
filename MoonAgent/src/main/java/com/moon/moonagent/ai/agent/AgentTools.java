package com.moon.moonagent.ai.agent;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.ai.agent.human.HumanInputRequest;
import com.moon.moonagent.mapper.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 编排工具面（@Tool 注册给 LLM 调用）。
 *
 * <p>错误处理策略：工具错误直接抛 IllegalArgumentException，
 * Spring AI ToolCalling 框架将异常信息注入对话上下文，LLM 对异常的响应比读 Map 更敏感。
 */
@Component
@RequiredArgsConstructor
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final AgentGenerationService generationService;
    private final AgentConversationMapper conversationMapper;

    @Tool(description = "把确认后的分镜方案批量写入项目（每个元素含 sceneNumber/scriptContent/imagePrompt/videoPrompt/negativePrompt/cameraMovement/shotType/soundDesign）")
    @McpTool(name = "writeScenes", description = "把确认后的分镜方案批量写入项目")
    public Map<String, Object> writeScenes(
            @ToolParam(description = "项目 ID") @McpToolParam(description = "项目 ID") String projectId,
            @ToolParam(description = "分镜列表") @McpToolParam(description = "分镜列表") List<AgentSceneItem> scenes) {
        try {
            int count = generationService.writeScript(projectId, scenes);
            log.info("AgentTools.writeScenes: projectId={} count={}", projectId, count);
            return Map.of("ok", true, "count", count);
        } catch (Exception e) {
            log.error("AgentTools.writeScenes 失败: {}", e.getMessage(), e);
            throw new IllegalArgumentException("分镜写入失败：" + e.getMessage());
        }
    }

    @Tool(description = "覆盖写分镜：清空项目现有分镜后批量写入新分镜（删除与写入同一事务）")
    @McpTool(name = "replaceScenes", description = "覆盖写分镜：清空项目现有分镜后批量写入新分镜")
    public Map<String, Object> replaceScenes(
            @ToolParam(description = "项目 ID") @McpToolParam(description = "项目 ID") String projectId,
            @ToolParam(description = "分镜列表") @McpToolParam(description = "分镜列表") List<AgentSceneItem> scenes) {
        try {
            int count = generationService.replaceScript(projectId, scenes);
            log.info("AgentTools.replaceScenes: projectId={} count={}", projectId, count);
            return Map.of("ok", true, "count", count);
        } catch (Exception e) {
            log.error("AgentTools.replaceScenes 失败: {}", e.getMessage(), e);
            throw new IllegalArgumentException("分镜覆盖写入失败：" + e.getMessage());
        }
    }

    /**
     * 只替换指定 scene_number 的分镜（部分替换）：更新这些编号的现有分镜（保留 id 与素材关联），
     * 不存在则新插入；不在 scenes 里出现的其他分镜完全不动。用于「只针对分镜N优化」——
     * 绝不清空/覆盖未指定的分镜（replaceScenes 会清空全部）。
     */
    @Tool(description = "部分替换分镜：只更新/插入指定 scene_number 的分镜，其他分镜不动（保留素材关联）")
    @McpTool(name = "replaceSpecificScenes", description = "部分替换分镜：只更新指定 scene_number 的分镜，保留其他分镜及其素材关联")
    public Map<String, Object> replaceSpecificScenes(
            @ToolParam(description = "项目 ID") @McpToolParam(description = "项目 ID") String projectId,
            @ToolParam(description = "分镜列表（只包含想更新的分镜）") @McpToolParam(description = "分镜列表") List<AgentSceneItem> scenes) {
        try {
            int count = generationService.updateScenesByNumber(projectId, scenes);
            log.info("AgentTools.replaceSpecificScenes: projectId={} count={}", projectId, count);
            return Map.of("ok", true, "count", count);
        } catch (Exception e) {
            log.error("AgentTools.replaceSpecificScenes 失败: {}", e.getMessage(), e);
            throw new IllegalArgumentException("分镜部分替换失败：" + e.getMessage());
        }
    }

    @Tool(description = "生成或完善图片（提供 picUrls 时基于这些图改图，多张参考图均作源图），支持多张（n>1），结果落库到会话产出素材")
    @McpTool(name = "refineImage", description = "生成或完善图片（图生图或文生图）")
    public Map<String, Object> refineImage(
            @ToolParam(description = "会话 ID") @McpToolParam(description = "会话 ID") String conversationId,
            @ToolParam(description = "图片生成提示词") @McpToolParam(description = "图片生成提示词") String prompt,
            @ToolParam(description = "源图 URL 列表（可空，空则纯文生图；改图取首张为基准）") @McpToolParam(description = "源图 URL 列表（可空）") List<String> picUrls,
            @ToolParam(description = "模型名（可空，默认配置）") @McpToolParam(description = "模型名（可空）") String model,
            @ToolParam(description = "图片尺寸如 1024x1024（可空，默认配置）") @McpToolParam(description = "图片尺寸（可空）") String size,
            @ToolParam(description = "图片质量（可空，默认配置）") @McpToolParam(description = "图片质量（可空）") String quality,
            @ToolParam(description = "生成数量（可空，默认 1）") @McpToolParam(description = "生成数量（可空）") String n) {
        try {
            AgentConversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) {
                throw new IllegalArgumentException("会话不存在：" + conversationId);
            }
            String mode = (picUrls != null && !picUrls.isEmpty()) ? "edit" : null;
            Integer nInt = null;
            if (n != null && !n.isBlank()) {
                try { nInt = Integer.parseInt(n.trim()); } catch (NumberFormatException ignored) { }
            }
            Map<String, Object> result = generationService.generateImage(
                    conv, null, prompt, model, size, quality, nInt, mode, null,
                    (picUrls == null || picUrls.isEmpty()) ? null : picUrls.getFirst());
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) result.getOrDefault("imageUrls", List.of());
            if (urls.isEmpty()) {
                throw new IllegalArgumentException("图片生成失败，请稍后重试");
            }
            return Map.of("ok", true, "imageUrls", urls,
                    "assetIds", result.getOrDefault("assetIds", List.of()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("AgentTools.refineImage 失败: {}", e.getMessage(), e);
            throw new IllegalArgumentException("图片生成失败：" + e.getMessage());
        }
    }

    @Tool(description = "生成一段视频（提供 picUrls 时基于这些图生成，多张参考图均投喂），同步等待生成完成，结果落库到会话产出素材")
    @McpTool(name = "generateVideo", description = "生成一段视频（文生视频或图生视频）")
    public Map<String, Object> generateVideo(
            @ToolParam(description = "会话 ID") @McpToolParam(description = "会话 ID") String conversationId,
            @ToolParam(description = "视频提示词") @McpToolParam(description = "视频提示词") String prompt,
            @ToolParam(description = "时长秒（可空）") @McpToolParam(description = "时长秒（可空）") String duration,
            @ToolParam(description = "画幅如 16:9 或 9:16（可空）") @McpToolParam(description = "画幅（可空）") String aspectRatio,
            @ToolParam(description = "参考图 URL 列表（可空，多张投喂）") @McpToolParam(description = "参考图 URL 列表（可空）") List<String> picUrls,
            @ToolParam(description = "模型名（可空，默认 MiniMax-H3）") @McpToolParam(description = "模型名（可空）") String model,
            @ToolParam(description = "分辨率（可空，默认配置档）") @McpToolParam(description = "分辨率（可空）") String resolution) {
        try {
            AgentConversation conv = conversationMapper.selectById(conversationId);
            if (conv == null) {
                throw new IllegalArgumentException("会话不存在：" + conversationId);
            }
            String taskId = generationService.createVideoTask(
                    conv, null, prompt, model, resolution, null, aspectRatio,
                    duration, null,
                    (picUrls == null || picUrls.isEmpty()) ? List.of() : picUrls,
                    null);
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("视频任务创建失败，请稍后重试");
            }
            Map<String, String> poll = generationService.pollVideoTask(taskId);
            String status = poll.get("status");
            if ("completed".equals(status)) {
                return Map.of("ok", true, "videoUrl", poll.getOrDefault("videoUrl", ""));
            }
            throw new IllegalArgumentException(poll.getOrDefault("error", "视频生成失败：" + status));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("AgentTools.generateVideo 失败: {}", e.getMessage(), e);
            throw new IllegalArgumentException("视频生成失败：" + e.getMessage());
        }
    }

    @Tool(
            name = "moon_request_human_input",
            description = "请求用户点击选择。用于目标不明确、需要候选人确认或存在危险操作时。"
                    + "options 每项必须包含 title 和 value；value 是系统内部参数，严禁在回复中向用户展示 ID。",
            returnDirect = true)
    public Map<String, Object> requestHumanInput(
            @ToolParam(description = "给用户看的简短问题") String prompt,
            @ToolParam(description = "可点击选项：{title:显示名称, value:内部参数, id:可选来源ID}") List<Map<String, Object>> options,
            @ToolParam(description = "是否允许自定义输入，默认 false") Boolean allowCustom) {
        HumanInputRequest request = HumanInputRequest.fromToolCall(prompt, options, allowCustom);
        log.info("AgentTools.requestHumanInput: options={} allowCustom={}", request.options().size(), request.allowCustom());
        return request.toToolResult();
    }

    /**
     * 测试技能：验证「仅有 SKILL.md + @Tool、无 IntentHandler」的通用执行链路。
     * returnDirect=true：模型调用后结果直接作为回复，不回传给模型再改写，演示确定。
     */
    @Tool(description = "测试技能：返回“测试无效，略略略。用户第一句话：”并拼接上下文第一句话（returnDirect 直接作为回复）",
            returnDirect = true)
    public String testEcho(
            @ToolParam(description = "上下文文本（取其第一句话拼接返回）") String context) {
        return "测试无效，略略略。用户第一句话：" + firstSentence(context);
    }

    /** 取文本第一个句子（中文/英文句末标点切分）；无标点则整段返回。 */
    private static String firstSentence(String text) {
        if (text == null || text.isBlank()) return "";
        String t = text.trim();
        Matcher m = Pattern.compile(".*?[。！？!?；;]").matcher(t);
        return m.find() ? m.group().trim() : t;
    }
}
