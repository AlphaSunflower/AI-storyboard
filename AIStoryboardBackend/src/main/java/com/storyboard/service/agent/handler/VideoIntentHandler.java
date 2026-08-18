package com.storyboard.service.agent.handler;

import com.storyboard.dto.response.AssetVO;
import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.service.AssetService;
import com.storyboard.service.ai.VideoPlanService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * video 视频链：资产询问（有资产 → 勾选卡片）→ 关联性门禁 → 方案设计
 * （有参考图 → 视觉模型 buildVideoPlan；无图 → LLM callVideoPlan；勾选资产设定集拼入方案输入）
 * → HITL video_plan 卡片（开始生成视频/继续完善）→ resume 异步视频生成（task_accepted + 后台轮询）。
 */
@Component
@RequiredArgsConstructor
public class VideoIntentHandler implements IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoIntentHandler.class);

    private final AgentOrchestratorSupport support;
    private final VideoPlanService videoPlanService;
    private final AssetService assetService;

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
        // 资产询问：项目有可用资产 → 弹资产选择卡片（勾选/跳过后才设计方案；source=video 由 Orchestrator 分派）
        List<AssetVO> projectAssets = assetService.projectAssets(request.getConversation().getProjectId());
        if (projectAssets != null && !projectAssets.isEmpty()) {
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    "检测到资产库，本次生成视频要投入哪些资产？（可多选）",
                    "asset-selection",
                    List.of(Map.of("content", request.getContent(), "source", "video",
                            "picUrl", request.getPicUrl() == null ? "" : request.getPicUrl())),
                    "human_input",
                    List.of(Map.of("id", "asset-confirm", "title", "使用所选资产"),
                            Map.of("id", "asset-skip", "title", "不使用资产，直接生成")),
                    List.of(), Map.of(), Map.of(), List.of(), List.of(),
                    support.buildAssetOptions(projectAssets)));
        }
        return handleFromVideoClarifyGate(request, request.getContent(), List.of(), request.getPicUrl());
    }

    /**
     * 视频需求澄清 gate：LLM 分析需求模糊程度，多维度追问后再进视频方案设计。
     * type=1（需求已明确）→ 直接跳过进 handleVideoPlan；type=0 → 发 HITL 选项卡片暂停。
     */
    private String handleFromVideoClarifyGate(OrchestrationRequest request, String content,
                                               List<String> assetIds, String source) {
        // 有参考图时跳过澄清（参考图本身就是明确信息）
        if (source != null && !source.isBlank()) {
            return handleVideoPlan(request, content, assetIds, source);
        }
        String ctx = support.historyContext(request.getConversation().getId(), 15);
        String enriched = content + (ctx.isBlank() ? "" : ctx);
        AgentOrchestratorSupport.ImageClarifyResult clarify = support.callVideoClarify(enriched, request);
        if (clarify != null && clarify.type() == 0) {
            List<Map<String, Object>> options = clarify.options() != null
                    ? new java.util.ArrayList<>(clarify.options()) : new java.util.ArrayList<>();
            options.add(Map.of("id", "custom", "title", "✍ 自定义输入"));
            support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    clarify.message(), "video-clarify",
                    List.of(Map.of("content", content, "assetIds", String.join(",", assetIds),
                            "source", source == null ? "" : source, "options", options)),
                    "human_input", options), true);
            return request.getLastMessage();
        }
        return handleVideoPlan(request, content, assetIds, source);
    }

    // ===== resume =====

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String cpAction = checkpoint.getAction();
        // 资产选择卡片（asset-selection）：记录勾选 → 关联性门禁 → 需求澄清 → 设计方案
        if ("asset-selection".equals(cpAction)) {
            String content = support.planField(checkpoint.getPlan(), "content");
            String picUrl = support.planField(checkpoint.getPlan(), "picUrl");
            List<String> ids = "asset-skip".equals(request.getAction())
                    ? List.of() : request.getAssetIds() == null ? List.of() : request.getAssetIds();
            List<AssetVO> chosen = support.pickAssets(
                    assetService.projectAssets(request.getConversation().getProjectId()), ids);
            String gate = support.runAssetGate(request, content, chosen, "video", picUrl);
            if (gate != null) return request.getLastMessage();
            return handleFromVideoClarifyGate(request, content, ids, picUrl);
        }
        // 资产门禁澄清卡片（asset-gate）：重新描述/不使用/仍然使用 → 重判或放行后设计方案
        if ("asset-gate".equals(cpAction)) {
            List<String> prevIds = parseAssetIds(support.planField(checkpoint.getPlan(), "assetIds"));
            List<AssetVO> chosen = support.pickAssets(
                    assetService.projectAssets(request.getConversation().getProjectId()), prevIds);
            AgentOrchestratorSupport.AssetGateResume r = support.resumeAssetGate(request, checkpoint, chosen);
            if (!r.proceed()) return request.getLastMessage();
            return handleFromVideoClarifyGate(request, r.prompt(), r.assetIds(),
                    support.planField(checkpoint.getPlan(), "picUrl"));
        }
        // 视频需求澄清卡片续流：用户选择/自定义输入 → 拼入需求 → 进视频方案设计
        if ("video-clarify".equals(cpAction)) {
            String original = support.planField(checkpoint.getPlan(), "content");
            String source = support.planField(checkpoint.getPlan(), "source");
            List<String> assetIds = parseAssetIds(support.planField(checkpoint.getPlan(), "assetIds"));
            String chosenId = request.getAction();
            String title = "custom".equals(chosenId)
                    ? request.getCustomText()
                    : support.planListField(checkpoint.getPlan(), "options").stream()
                            .filter(o -> chosenId.equals(o.get("id")))
                            .map(o -> String.valueOf(o.getOrDefault("title", "")))
                            .findFirst().orElse("");
            String supplemented = title.isBlank() ? original : original + "\n（补充：" + title + "）";
            return handleVideoPlan(request, supplemented, assetIds, source);
        }
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
        // 勾选资产的第一张图片优先作视频首帧（人物形象参照：资产照片直接决定人物长相；
        // 纯文字描述生成的视频人物与资产完全不像——2026-08-17 用户反馈；无资产图才回退用户参考图 PicUrl）
        String assetIdsCsv = support.planField(checkpoint.getPlan(), "assetIds");
        String assetImage = support.firstAssetImageUrl(request.getConversation().getProjectId(), assetIdsCsv);
        if (!assetImage.isBlank()) source = assetImage;
        // 生成参数：用户提交 params 优先，未提交回退 checkpoint 推荐/原值（startVideoGenerationAsync 内做键级兜底）
        support.startVideoGenerationAsync(request, message, duration, null, source, request.getParams());
        return "";
    }

    /**
     * 视频方案设计（asset-selection/asset-gate resume 与 handle 无资产分支共用）：
     * 勾选资产设定集拼入方案输入 → 视觉模型（有图）/ LLM（无图）→ video_plan 卡片（plan 存 assetIds 供后续完善接力）。
     */
    private String handleVideoPlan(OrchestrationRequest request, String content, List<String> assetIds, String source) {
        try {
            // 网关模型列表（卡片参数选择器用）+ LLM 选参选项文本（网关不可用时为空）
            List<Map<String, Object>> models = support.buildModels("video");
            String modelOptionsText = support.buildModelOptionsText("video");
            support.sendEvent(request, "workflow", Map.of("title", "正在设计视频方案…", "status", "node_started"));
            // 勾选资产设定集：文字拼入方案输入（视觉模型/LLM 都按设定生成，人物外貌/道具/场景不得改变）；
            // 方案输入同时拼最近会话上下文（用户可能只说「重新生成」，完整需求在历史消息里）
            List<AssetVO> chosen = support.pickAssets(
                    assetService.projectAssets(request.getConversation().getProjectId()), assetIds);
            String sheet = support.assetSheetText(chosen);
            String ctx = support.historyContext(request.getConversation().getId(), 15);
            String enriched = content
                    + (ctx.isBlank() ? "" : ctx)
                    + (sheet.isBlank() ? "" : "\n\n【本次投入的资产设定，视频方案必须体现且不得改变】\n" + sheet);
            String message;
            int duration;
            Map<String, String> recommended = Map.of();
            Map<String, String> reasons = Map.of();
            if (source != null && !source.isBlank()) {
                // 视觉模型看图 + 诉求 → 视频方案（首帧语义）
                VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(source, enriched, modelOptionsText);
                message = plan.message();
                duration = plan.duration();
                recommended = plan.params() == null ? Map.of() : plan.params();
                reasons = plan.reasons() == null ? Map.of() : plan.reasons();
            } else {
                // 无图：LLM 生成视频方案（prompt + 时长 + 推荐参数）
                AgentOrchestratorSupport.VideoPlanResult plan = support.callVideoPlan(enriched, modelOptionsText);
                message = plan.message();
                duration = plan.duration();
                recommended = plan.params();
                reasons = plan.reasons();
            }
            // HITL 通用模板：方案 → checkpoint(generate_video) → video_plan 卡片（models/推荐参数随事件下发；
            // plan 存 assetIds 供 refine/custom 重设计时接力；卡片正文告知投入资产）
            String planText = "📹 视频方案：\n" + message + "\n（时长 " + duration + " 秒）";
            if (!sheet.isBlank()) {
                planText += "\n\n🎬 本次投入资产：" + String.join("、", chosen.stream().map(AssetVO::name).toList());
            }
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    planText, "generate_video",
                    List.of(Map.of("message", message, "duration", duration,
                            "source", source == null ? "" : source,
                            "assetIds", String.join(",", assetIds))),
                    "video_plan",
                    List.of(Map.of("id", "generate_video", "title", "开始生成视频"),
                            Map.of("id", "refine", "title", "继续完善"),
                            Map.of("id", "custom", "title", "✍ 自定义输入")),
                    models, recommended, reasons));
        } catch (Exception e) {
            log.error("VideoIntentHandler.handleVideoPlan 失败: conversationId={}, error={}",
                    request.getConversation().getId(), e.getMessage(), e);
            return support.sendFriendlyError(request, e.getMessage(), "视频方案暂时没生成出来，请稍后重试或换个说法。");
        }
    }

    /** 继续完善：调整意见卡片（✍ 自定义输入，用户描述修改意见；plan 接力传递 assetIds） */
    private String showVideoOpinionCard(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String message = support.planField(checkpoint.getPlan(), "message");
        String duration = support.planField(checkpoint.getPlan(), "duration");
        String source = support.planField(checkpoint.getPlan(), "source");
        String assetIds = support.planField(checkpoint.getPlan(), "assetIds");
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "📹 想怎么调整视频方案？\n\n请直接描述修改意见（如节奏、运镜、内容、时长、画幅），我会重新设计方案。",
                "video-opinion",
                List.of(Map.of("message", message, "duration", duration,
                        "source", source == null ? "" : source,
                        "assetIds", assetIds == null ? "" : assetIds)),
                "human_input",
                List.of(Map.of("id", "custom", "title", "✍ 自定义输入"))));
    }

    /** 合并自定义意见重新设计方案（有图 → 视觉模型看原图；无图 → LLM；资产设定集按原勾选接力）→ 新 video_plan 卡片 */
    private String resumeVideoWithOpinion(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String opinion = request.getCustomText();
        if (opinion == null || opinion.isBlank()) opinion = request.getContent();
        String source = support.planField(checkpoint.getPlan(), "source");
        List<String> assetIds = parseAssetIds(support.planField(checkpoint.getPlan(), "assetIds"));
        return handleVideoPlan(request, opinion, assetIds, source);
    }

    /** 逗号分隔资产 ID 字符串 → List（宽松解析） */
    private List<String> parseAssetIds(String s) {
        if (s == null || s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
