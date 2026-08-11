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
        return Set.of("generate_video");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        String source = request.getPicUrl();
        try {
            support.sendEvent(request, "workflow", Map.of("title", "正在设计视频方案…", "status", "node_started"));
            String message;
            int duration;
            if (source != null && !source.isBlank()) {
                // 视觉模型看图 + 诉求 → 视频方案（首帧语义）
                VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(source, request.getContent());
                message = plan.message();
                duration = plan.duration();
            } else {
                // 无图：LLM 生成视频方案（prompt + 时长）
                AgentOrchestratorSupport.VideoPlanResult plan = support.callVideoPlan(request.getContent());
                message = plan.message();
                duration = plan.duration();
            }
            // HITL 通用模板：方案 → checkpoint(generate_video) → video_plan 卡片
            String planText = "📹 视频方案：\n" + message + "\n（时长 " + duration + " 秒）";
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    planText, "generate_video",
                    List.of(Map.of("message", message, "duration", duration, "source", source == null ? "" : source)),
                    "video_plan",
                    List.of(Map.of("id", "generate_video", "title", "开始生成视频"),
                            Map.of("id", "refine", "title", "继续完善"))));
        } catch (Exception e) {
            log.error("VideoIntentHandler.handle 失败: conversationId={}, error={}",
                    request.getConversation().getId(), e.getMessage(), e);
            support.sendEvent(request, "error", Map.of("code", "50202", "message", "视频方案生成失败，请稍后重试"));
            return "";
        }
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 视频异步执行：方案确认后创建任务 → task_accepted → 本轮结束，前端轮询状态端点取结果。
        // 无同步 message 输出，返回空串（结果由后台轮询更新资产行，前端轮询渲染）
        String message = support.planField(checkpoint.getPlan(), "message");
        String duration = support.planField(checkpoint.getPlan(), "duration");
        String source = support.planField(checkpoint.getPlan(), "source");
        support.startVideoGenerationAsync(request, message, duration, null, source);
        return "";
    }
}
