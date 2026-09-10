package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.ai.VideoPlanService;
import com.moon.moonagent.ai.agent.PlanField;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
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
        // 2026-09-05 用户反馈（再一次拍板）：不再弹「本次生成视频，要投入哪些资产库资产？（可多选）」门禁——
        // 该交互无意义，改为直接用「用户本次自然携带的资产/参考图」。判定优先级：
        //  1) assetIds 非空（用户已携带）→ 直接用这些资产；
        //  2) 有参考图（picUrls）→ 直接用参考图；
        //  3) 无资产无图 → 走需求澄清 gate。
        List<AssetVO> projectAssets = storyboardClient.getProjectAssets(request.getConversation().getProjectId());
        List<String> picUrls = request.getPicUrls() == null ? List.of() : request.getPicUrls();
        if (picUrls.isEmpty()) {
            List<String> restored = support.referenceMaterialUrls(request.getConversation().getId());
            if (!restored.isEmpty()) {
                request.setPicUrls(restored);
                picUrls = restored;
            }
        }
        List<String> assetIds = request.getAssetIds() == null ? List.of() : request.getAssetIds();
        log.info("[视频编排] handle 入口: conversationId={}, projectId={}, 本次带资产ids={}, 项目库存量资产数={}, 本次参考图数={}",
                request.getConversation().getId(), request.getConversation().getProjectId(),
                assetIds, projectAssets == null ? 0 : projectAssets.size(), picUrls.size());
        if (!assetIds.isEmpty()) {
            // 用户已选中资产 → 直接用该资产设计方案（资产图当参考图），不弹「是否投资产」卡
            log.info("[视频编排] 用户本次选中了资产({}个)，直接进视频方案（不再弹「是否投资产」）", assetIds.size());
            return handleFromVideoClarifyGate(request, request.getContent(), assetIds, picUrls);
        }
        if (picUrls.isEmpty()) {
            // 无资产无参考图 → 走需求澄清 gate
            log.info("[视频编排] 项目无资产且无参考图，走需求澄清 gate");
            return handleFromVideoClarifyGate(request, request.getContent(), List.of(), picUrls);
        }
        // 项目无资产但有参考图 → 直接用参考图
        log.info("[视频编排] 项目无资产但有参考图({}张)，直接用参考图进方案", picUrls.size());
        return handleFromVideoClarifyGate(request, request.getContent(), List.of(), picUrls);
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

        // 旧「参考图 role 选择」卡片已改为视觉模型自动推荐。若会话仍停留在 video-role-select，
        // 忽略用户所选动作，用停点信息重新走 handleVideoPlan（由视觉模型自动推荐角色）。
        if ("video-role-select".equals(cpAction)) {
            String content = support.planField(checkpoint.getPlan(), "content");
            String assetIdsStr = support.planField(checkpoint.getPlan(), "assetIds");
            List<String> assetIds = assetIdsStr == null || assetIdsStr.isBlank()
                    ? List.of() : java.util.Arrays.stream(assetIdsStr.split(",")).toList();
            List<String> sources = support.planSources(checkpoint.getPlan());
            log.info("[视频编排] 命中旧 video-role-select 停点，忽略所选动作并自动推荐角色");
            return handleVideoPlan(request, content, assetIds, sources);
        }


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
        // 从 checkpoint 恢复参考图角色（自动选择阶段存入 plan），并拆解为逐图 role 透传给模型
        String refRole = support.planField(checkpoint.getPlan(), "referenceRole");
        if (refRole != null && !refRole.isBlank() && sources != null && !sources.isEmpty()) {
            List<ReferenceImage> refImages = buildReferenceImages(refRole, sources);
            log.info("[视频编排] 使用带角色的视频生成: semanticRole={}, 图片数={}, 拆解前3图={}",
                    refRole, refImages.size(),
                    refImages.stream().limit(3).map(r -> r.role() + ":" + r.url()).toList());
            support.startVideoGenerationAsyncWithRole(request, message, duration, null, refImages, request.getParams());
        } else {
            support.startVideoGenerationAsync(request, message, duration, null, sources, request.getParams());
        }
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
            support.sendWorkflowEvent(request, "design_video_plan", "正在设计视频方案…", "node_started");
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
            // 用户上传素材时填写的用途（agent_assets.purpose）→ 通知视频方案按 @图片N 锚定（H3 参考素材说明）
            java.util.Map<String, String> uploadPurposes = support.referenceMaterialPurposes(
                    request.getConversation().getId());
            if (!uploadPurposes.isEmpty() && sources != null && !sources.isEmpty()) {
                StringBuilder purposeText = new StringBuilder("\n\n【本次上传素材用途（H3 参考素材说明，不得改变）】\n");
                int purposeIdx = 1;
                boolean hasPurpose = false;
                for (String s : sources) {
                    if (s == null || s.isBlank()) continue;
                    String p = uploadPurposes.getOrDefault(s, "");
                    if (!p.isBlank()) {
                        purposeText.append("@图片").append(purposeIdx).append(" 是").append(p.trim()).append("\n");
                        hasPurpose = true;
                    }
                    purposeIdx++;
                }
                if (hasPurpose) enriched = enriched + purposeText;
            }
            // 用户已在 video-role-select 选中参考图角色 → 把角色诉求拼入方案输入，让视觉/LLM 按要求产出
            String referenceRole = request.getParams() == null ? null : request.getParams().get("referenceRole");
            if (referenceRole != null && !referenceRole.isBlank()) {
                String roleHint = switch (referenceRole) {
                    case "FIRST_FRAME" -> "参考图用于首帧：视频的第一帧必须以上传图片为准。";
                    case "LAST_FRAME" -> "参考图用于尾帧：视频的最后一帧必须以上传图片为准。";
                    case "FIRST_LAST_FRAME" -> "参考图用于首尾帧：图1作为首帧，图2作为尾帧。";
                    default -> "参考图仅作风格参考，模型可自由演绎。";
                };
                enriched = enriched + "\n\n【参考图角色】" + roleHint;
            }
            String message;
            int duration;
            Map<String, String> recommended = Map.of();
            Map<String, String> reasons = Map.of();



            if (sources != null && !sources.isEmpty()) {
                // 视觉模型看全部图 + 诉求 → 视频方案（同时输出推荐的参考图角色）
                VideoPlanService.VideoPlan plan = videoPlanService.buildVideoPlan(sources, enriched, modelOptionsText);
                message = plan.message();
                duration = plan.duration();
                recommended = plan.params() == null ? Map.of() : plan.params();
                reasons = plan.reasons() == null ? Map.of() : plan.reasons();
                
                // 从 VideoPlan 获取推荐的参考图角色（只认 4 个合法值；非法/缺失按图数回退默认）
                String planRole = normalizeRole(plan.referenceRole());
                if (planRole != null) {
                    referenceRole = planRole;
                    log.info("[视频编排] 从 VideoPlan 获取参考图角色: {}", referenceRole);
                } else if (normalizeRole(referenceRole) == null) {
                    // 既无合法推荐、也无合法已选角色 → 按图数回退默认
                    referenceRole = defaultRole(sources.size());
                    log.info("[视频编排] VideoPlan 未输出合法角色，使用默认: {}", referenceRole);
                } else {
                    // 已选角色（来自 request.params）合法 → 归一大小写
                    referenceRole = normalizeRole(referenceRole);
                }
                // 将角色写回 request.params，供后续 startVideoGenerationAsync 使用
                Map<String, String> updatedParams = new java.util.HashMap<>(request.getParams() == null ? Map.of() : request.getParams());
                updatedParams.put("referenceRole", referenceRole);
                request.setParams(updatedParams);
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
                            "assetIds", String.join(",", assetIds),
                            "referenceRole", referenceRole == null ? "" : referenceRole)),
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

    /** 语义参考图角色：只认 4 个合法值，大小写归一；非法/缺失返回 null */
    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) return null;
        String r = role.trim().toUpperCase();
        return switch (r) {
            case "FIRST_FRAME", "LAST_FRAME", "FIRST_LAST_FRAME", "REFERENCE" -> r;
            default -> null;
        };
    }

    /** 按图片数量回退默认语义角色 */
    private static String defaultRole(int imageCount) {
        return switch (imageCount) {
            case 1 -> "FIRST_FRAME";
            case 2 -> "FIRST_LAST_FRAME";
            default -> "REFERENCE";
        };
    }

    /**
     * 语义角色 → 逐图 ReferenceImage（MiniMax 按图带 role）。
     * FIRST_LAST_FRAME 拆成「图1=首帧、图末=尾帧、中间=参考」；仅 1 张时按 FIRST_FRAME 处理。
     */
    static List<ReferenceImage> buildReferenceImages(String semanticRole, List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
        String role = normalizeRole(semanticRole);
        if (role == null) role = "REFERENCE";
        List<ReferenceImage> out = new ArrayList<>();
        switch (role) {
            case "FIRST_FRAME" -> {
                for (int i = 0; i < urls.size(); i++) {
                    out.add(new ReferenceImage(urls.get(i),
                            i == 0 ? ReferenceImageRole.FIRST_FRAME : ReferenceImageRole.REFERENCE));
                }
            }
            case "LAST_FRAME" -> {
                int last = urls.size() - 1;
                for (int i = 0; i < urls.size(); i++) {
                    out.add(new ReferenceImage(urls.get(i),
                            i == last ? ReferenceImageRole.LAST_FRAME : ReferenceImageRole.REFERENCE));
                }
            }
            case "FIRST_LAST_FRAME" -> {
                int last = urls.size() - 1;
                for (int i = 0; i < urls.size(); i++) {
                    ReferenceImageRole r;
                    if (i == 0) r = ReferenceImageRole.FIRST_FRAME;
                    else if (i == last && last > 0) r = ReferenceImageRole.LAST_FRAME;
                    else r = ReferenceImageRole.REFERENCE;
                    out.add(new ReferenceImage(urls.get(i), r));
                }
            }
            default -> {
                for (String u : urls) out.add(new ReferenceImage(u, ReferenceImageRole.REFERENCE));
            }
        }
        return out;
    }
}



