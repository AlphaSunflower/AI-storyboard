package com.storyboard.service.agent.handler;

import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.service.ai.VideoPlanService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * video 视频链：方案设计（有参考图 → 视觉模型 buildVideoPlan；无图 → LLM callVideoPlan）
 * → HITL video_plan 卡片（开始生成视频/继续完善）→ resume 异步视频生成（task_accepted + 后台轮询）。
 */
@Component
@RequiredArgsConstructor
public class VideoIntentHandler implements IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoIntentHandler.class);

    private final AgentOrchestratorSupport support;
    private final VideoPlanService videoPlanService;

    @Override
    public String intentType() {
        return "intent-video";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of("generate_video", "refine");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        String source = request.getPicUrl();
        try {
            // 网关模型列表（卡片参数选择器用）+ LLM 选参选项文本（网关不可用时为空）
            List<Map<String, Object>> models = support.buildModels("video");
            String modelOptionsText = support.buildModelOptionsText("video");
            support.sendEvent(request, "workflow", Map.of("title", "正在设计视频方案…", "status", "node_started"));
            String message;
            int duration;
            Map<String, String> recommended = Map.of();
            Map<String, String> reasons = Map.of();
            if (source != null && !source.isBlank()) {
                // 视觉模型看图 + 诉求 → 视频方案（首帧语义）
                VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(source, request.getContent(), modelOptionsText);
                message = plan.message();
                duration = plan.duration();
                recommended = plan.params() == null ? Map.of() : plan.params();
                reasons = plan.reasons() == null ? Map.of() : plan.reasons();
            } else {
                // 无图：LLM 生成视频方案（prompt + 时长 + 推荐参数）
                AgentOrchestratorSupport.VideoPlanResult plan = support.callVideoPlan(request.getContent(), modelOptionsText);
                message = plan.message();
                duration = plan.duration();
                recommended = plan.params();
                reasons = plan.reasons();
            }
            // HITL 通用模板：方案 → checkpoint(generate_video) → video_plan 卡片（models/推荐参数随事件下发）
            String planText = "📹 视频方案：\n" + message + "\n（时长 " + duration + " 秒）";
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    planText, "generate_video",
                    List.of(Map.of("message", message, "duration", duration, "source", source == null ? "" : source)),
                    "video_plan",
                    List.of(Map.of("id", "generate_video", "title", "开始生成视频"),
                            Map.of("id", "refine", "title", "继续完善"),
                            Map.of("id", "custom", "title", "✍ 自定义输入")),
                    models, recommended, reasons));
        } catch (Exception e) {
            log.error("VideoIntentHandler.handle 失败: conversationId={}, error={}",
                    request.getConversation().getId(), e.getMessage(), e);
            support.sendEvent(request, "error", Map.of("code", "50202", "message", "视频方案生成失败，请稍后重试"));
            return "";
        }
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String cpAction = checkpoint.getAction();
        // 调整意见卡片提交（video-opinion）：合并自定义意见重新设计方案 → 新 video_plan 卡片
        if ("video-opinion".equals(cpAction)) {
            return resumeVideoWithOpinion(request, checkpoint);
        }
        // 方案卡片的「继续完善」：弹调整意见卡片（自定义输入，与分镜 scene-regenerate 同款）
        if ("refine".equals(request.getAction()) && "generate_video".equals(cpAction)) {
            return showVideoOpinionCard(request, checkpoint);
        }
        // 方案卡片的「✍ 自定义输入」：直接按自定义意见重新设计
        if ("custom".equals(request.getAction()) && "generate_video".equals(cpAction)) {
            return resumeVideoWithOpinion(request, checkpoint);
        }
        // 默认 generate_video：视频异步执行——方案确认后创建任务 → task_accepted → 本轮结束，前端轮询状态端点取结果。
        // 无同步 message 输出，返回空串（结果由后台轮询更新资产行，前端轮询渲染）
        String message = support.planField(checkpoint.getPlan(), "message");
        String duration = support.planField(checkpoint.getPlan(), "duration");
        String source = support.planField(checkpoint.getPlan(), "source");
        // 生成参数：用户提交 params 优先，未提交回退 checkpoint 推荐/原值（startVideoGenerationAsync 内做键级兜底）
        support.startVideoGenerationAsync(request, message, duration, null, source, request.getParams());
        return "";
    }

    /** 继续完善：调整意见卡片（✍ 自定义输入，用户描述修改意见） */
    private String showVideoOpinionCard(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String message = support.planField(checkpoint.getPlan(), "message");
        String duration = support.planField(checkpoint.getPlan(), "duration");
        String source = support.planField(checkpoint.getPlan(), "source");
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "📹 想怎么调整视频方案？\n\n请直接描述修改意见（如节奏、运镜、内容、时长、画幅），我会重新设计方案。",
                "video-opinion",
                List.of(Map.of("message", message, "duration", duration, "source", source == null ? "" : source)),
                "human_input",
                List.of(Map.of("id", "custom", "title", "✍ 自定义输入"))));
    }

    /** 合并自定义意见重新设计方案（有图 → 视觉模型看原图；无图 → LLM）→ 新 video_plan 卡片（先展示再确认） */
    private String resumeVideoWithOpinion(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String opinion = request.getCustomText();
        if (opinion == null || opinion.isBlank()) opinion = request.getContent();
        String source = support.planField(checkpoint.getPlan(), "source");
        String modelOptionsText = support.buildModelOptionsText("video");
        List<Map<String, Object>> models = support.buildModels("video");
        Map<String, String> recommended = Map.of();
        Map<String, String> reasons = Map.of();
        String message;
        int duration;
        if (source != null && !source.isBlank()) {
            VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(source, opinion, modelOptionsText);
            message = plan.message();
            duration = plan.duration();
            recommended = plan.params() == null ? Map.of() : plan.params();
            reasons = plan.reasons() == null ? Map.of() : plan.reasons();
        } else {
            AgentOrchestratorSupport.VideoPlanResult plan = support.callVideoPlan(opinion, modelOptionsText);
            message = plan.message();
            duration = plan.duration();
            recommended = plan.params();
            reasons = plan.reasons();
        }
        String planText = "📹 视频方案：\n" + message + "\n（时长 " + duration + " 秒）";
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "generate_video",
                List.of(Map.of("message", message, "duration", duration, "source", source == null ? "" : source)),
                "video_plan",
                List.of(Map.of("id", "generate_video", "title", "开始生成视频"),
                        Map.of("id", "refine", "title", "继续完善"),
                        Map.of("id", "custom", "title", "✍ 自定义输入")),
                models, recommended, reasons));
    }
}
