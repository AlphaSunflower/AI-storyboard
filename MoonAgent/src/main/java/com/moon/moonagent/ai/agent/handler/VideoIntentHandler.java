package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.ai.VideoPlanService;
import com.moon.moonagent.ai.agent.PlanField;
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
    private final StoryboardClient storyboardClient;

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
        // 2026-08-28 用户反馈（第5次）：用户消息明确「根据资产设计视频」时，应直接用项目资产，不再弹「是否投资产」。
        // 判定优先级：
        //  1) assetIds 非空（用户从资产库勾选）→ 直接用该资产；
        //  2) 消息含资产意图词（根据资产/按资产/用资产/结合资产等）且项目有资产 → 直接用项目资产；
        //  3) 只传了参考图、未提及资产 → 弹卡问「要不要投入资产库资产」；
        //  4) 两者都无 → 走需求澄清 gate。
        // 注意：getProjectAssets(projectId) 返回项目库存量资产，不能无条件自动投（否则重蹈「未上传却包含资产」覆辙），
        //       仅当用户明确「按资产」或用 assetIds 勾选时才投。
        List<AssetVO> projectAssets = storyboardClient.getProjectAssets(request.getConversation().getProjectId());
        List<String> picUrls = request.getPicUrls() == null ? List.of() : request.getPicUrls();
        List<String> assetIds = request.getAssetIds() == null ? List.of() : request.getAssetIds();
        boolean wantsAsset = wantsAssetByContent(request.getContent());
        log.info("[视频编排] handle 入口: conversationId={}, projectId={}, 本次带资产ids={}, 项目库存量资产数={}, 本次参考图数={}, 消息含资产意图={}",
                request.getConversation().getId(), request.getConversation().getProjectId(),
                assetIds, projectAssets == null ? 0 : projectAssets.size(), picUrls.size(), wantsAsset);
        if (!assetIds.isEmpty()) {
            // 用户已选中资产 → 直接用该资产设计方案（资产图当参考图），不弹「是否投资产」卡
            log.info("[视频编排] 用户本次选中了资产({}个)，直接进视频方案（不再弹「是否投资产」）", assetIds.size());
            return handleFromVideoClarifyGate(request, request.getContent(), assetIds, picUrls);
        }
        if (wantsAsset && projectAssets != null && !projectAssets.isEmpty()) {
            // 用户明确「根据资产」且项目有资产 → 直接用全部项目资产进方案，不弹「是否投资产」
            log.info("[视频编排] 用户消息含资产意图，直接用项目资产({}个)进方案（不再弹「是否投资产」）", projectAssets.size());
            return handleFromVideoClarifyGate(request, request.getContent(),
                    projectAssets.stream().map(AssetVO::id).toList(), picUrls);
        }
        if (picUrls.isEmpty()) {
            // 无资产无参考图 → 走需求澄清 gate
            log.info("[视频编排] 本次无资产且无参考图，走需求澄清 gate");
            return handleFromVideoClarifyGate(request, request.getContent(), List.of(), picUrls);
        }
        // 只上传了参考图、未选中资产且未提及资产 → 弹卡问「要不要投入资产库资产」；资产库资产作可选项，用户勾选才投、跳过则不投
        log.info("[视频编排] 仅上传参考图({}张)，弹 asset-selection 询问是否投入资产（库存资产={}个，可选不选，不自动投）",
                picUrls.size(), projectAssets == null ? 0 : projectAssets.size());
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "本次生成视频，是否还要投入资产库的资产设定？（可多选；不选则只用你上传的参考图）",
                "asset-selection",
                List.of(Map.of("content", request.getContent(), "source", "video",
                        "picUrls", picUrls)),
                "human_input",
                List.of(Map.of("id", "asset-confirm", "title", "使用所选资产"),
                        Map.of("id", "asset-skip", "title", "不使用资产，只用参考图")),
                List.of(), Map.of(), Map.of(), List.of(), List.of(),
                support.buildAssetOptions(projectAssets)));
    }

    /** 判断用户消息是否明确表达「使用/根据资产库资产」意图（命中则直接用项目资产，不再弹「是否投资产」）。
     *  仅精确匹配资产意图短语，避免「资产」单独出现（如"没有资产/不要资产"）误判。 */
    private boolean wantsAssetByContent(String content) {
        if (content == null || content.isBlank()) return false;
        String c = content.trim();
        String[] keywords = {"根据资产", "按资产", "用资产", "结合资产", "参照资产", "使用资产", "按设定", "根据设定", "用设定"};
        for (String kw : keywords) {
            if (c.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 视频需求澄清 gate：LLM 分析需求模糊程度，多维度追问后再进视频方案设计。
     * type=1（需求已明确）→ 直接跳过进 handleVideoPlan；type=0 → 发 HITL 选项卡片暂停。
     */
    private String handleFromVideoClarifyGate(OrchestrationRequest request, String content,
                                               List<String> assetIds, List<String> sources) {
        // 已有参考图（本轮 picUrls）或已勾选资产（上传的参考图/设定卡本身即明确信息）→ 跳过澄清，直接设计方案。
        // 2026-08-27 用户反馈：有清晰场景 + 已上传资产/图片时不该再问「需要更多细节/请描述场景内容」——
        // 仅凭 picUrl 判定漏掉了资产勾选链路（资产来自项目资产库，不是本条消息的 picUrl）。
        if ((sources != null && !sources.isEmpty()) || (assetIds != null && !assetIds.isEmpty())) {
            return handleVideoPlan(request, content, assetIds, sources);
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
                            "picUrls", sources == null ? List.of() : sources, "options", options)),
                    "human_input", options), true);
            return request.getLastMessage();
        }
        // 需求明确/达上限 → 直接用用户内容设计视频方案
        return handleVideoPlan(request, content, assetIds, sources);
    }



    // ===== resume =====

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String cpAction = checkpoint.getAction();
        // 资产选择卡片（asset-selection）：记录勾选 → 关联性门禁 → 需求澄清 → 设计方案
        if ("asset-selection".equals(cpAction)) {
            String content = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            List<String> picUrls = support.planPicUrls(checkpoint.getPlan());
            List<String> ids = "asset-skip".equals(request.getAction())
                    ? List.of() : request.getAssetIds() == null ? List.of() : request.getAssetIds();
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(request.getConversation().getProjectId()), ids);
            String gate = support.runAssetGate(request, content, chosen, "video", picUrls);
            if (gate != null) return request.getLastMessage();
            // 用户「使用所选资产」→ 所选资产/参考图已定义场景，跳过需求澄清直接设计方案；
            // 「不使用资产」→ 无资产锚点，保留澄清（文本模糊才追问，明确则 type=1 直接过）。
            // 2026-08-27 用户反馈：选了资产还让「选择示例」——澄清 gate 只看文本看不到图，会误判「根据这个界面」为模糊。
            if (!"asset-skip".equals(request.getAction())) {
                return handleVideoPlan(request, content, ids, picUrls);
            }
            return handleFromVideoClarifyGate(request, content, ids, picUrls);
        }
        // 资产门禁澄清卡片（asset-gate）：重新描述/不使用/仍然使用 → 重判或放行后设计方案
        if ("asset-gate".equals(cpAction)) {
            List<String> prevIds = parseAssetIds(support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS));
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(request.getConversation().getProjectId()), prevIds);
            AgentOrchestratorSupport.AssetGateResume r = support.resumeAssetGate(request, checkpoint, chosen);
            if (!r.proceed()) return request.getLastMessage();
            // 资产门禁已确认（用户重描述相关/仍然使用）→ 直接设计方案，不再问需求澄清
            return handleVideoPlan(request, r.prompt(), r.assetIds(),
                    support.planPicUrls(checkpoint.getPlan()));
        }
        // 视频需求澄清卡片续流：用户选择/自定义输入 → 拼入需求 → 视频来源人工介入
        if ("video-clarify".equals(cpAction)) {
            String original = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            List<String> sources = support.planPicUrls(checkpoint.getPlan());
            List<String> assetIds = parseAssetIds(support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS));
            String chosenId = request.getAction();
            String title = "custom".equals(chosenId)
                    ? request.getCustomText()
                    : support.planListField(checkpoint.getPlan(), "options").stream()
                            .filter(o -> chosenId.equals(o.get("id")))
                            .map(o -> String.valueOf(o.getOrDefault("title", "")))
                            .findFirst().orElse("");
            String supplemented = title.isBlank() ? original : original + "\n（补充：" + title + "）";
            return handleVideoPlan(request, supplemented, assetIds, sources);
        }
        // 视频来源卡片续流（video-source）：has-prompt → 用户填写描述 → 视频方案；no-prompt → LLM 自由生成
        if ("video-source".equals(cpAction)) {
            String original = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            List<String> assetIds = parseAssetIds(support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS));
            List<String> sources = support.planPicUrls(checkpoint.getPlan());
            String supplemented;
            if ("has-prompt".equals(request.getAction())) {
                String custom = request.getCustomText();
                supplemented = (custom != null && !custom.isBlank())
                        ? original + "\n（用户提供的描述：" + custom + "）" : original;
            } else {
                // no-prompt：内容太短时补充方向引导
                supplemented = original.length() < 20
                        ? "用户想生成视频但没有具体描述，请自由发挥设计一个有创意的视频方案。用户原始需求：" + original
                        : original;
            }
            return handleVideoPlan(request, supplemented, assetIds, sources);
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
        String message = support.planField(checkpoint.getPlan(), PlanField.MESSAGE);
        String duration = support.planField(checkpoint.getPlan(), PlanField.DURATION);
        List<String> sources = support.planPicUrls(checkpoint.getPlan());
        // 勾选资产的全部图片优先作视频参考（人物形象参照：资产照片直接决定人物长相；
        // 纯文字描述生成的视频人物与资产完全不像——2026-08-17 用户反馈；无资产图才回退用户参考图 PicUrls）
        String assetIdsCsv = support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS);
        List<String> assetImages = support.assetImageUrls(request.getConversation().getProjectId(), assetIdsCsv);
        if (!assetImages.isEmpty()) sources = assetImages;
        // 生成参数：用户提交 params 优先，未提交回退 checkpoint 推荐/原值（startVideoGenerationAsync 内做键级兜底）
        support.startVideoGenerationAsync(request, message, duration, null, sources, request.getParams());
        return "";
    }

    /**
     * 视频方案设计（asset-selection/asset-gate resume 与 handle 无资产分支共用）：
     * 勾选资产设定集拼入方案输入 → 视觉模型（有图）/ LLM（无图）→ video_plan 卡片（plan 存 assetIds 供后续完善接力）。
     */
    private String handleVideoPlan(OrchestrationRequest request, String content, List<String> assetIds, List<String> sources) {
        try {
            // 日志：确认资产/图片/图片text是否有带入（用户实测定位「图带没带进生成」）
            log.info("[视频编排] handleVideoPlan 入口: 资产ids={}, 参考图数={}, 参考图={}",
                    assetIds, sources == null ? 0 : sources.size(), sources);
            List<AssetVO> chosenTmp = support.pickAssets(
                    storyboardClient.getProjectAssets(request.getConversation().getProjectId()),
                    assetIds == null ? List.of() : assetIds);
            if (chosenTmp != null && !chosenTmp.isEmpty()) {
                int imgCnt = 0; int txtCnt = 0;
                for (var a : chosenTmp) {
                    if (a.images() != null) {
                        imgCnt += a.images().size();
                        txtCnt += (int) a.images().stream().filter(im -> im.text() != null && !im.text().isBlank()).count();
                    }
                }
                log.info("[视频编排] 勾选资产={}个, 名称={}, 图片总数={}, 带图片text约束数={}",
                        chosenTmp.size(), chosenTmp.stream().map(AssetVO::name).toList(), imgCnt, txtCnt);
            }
            // 网关模型列表（卡片参数选择器用）+ LLM 选参选项文本（网关不可用时为空）
            List<Map<String, Object>> models = support.buildModels("video");
            String modelOptionsText = support.buildModelOptionsText("video");
            support.sendEvent(request, "workflow", Map.of("title", "正在设计视频方案…", "status", "node_started"));
            // 勾选资产设定集：文字拼入方案输入（视觉模型/LLM 都按设定生成，人物外貌/道具/场景不得改变）；
            // 方案输入同时拼最近会话上下文（用户可能只说「重新生成」，完整需求在历史消息里）
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(request.getConversation().getProjectId()), assetIds);
            String sheet = support.assetSheetText(chosen);
            // 无参考图但勾选了含图的资产：用全部资产图作为视觉方案输入——2026-08-27 用户反馈「读取图片生成」，
            // 资产图直接决定人物外貌/场景，让视觉模型看图设计，plan 正文才能准确反映产出（而非仅靠文字设定集拼）。
            if ((sources == null || sources.isEmpty()) && chosen != null) {
                List<String> assetUrls = chosen.stream()
                        .filter(a -> a.images() != null && !a.images().isEmpty())
                        .flatMap(a -> a.images().stream())
                        .map(img -> img.url())
                        .filter(u -> u != null && !u.isBlank())
                        .distinct().toList();
                if (!assetUrls.isEmpty()) sources = assetUrls;
            }
            String ctx = support.historyContext(request.getConversation().getId(), 15);
            String enriched = content
                    + (ctx.isBlank() ? "" : ctx)
                    + (sheet.isBlank() ? "" : "\n\n【本次投入的资产设定，视频方案必须体现且不得改变】\n" + sheet);
            String message;
            int duration;
            Map<String, String> recommended = Map.of();
            Map<String, String> reasons = Map.of();
            if (sources != null && !sources.isEmpty()) {
                // 视觉模型看全部图 + 诉求 → 视频方案
                VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(sources, enriched, modelOptionsText);
                message = plan.message();
                duration = plan.duration();
                recommended = plan.params() == null ? Map.of() : plan.params();
                reasons = plan.reasons() == null ? Map.of() : plan.reasons();
            } else {
                // 无图：LLM 生成视频方案（prompt + 时长 + 推荐参数）
                AgentOrchestratorSupport.VideoPlanResult plan = support.callVideoPlan(enriched, modelOptionsText, request);
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
                            "picUrls", sources == null ? List.of() : sources,
                            "assetIds", String.join(",", assetIds))),
                    "video_plan",
                    support.buildHITLCard(request, planText, List.of(
                            Map.of("id", "generate_video", "title", "开始生成视频"),
                            Map.of("id", "refine", "title", "继续完善"),
                            Map.of("id", "custom", "title", "✍ 自定义输入"))),
                    models, recommended, reasons));
        } catch (Exception e) {
            log.error("VideoIntentHandler.handleVideoPlan 失败: conversationId={}, error={}",
                    request.getConversation().getId(), e.getMessage(), e);
            return support.sendFriendlyError(request, e.getMessage(), "视频方案暂时没生成出来，请稍后重试或换个说法。");
        }
    }

    /** 继续完善：调整意见卡片（✍ 自定义输入，用户描述修改意见；plan 接力传递 assetIds） */
    private String showVideoOpinionCard(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String message = support.planField(checkpoint.getPlan(), PlanField.MESSAGE);
        String duration = support.planField(checkpoint.getPlan(), PlanField.DURATION);
        List<String> sources = support.planPicUrls(checkpoint.getPlan());
        String assetIds = support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS);
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "📹 想怎么调整视频方案？\n\n请直接描述修改意见（如节奏、运镜、内容、时长、画幅），我会重新设计方案。",
                "video-opinion",
                List.of(Map.of("message", message, "duration", duration,
                        "picUrls", sources == null ? List.of() : sources,
                        "assetIds", assetIds == null ? "" : assetIds)),
                "human_input",
                List.of(Map.of("id", "custom", "title", "✍ 自定义输入"))));
    }

    /** 合并自定义意见重新设计方案（有图 → 视觉模型看原图；无图 → LLM；资产设定集按原勾选接力）→ 新 video_plan 卡片 */
    private String resumeVideoWithOpinion(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String opinion = request.getCustomText();
        if (opinion == null || opinion.isBlank()) opinion = request.getContent();
        List<String> sources = support.planPicUrls(checkpoint.getPlan());
        List<String> assetIds = parseAssetIds(support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS));
        return handleVideoPlan(request, opinion, assetIds, sources);
    }

    /** 逗号分隔资产 ID 字符串 → List（宽松解析） */
    private List<String> parseAssetIds(String s) {
        if (s == null || s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
