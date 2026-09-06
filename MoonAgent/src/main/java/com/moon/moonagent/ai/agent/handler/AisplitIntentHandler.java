package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.response.AssetVO;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.ai.agent.PlanField;
import com.moon.moonagent.ai.agent.AgentTools;
import com.moon.moonagent.ai.agent.SuggestedAction;
import com.moon.moonagent.ai.agent.AssetMatchingService;
import com.moon.moonagent.ai.agent.SceneAssetMatch;
import com.moon.moonagent.ai.agent.storyboard.StoryboardDesignPlan;
import com.moon.moonagent.ai.agent.storyboard.StoryboardPortfolioService;
import com.moon.moonagent.ai.agent.storyboard.StoryboardReferenceBinding;
import com.moon.moonagent.ai.agent.storyboard.StoryboardReferenceRole;
import com.moon.moonagent.ai.agent.storyboard.StoryboardScenePlan;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.ScriptGenerationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * aisplit 分镜链：现有分镜检测 → 处理方式卡片 → 剧本优化 gate → 分镜方案 → 分镜 JSON → HITL 确认 → resume 写库。
 *
 * <p>人工介入卡片（全部复用 human_input 基建，前端零改动）：
 * <ul>
 *   <li>入口检测（handle 首步）：项目已有分镜 → 卡片1「处理方式」（基于现有优化 / 全新创建 / 不生成），
 *       checkpoint action=scene-mode，选项 id 走 Orchestrator 特判（动态，不进 byAction 注册表）</li>
 *   <li>方案确认卡片：有现有分镜 → 动态选项 [覆盖导入 replace][追加 append][不满意 disagree][不生成 cancel]，
 *       planText 前置覆盖警告（将删除现有分镜及其产出素材）；无 → 现状 [满意 agree][不满意 disagree]</li>
 *   <li>不满意（disagree）：发「调整意见」卡片（checkpoint action=scene-regenerate，选项=✍自定义输入），
 *       用户意见 + 上一轮方案 → 重走生成链（可循环）</li>
 * </ul>
 * 剧本优化与分镜方案两个 gate 仍为手动澄清循环（type=0 选项卡片追问，type=1 继续）。
 */
@Component
@RequiredArgsConstructor
public class AisplitIntentHandler implements IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(AisplitIntentHandler.class);

    private final AgentOrchestratorSupport support;
    private final ScriptGenerationService scriptGenerationService;
    private final AgentTools agentTools;
    private final StoryboardClient storyboardClient;
    private final AgentAiConfigProperties agentConfig;
    private final AssetMatchingService assetMatchingService;
    private final StoryboardPortfolioService portfolioService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 并行 LLM 步骤专用：互不依赖的调用放虚拟线程并发，避免整条分镜链串行卡住。 */
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 连续不满意调整计数与本轮勾选资产已迁入 {@link AgentOrchestratorSupport}
     * （P4 2026-08-21：随 checkpoint.plan JSON 持久化，重启后 resume 恢复）。
     */

    @Override
    public String intentType() {
        return "intent-aisplit";
    }

    @Override
    public Set<String> resumeActions() {
        // agree/replace/append/cancel/disagree：方案确认卡片（checkpoint action 恒为 agree）的写库策略选项；
        // scene-mode-* 与 scene-regenerate 的 custom 选项是动态 id，走 Orchestrator 特判，不注册
        return Set.of("agree", "replace", "append", "cancel", "disagree");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        restoreMaterialsIfEmpty(request);
        log.info("[分镜] handle 素材: picUrls={}, assetIds={}, sessionAssets={}",
                request.getPicUrls(), request.getAssetIds(),
                support.getSessionAssets(request.getConversation().getId()));
        // 不再弹「检测到资产库，本次生成分镜要使用哪些资产？（可多选）」门禁——该交互无意义。
        // 改为：用户本次上传了素材（PicUrls）或勾选了资产（assetIds）→ 走素材绑定+分镜方案设计前置增强；
        // 否则走常规文本生成链。素材用途随上传携带（text 字段）或从聊天文字说明提取。
        List<String> picUrls = request.getPicUrls();
        List<String> assetIds = request.getAssetIds();
        boolean hasMaterials = (picUrls != null && !picUrls.isEmpty())
                || (assetIds != null && !assetIds.isEmpty())
                || support.hasSessionAssets(request.getConversation().getId());
        if (hasMaterials) {
            // 仍需先做「已有分镜如何处理」检测；素材/资产会随卡片 checkpoint 携带，选择后再回到素材绑定路径
            return continueAisplit(request, request.getContent());
        }
        return continueAisplit(request, request.getContent());
    }

    /**
     * 素材绑定 + 分镜方案设计前置增强（用户上传素材/勾选资产才走）。
     * 整理智能体把「提示词 ↔ 素材图」绑定，分镜方案设计师把素材编入具体分镜并分配参考角色，
     * 再把编排蓝图拼回剧本，交给现有分镜生成链（会产出 H3 三段式 videoPrompt，含 @图片N 与角色），
     * 复用现有「方案确认卡 → 写库」链路（零新增 resume 分支，行为稳定）。
     */
    private String handleMaterialBoundStoryboard(OrchestrationRequest request, String content,
                                                 List<String> picUrls, List<String> assetIds) {
        // 先进会话快照；即使整理/设计失败，回退常规链也能继续携带资产
        List<String> effective = assetIds == null ? List.of() : assetIds;
        support.setSessionAssets(request.getConversation().getId(), effective);
        StoryboardDesignPlan plan;
        try {
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(request.getConversation().getProjectId()),
                    effective);
            support.sendEvent(request, "workflow", Map.of("title", "正在分析素材…", "status", "node_started"));
            List<StoryboardReferenceBinding> bindings = portfolioService.bindMaterials(
                    content, picUrls, chosen,
                    support.referenceMaterialPurposes(request.getConversation().getId()),
                    support.referenceMaterialKinds(request.getConversation().getId()));
            support.sendEvent(request, "workflow", Map.of("title", "正在设计分镜方案…", "status", "node_started"));
            plan = portfolioService.designPlan(content, bindings);
        } catch (Exception e) {
            log.warn("[分镜] 素材整理/方案设计失败，回退常规分镜链（资产已保留）: {}", e.getMessage());
            return continueAisplit(request, content);
        }
        String bridged = content + "\n\n【分镜素材编排（已由方案设计师编排，不得违背）】\n"
                + blueprintText(plan);
        return handleFromPlanGate(request, bridged, effective, blueprintText(plan), true,
                sceneBindingsPayload(plan), portfolioService.toScenes(plan));
    }

    /** 把素材编排蓝图转成人类可读文本（拼入剧本，供分镜生成与确认卡展示）。 */
    private String blueprintText(StoryboardDesignPlan plan) {
        if (plan == null || plan.scenes() == null || plan.scenes().isEmpty()) return "（无素材编排）";
        StringBuilder sb = new StringBuilder();
        sb.append("全局画幅：").append(plan.aspectRatio()).append("\n");
        for (StoryboardScenePlan s : plan.scenes()) {
            sb.append("分镜").append(s.sceneNumber()).append(" 剧情：").append(nvl(s.scriptContent(), "见上方剧本"));
            if (s.referenceBindings() != null && !s.referenceBindings().isEmpty()) {
                sb.append("\n  素材：");
                for (int i = 0; i < s.referenceBindings().size(); i++) {
                    StoryboardReferenceBinding b = s.referenceBindings().get(i);
                    String roleName = StoryboardReferenceRole.parse(b.role()) == null
                            ? "全能参考" : StoryboardReferenceRole.parse(b.role()).name();
                    sb.append("图").append(i + 1).append("（").append(b.assetId()).append("）作")
                            .append(roleName).append(" · ").append(nvl(b.purposeText(), "用户未明确说明"));
                    if (i < s.referenceBindings().size() - 1) sb.append("；");
                }
                sb.append("\n");
            } else {
                sb.append("\n  素材：无\n");
            }
        }
        return sb.toString();
    }

    private static String nvl(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    /** 设计兜底：toScenes 的 AgentSceneItem → 标准分镜 Map（sceneNumber 按项目 max+1 递增）。 */
    private List<Map<String, Object>> fallbackItemsToMaps(List<AgentSceneItem> items, String projectId) {
        int num = storyboardClient.maxSceneNumber(projectId);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (AgentSceneItem item : items) {
            num++;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("projectId", projectId);
            m.put("sceneNumber", num);
            m.put("scriptContent", nvl(item.scriptContent(), "分镜" + num));
            m.put("imagePrompt", nvl(item.imagePrompt(), item.scriptContent()));
            m.put("videoPrompt", nvl(item.videoPrompt(), item.scriptContent()));
            m.put("negativePrompt", nvl(item.negativePrompt(), "模糊,畸变,多余文字,水印,logo,形变,多余肢体,低清晰度,噪点"));
            m.put("cameraMovement", nvl(item.cameraMovement(), ""));
            m.put("shotType", nvl(item.shotType(), "全景"));
            m.put("soundDesign", nvl(item.soundDesign(), ""));
            m.put("referenceBindings", List.of());
            out.add(m);
        }
        return out;
    }

    /** 将设计方案的编排编号对齐到分镜实际生成的 sceneNumber（项目已有分镜时生成编号从 max+1 开始）。 */
    private List<Map<String, Object>> alignSceneBindings(List<Map<String, Object>> bindings,
                                                         List<Map<String, Object>> scenes) {
        if (bindings == null || bindings.isEmpty()) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = 0; i < bindings.size(); i++) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(bindings.get(i));
            if (i < scenes.size() && scenes.get(i).get("sceneNumber") instanceof Number n) {
                copy.put("sceneNumber", n.intValue());
            }
            out.add(copy);
        }
        return out;
    }

    /** 把方案设计师的每镜素材绑定转成 checkpoint 载荷（sceneNumber + referenceBindings）。 */
    private List<Map<String, Object>> sceneBindingsPayload(StoryboardDesignPlan plan) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (plan == null || plan.scenes() == null) return out;
        for (StoryboardScenePlan s : plan.scenes()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("sceneNumber", s.sceneNumber());
            m.put("referenceBindings", s.referenceBindings() == null ? List.of() : s.referenceBindings());
            out.add(m);
        }
        return out;
    }

    /**
     * 资产已定的正式分镜链入口（handle 与 asset-selection/asset-gate resume 共用）：
     * 现有分镜检测 → 处理方式卡片 / 剧本优化 gate。
     */
    private String continueAisplit(OrchestrationRequest request, String content) {
        restoreMaterialsIfEmpty(request);
        if (request.getAssetIds() != null && !request.getAssetIds().isEmpty()) {
            support.setSessionAssets(request.getConversation().getId(), request.getAssetIds());
        }
        long existing = existingSceneCount(request.getConversation().getProjectId());
        if (existing > 0) {
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    "检测到您当前项目已有 " + existing + " 个分镜，如何处理？",
                    "scene-mode",
                    List.of(Map.of("content", content, "existingCount", existing)),
                    "human_input",
                    List.of(
                            Map.of("id", "scene-mode-optimize", "title", "基于现有分镜进一步优化"),
                            Map.of("id", "scene-mode-fresh", "title", "额外创建全新的分镜内容"),
                            Map.of("id", "scene-mode-cancel", "title", "本次不生成分镜"))));
        }
        if (hasMaterials(request)) {
            return handleMaterialBoundStoryboard(request, content,
                    request.getPicUrls(), effectiveAssetIds(request));
        }
        return handleFromClarifyGate(request, content, currentAssetIds(request));
    }

    /**
     * 需求澄清 gate：LLM 分析需求模糊程度，多维度追问后再进正式剧本优化。
     * type=1（需求已明确）→ 直接跳过进 handleFromScriptGate；type=0 → 发 HITL 选项卡片暂停。
     */
    private String handleFromClarifyGate(OrchestrationRequest request, String content, List<String> assetIds) {
        support.sendEvent(request, "workflow", Map.of("title", "正在分析需求…", "status", "node_started"));
        AgentOrchestratorSupport.ScriptOptimizeResult clarify = support.callRequirementClarify(content, request);
        if (clarify != null && clarify.type() == 0) {
            // 需求模糊 → 多维度澄清卡片（用户可选或自定义输入）
            List<Map<String, Object>> options = clarify.options() != null
                    ? new java.util.ArrayList<>(clarify.options()) : new java.util.ArrayList<>();
            options.add(Map.of("id", "custom", "title", "✍ 自定义输入"));
            support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    clarify.message(), "requirement-clarify",
                    List.of(Map.of("content", content, "assetIds", assetIds == null ? List.of() : assetIds,
                            "options", options)),
                    "human_input", options), true);
            return request.getLastMessage();
        }
        // 需求已明确 → 剧本来源人工介入（有剧本先填写 / 没有帮我生成）
        return showScriptSourceGate(request, content, assetIds);
    }

    /**
     * 剧本来源人工介入：需求已明确后，问用户有没有现成剧本/提示词。
     * 有 → 用户填写后进剧本优化；没有 → LLM 自由生成。
     */
    private String showScriptSourceGate(OrchestrationRequest request, String content, List<String> assetIds) {
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "你有现成的剧本或提示词吗？有的话可以先填写，没有也没关系，我来帮你生成。",
                "script-source",
                List.of(Map.of("content", content, "assetIds", assetIds == null ? List.of() : assetIds)),
                "human_input",
                List.of(Map.of("id", "has-script", "title", "有，我来填写"),
                        Map.of("id", "no-script", "title", "没有，帮我生成"))));
    }

    /**
     * 从「剧本优化 gate」重走完整分镜链（澄清选项/调整意见补充后由 resume 调用，可重入）。
     *
     * @param content  用户需求（可为原始消息，或澄清选项/调整意见补充后的拼接文本）
     * @param assetIds 本轮勾选的资产 ID（空 = 不注入资产设定集）
     */
    public String handleFromScriptGate(OrchestrationRequest request, String content, List<String> assetIds) {
        // 0. 输入拼最近会话上下文：用户可能只说「重新生成/继续」，完整需求在历史消息里
        //    （剧本优化 gate、分镜方案、分镜 JSON 全部基于拼后的内容；checkpoint plan 仍存原始 content，resume 不重复拼）
        content = content + support.historyContext(request.getConversation().getId(), 15);
        // 1. 剧本优化（手动澄清循环：type=0 追问结束本轮 / type=1 继续；message 字段已流式增量转发）
        support.sendEvent(request, "workflow", Map.of("title", "正在优化剧本…", "status", "node_started"));
        AgentOrchestratorSupport.ScriptOptimizeResult opt = support.callScriptOptimize(content, request);
        String script;
        if (opt == null || opt.type() == 0) {
            // 澄清上限判定：未达上限 → 追问（选项卡片优先，无 options 退回文本）并结束本轮；已达上限 → 以原始需求为默认剧本继续
            if (askClarify(request, "script", content,
                    opt != null ? opt.message() : "已理解你的需求，请继续补充。",
                    opt != null ? opt.options() : List.of())) {
                return request.getLastMessage();
            }
            script = content;
        } else {
            support.resetClarify(request.getConversation().getId());
            script = opt.script() != null && !opt.script().isBlank() ? opt.script() : content;
        }
        return handleFromPlanGate(request, script, assetIds);
    }

    /**
     * 从「分镜方案 gate」重走（剧本已定，澄清选项补充后由 resume 调用，可重入）。
     *
     * @param script   已定剧本
     * @param assetIds 本轮勾选的资产 ID（空 = 不注入、不自动关联）
     */
    public String handleFromPlanGate(OrchestrationRequest request, String script, List<String> assetIds) {
        return handleFromPlanGate(request, script, assetIds, null, false, List.of(), List.of());
    }

    /**
     * 同 {@link #handleFromPlanGate(OrchestrationRequest, String, List)}，额外把素材编排蓝图追加到确认卡正文
     * （逐镜展示素材 + 参考角色 + 用途，满足「用户能看清每个分镜用了什么素材、作什么」）。
     *
     * @param extraPlanText 追加到方案正文的素材编排文本（可空）
     */
    public String handleFromPlanGate(OrchestrationRequest request, String script, List<String> assetIds,
                                     String extraPlanText) {
        return handleFromPlanGate(request, script, assetIds, extraPlanText, false, List.of(), List.of());
    }

    /**
     * 同 {@link #handleFromPlanGate(OrchestrationRequest, String, List)}，额外素材编排蓝图追加到确认卡，
     * 并可选跳过「分镜方案澄清 gate」（素材已被整理智能体/方案设计师编排好，再问一次会重复丢失上下文）。
     */
    public String handleFromPlanGate(OrchestrationRequest request, String script, List<String> assetIds,
                                     String extraPlanText, boolean skipDesignGate) {
        return handleFromPlanGate(request, script, assetIds, extraPlanText, skipDesignGate, List.of(), List.of());
    }

    /**
     * 同 {@link #handleFromPlanGate(OrchestrationRequest, String, List)}，额外素材编排蓝图追加到确认卡，
     * 并可选跳过「分镜方案澄清 gate」；{@code sceneBindings} 随 checkpoint 落库，写库后自动挂载到对应分镜。
     */
    public String handleFromPlanGate(OrchestrationRequest request, String script, List<String> assetIds,
                                     String extraPlanText, boolean skipDesignGate,
                                     List<Map<String, Object>> sceneBindings,
                                     List<AgentSceneItem> fallbackSceneItems) {
        // 2. 分镜方案设计（结构化 message，流式增量转发）
        support.sendEvent(request, "workflow", Map.of("title", "正在设计分镜方案…", "status", "node_started"));
        AgentOrchestratorSupport.StoryboardPlanResult plan = null;
        if (!skipDesignGate) {
            plan = support.callStoryboardPlan(script, request);
            if (plan == null || plan.type() == 0) {
                // 澄清上限判定同上：未达上限 → 追问结束本轮；已达上限 → 直接进入分镜生成
                if (askClarify(request, "plan", script,
                        plan != null ? plan.message() : "分镜方案需要进一步明确，请补充描述。",
                        plan != null ? plan.options() : List.of())) {
                    return request.getLastMessage();
                }
            } else {
                support.resetClarify(request.getConversation().getId());
            }
        }

        // 3. 分镜 JSON（复用 ScriptGenerationService：LLM 生成 8 字段分镜列表；
        // assetIds 非 null 时只注入勾选资产设定集——空=用户不使用资产不注入，null=全量旧行为）
        support.sendEvent(request, "workflow", Map.of("title", "正在生成分镜…", "status", "node_started"));
        String projectId = request.getConversation().getProjectId();
        List<Map<String, Object>> preSceneMaps = fallbackSceneItems == null || fallbackSceneItems.isEmpty()
                ? List.of()
                : fallbackItemsToMaps(fallbackSceneItems, projectId);
        List<AssetVO> chosen = support.pickAssets(storyboardClient.getProjectAssets(request.getConversation().getProjectId()),
                assetIds == null ? List.of() : assetIds);

        // 并行优化 1：分镜 JSON、自动资产关联、参数推荐互不依赖，放虚拟线程并发（失败各自兜底，不阻塞）。
        // asset 关联在素材编排路径可先用 portfolio 的 fallbackSceneItems 提前匹配，避免等 generateScenes 串行。
        CompletableFuture<List<Map<String, Object>>> scenesFuture = CompletableFuture.supplyAsync(() -> {
            try {
                var generated = scriptGenerationService.generateScenesWithReasoning(
                        projectId, script, "movie", null, "16:9", null, null, null,
                        assetIds == null || assetIds.isEmpty() ? java.util.Collections.emptyList() : assetIds);
                if (generated.reasoning() != null) {
                    request.setReasoning(generated.reasoning());
                    if (request.getTrace() != null) request.getTrace().reasoning(generated.reasoning());
                }
                return generated.scenes();
            } catch (Exception e) {
                log.warn("分镜 JSON 生成失败，走设计方案兜底: {}", e.getMessage());
                return fallbackSceneItems == null || fallbackSceneItems.isEmpty()
                        ? List.of()
                        : fallbackItemsToMaps(fallbackSceneItems, projectId);
            }
        }, agentExecutor);

        support.sendEvent(request, "workflow", Map.of("title", "正在关联资产…", "status", "node_started"));
        CompletableFuture<List<SceneAssetMatch>> assetFuture = null;
        if (!chosen.isEmpty() && !preSceneMaps.isEmpty()) {
            assetFuture = CompletableFuture.supplyAsync(() -> assetMatchingService.matchScenes(preSceneMaps, chosen), agentExecutor);
        }
        support.sendEvent(request, "workflow", Map.of("title", "正在推荐生成参数…", "status", "node_started"));
        CompletableFuture<AgentOrchestratorSupport.SceneParamsRecommendation> recFuture =
                CompletableFuture.supplyAsync(() -> support.recommendSceneParams(script, request), agentExecutor);

        List<Map<String, Object>> scenes = scenesFuture.join();
        if (scenes == null || scenes.isEmpty()) {
            support.sendMessage(request, "⚠ 未能生成分镜内容，请重新描述需求。");
            return request.getLastMessage();
        }
        List<Map<String, Object>> effectiveBindings = alignSceneBindings(sceneBindings, scenes);

        // 3.5 自动关联判定：分镜列表 + 勾选资产 → 每镜出现的资产（单独 LLM 调用；失败降级不关联，不阻塞）
        // 结果写进 scenes 的 assetIds 字段随 checkpoint plan 落库，写库时按分镜号写 scene_assets
        List<SceneAssetMatch> matches = List.of();
        if (!chosen.isEmpty()) {
            if (assetFuture == null) {
                List<Map<String, Object>> sceneMaps = scenes.stream()
                        .map(s -> {
                            Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("sceneNumber", s.get("sceneNumber"));
                            m.put("scriptContent", s.get("scriptContent") == null ? "" : s.get("scriptContent"));
                            return m;
                        }).toList();
                assetFuture = CompletableFuture.supplyAsync(() -> assetMatchingService.matchScenes(sceneMaps, chosen), agentExecutor);
            }
            matches = assetFuture.join();
            if (!matches.isEmpty()) {
                for (Map<String, Object> s : scenes) {
                    int num = s.get("sceneNumber") instanceof Number n ? n.intValue() : 0;
                    matches.stream().filter(m -> m.sceneNumber() == num).findFirst()
                            .ifPresent(m -> s.put("assetIds", m.assetIds()));
                }
            }
        }

        // 4. HITL 通用模板：方案消息 → checkpoint(agree) → human_input 事件。
        AgentOrchestratorSupport.SceneParamsRecommendation rec = recFuture.join();
        // 已有分镜 → 选项变为写库策略（replace/append/disagree/cancel）+ 覆盖警告；无 → 现状（满意/不满意）
        long existing = existingSceneCount(request.getConversation().getProjectId());
        // 部分替换判定：方案只覆盖部分现有分镜（用户「只优化分镜N」/分镜选择卡只勾选几镜）时，
        // 写库走 replaceSpecificScenes 只更新选中分镜，绝不删除其他分镜——警告与按钮文案须随之区分。
        boolean partial = existing > 0 && isPartialPlan(scenes, request.getConversation().getProjectId());
        List<Map<String, Object>> actions = existing > 0
                ? List.of(
                    Map.of("id", "replace", "title", partial ? "更新选中分镜，其他保持不变" : "先删除现有分镜再导入"),
                    Map.of("id", "append", "title", "追加到现有分镜后面"),
                    Map.of("id", "disagree", "title", "不满意，重新生成"),
                    Map.of("id", "cancel", "title", "不生成分镜"))
                : List.of(Map.of("id", "agree", "title", "满意"), Map.of("id", "disagree", "title", "不满意"));
        String planText = "📋 分镜方案（共 " + scenes.size() + " 个镜头）：\n" + support.summarizeScenes(scenes);
        if (extraPlanText != null && !extraPlanText.isBlank()) {
            planText += "\n\n" + extraPlanText;
        }
        if (!matches.isEmpty()) {
            planText += "\n\n🔗 建议资产关联：\n" + buildMatchSummary(matches, chosen);
        }
        if (existing > 0) {
            // 覆盖导入会删除现有分镜及其产出素材——卡片正文前置警告；部分替换时说明只更新选中分镜
            planText = (partial
                    ? "ℹ 只更新选中的 " + scenes.size() + " 个分镜，其他分镜及其产出素材保持不变。\n"
                    : "⚠ 检测到您当前已有 " + existing + " 个分镜（含已生成的图片/视频素材），覆盖导入将全部删除。\n")
                    + planText;
        }
        // 步骤 4：确认卡按钮由 LLM 声明（id 落在上方状态感知 manifest 内；LLM 失败回退静态列表）
        actions = support.buildHITLCard(request, planText, actions);
        List<Map<String, Object>> payloadScenes = scenes;
        if (!effectiveBindings.isEmpty() && !payloadScenes.isEmpty()) {
            payloadScenes = new java.util.ArrayList<>(payloadScenes);
            Map<String, Object> first = new java.util.LinkedHashMap<>(payloadScenes.get(0));
            first.put("sceneBindings", effectiveBindings);
            payloadScenes.set(0, first);
        }
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "agree", payloadScenes, "human_input", actions,
                List.of(), rec.recommended(), rec.reasons(), rec.imageModels(), rec.videoModels()));
    }

    /**
     * 澄清追问统一出口：未达上限 → 优先发 human_input 选项卡片（LLM 输出 options），
     * 无 options 退回纯文本追问；已达上限 → 返回 false，调用方以原始需求为默认方案继续。
     *
     * @param kind           gate 标识（script=剧本优化 gate / plan=分镜方案 gate）
     * @param originalContent 追问时已确定的需求文本（resume 时拼入所选选项）
     * @param questionText    追问文本（卡片 formContent / 文本消息共用）
     * @param options         LLM 输出的选项（可为空）
     * @return true=本轮已结束（调用方 return）；false=达澄清上限（调用方按默认方案继续）
     */
    private boolean askClarify(OrchestrationRequest request, String kind, String originalContent,
                               String questionText, List<Map<String, Object>> options) {
        if (support.clarifyLimitReached(request.getConversation().getId(), request)) {
            return false;
        }
        if (options != null && !options.isEmpty()) {
            // 追加「自定义输入」选项：没有想要的选项时用户可自由输入（resume 特判 action=custom 用 customText）
            List<Map<String, Object>> withCustom = new java.util.ArrayList<>(options);
            withCustom.add(Map.of("id", "custom", "title", "✍ 自定义输入"));
            // skipMessage=true：追问文本已由流式 message 增量发过（callScriptOptimize/Plan 的
            // streamPlanWithMessage 转发 message 字段），此处再发一次会重复；卡片 formContent 不受影响
            support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    questionText, "clarify-option",
                    List.of(Map.of("kind", kind, "originalContent", originalContent, "options", withCustom)),
                    "human_input", withCustom), true);
        } else if (request.getLastMessage().isBlank()) {
            // 流式失败兜底 / LLM 未输出 options：退回纯文本追问（message 未发出时补一条）
            support.sendMessage(request, questionText);
        }
        return true;
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        restoreMaterialsIfEmpty(request);
        // 资产选择卡片（asset-selection）：记录勾选 → 关联性门禁 → 进入正式分镜链（source=aisplit 由 Orchestrator 分派）
        if ("asset-selection".equals(checkpoint.getAction())) {
            String content = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            List<String> ids = "asset-skip".equals(request.getAction())
                    ? List.of() : request.getAssetIds() == null ? List.of() : request.getAssetIds();
            support.setSessionAssets(request.getConversation().getId(), ids);
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(request.getConversation().getProjectId()), ids);
            // 关联性门禁：弱关联弹澄清卡片结束本轮；相关/判定失败/无资产放行
            String gate = support.runAssetGate(request, content, chosen, "aisplit", java.util.List.of());
            if (gate != null) return request.getLastMessage();
            return continueAisplit(request, content);
        }
        // 资产门禁澄清卡片（asset-gate）：重新描述/不使用/仍然使用 → 重判或放行后继续
        if ("asset-gate".equals(checkpoint.getAction())) {
            List<String> prevIds = currentAssetIds(request);
            List<AssetVO> chosen = support.pickAssets(
                    storyboardClient.getProjectAssets(request.getConversation().getProjectId()), prevIds);
            AgentOrchestratorSupport.AssetGateResume r = support.resumeAssetGate(request, checkpoint, chosen);
            if (!r.proceed()) return request.getLastMessage();
            support.setSessionAssets(request.getConversation().getId(), r.assetIds());
            return continueAisplit(request, r.prompt());
        }

        // 澄清选项续流：所选选项标题拼入需求，重走对应 gate（kind=script 从剧本优化重走；plan 从分镜方案重走）。
        // action=custom（自定义输入）：直接用用户输入文本作为补充，不查 options 表
        if ("clarify-option".equals(checkpoint.getAction())) {
            String kind = support.planField(checkpoint.getPlan(), PlanField.KIND);
            String original = support.planField(checkpoint.getPlan(), PlanField.ORIGINAL_CONTENT);
            String chosenId = request.getAction();
            String title = "custom".equals(chosenId)
                    ? request.getCustomText()
                    : support.planListField(checkpoint.getPlan(), "options").stream()
                            .filter(o -> chosenId.equals(o.get("id")))
                            .map(o -> String.valueOf(o.getOrDefault("title", "")))
                            .findFirst().orElse("");
            String supplemented = title.isBlank() ? original : original + "\n（补充：" + title + "）";
            // 取消意图出口：动态选项标题（如「取消操作/取消分镜生成/跳过换个话题」）或 custom 文本 → 干净收尾
            if (support.isCancelIntent(title)) {
                return endWithoutScenes(request, "好的，已取消这次分镜生成。需要时随时再叫我。");
            }
            return "plan".equals(kind)
                    ? handleFromPlanGate(request, supplemented, currentAssetIds(request))
                    : handleFromScriptGate(request, supplemented, currentAssetIds(request));
        }

        // 需求澄清卡片续流：用户选择/自定义输入 → 拼入需求 → 进剧本优化（跳过二次澄清）
        if ("requirement-clarify".equals(checkpoint.getAction())) {
            String original = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            String chosenId = request.getAction();
            String title = "custom".equals(chosenId)
                    ? request.getCustomText()
                    : support.planListField(checkpoint.getPlan(), "options").stream()
                            .filter(o -> chosenId.equals(o.get("id")))
                            .map(o -> String.valueOf(o.getOrDefault("title", "")))
                            .findFirst().orElse("");
            String supplemented = title.isBlank() ? original : original + "\n（补充：" + title + "）";
            // 取消意图出口：选项标题/custom 文本命中「取消/不生成/算了...」→ 干净收尾不再循环（LLM 兜底）
            if (support.isCancelIntent(title)) {
                return endWithoutScenes(request, "好的，已取消这次分镜生成。需要时随时再叫我。");
            }
            // 「对指定分镜优化」意图：识别到用户想选特定分镜（如「我要对指定的分镜优化/哪些分镜」）
            // → 弹分镜选择卡（复用 assets 多选），只优化选中的分镜（写库走部分替换，保留其他分镜及其素材）
            if (isSceneSelectionIntent(title)) {
                if (existingSceneCount(request.getConversation().getProjectId()) > 0) {
                    return showSceneSelectCard(request, rawRequirement(original), currentAssetIds(request));
                }
            }
            // 澄清后 → 剧本来源人工介入（跳过二次澄清）
            List<String> assetIds = currentAssetIds(request);
            if (assetIds.isEmpty()) {
                // plan 里可能存了 assetIds
                String stored = support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS);
                if (stored != null && !stored.isBlank() && !"[]".equals(stored)) {
                    try {
                        @SuppressWarnings("unchecked")
                        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(stored, java.util.List.class);
                        assetIds = parsed.stream().map(String::valueOf).toList();
                    } catch (Exception ignored) {}
                }
            }
            // 用户已提供内容（自定义输入或选项文本）→ 跳过"有剧本吗"直接进剧本优化
            if (!title.isBlank()) {
                if (hasMaterials(request)) {
                    return handleMaterialBoundStoryboard(request, supplemented,
                            request.getPicUrls(), effectiveAssetIds(request));
                }
                return handleFromScriptGate(request, supplemented, assetIds);
            }
            return showScriptSourceGate(request, supplemented, assetIds);
        }

        // 剧本来源卡片续流：has-script → 用户填写剧本 → 进剧本优化；no-script → LLM 自由生成
        if ("script-source".equals(checkpoint.getAction())) {
            String original = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            List<String> assetIds = currentAssetIds(request);
            if (assetIds.isEmpty()) {
                String stored = support.planField(checkpoint.getPlan(), PlanField.ASSET_IDS);
                if (stored != null && !stored.isBlank() && !"[]".equals(stored)) {
                    try {
                        @SuppressWarnings("unchecked")
                        var parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(stored, java.util.List.class);
                        assetIds = parsed.stream().map(String::valueOf).toList();
                    } catch (Exception ignored) {}
                }
            }
            String supplemented;
            if ("has-script".equals(request.getAction())) {
                String custom = request.getCustomText();
                // 取消意图出口：用户填剧本时输入「取消/不弄了」→ 干净收尾
                if (support.isCancelIntent(custom)) {
                    return endWithoutScenes(request, "好的，已取消这次分镜生成。需要时随时再叫我。");
                }
                supplemented = (custom != null && !custom.isBlank())
                        ? original + "\n（用户提供的剧本：\n" + custom + "）" : original;
            } else {
                // no-script：内容太短时补充方向引导，让 LLM 有东西可写
                supplemented = original.length() < 20
                        ? "用户想生成分镜但没有具体内容，请自由发挥创作一个有趣的分镜剧本。用户原始需求：" + original
                        : original;
            }
            if (hasMaterials(request)) {
                return handleMaterialBoundStoryboard(request, supplemented,
                        request.getPicUrls(), effectiveAssetIds(request));
            }
            return handleFromScriptGate(request, supplemented, assetIds);
        }

        // 分镜选择卡片续流（scene-select）：用户勾选/输入了要优化的分镜 → 只优化这些分镜
        // （保留其他分镜及其素材，写库走部分替换 replaceSpecificScenes；不点名则全量）
        if ("scene-select".equals(checkpoint.getAction())) {
            String original = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            java.util.Set<Integer> targetNums = resolveTargetScenes(request);
            if (targetNums == null) {
                // 勾选/输入都没有识别到有效分镜 → 重新弹选择卡
                return showSceneSelectCard(request, original, currentAssetIds(request));
            }
            if (targetNums.isEmpty()) {
                // 「优化全部分镜」→ 现有全量逻辑
                return handleFromScriptGate(request, original, currentAssetIds(request));
            }
            // 只优化点名分镜：现有分镜（指定编号）+ 用户需求 + 限定只生成这些分镜（编号不变）
            String existing = buildExistingSceneText(request.getConversation().getProjectId(), targetNums);
            String supplemented = "【现有分镜】\n" + existing
                    + "\n【用户需求】\n" + original
                    + "\n请只针对用户点名的分镜（" + targetNums + "）优化，输出方案时只包含这些分镜，"
                    + "保持这些分镜的编号不变，不要修改/生成其他分镜。";
            return handleFromScriptGate(request, supplemented, currentAssetIds(request));
        }

        // 卡片1：分镜处理方式（scene-mode）——基于现有优化 / 全新创建 / 不生成
        if ("scene-mode".equals(checkpoint.getAction())) {
            String action = request.getAction();
            String original = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            if ("scene-mode-cancel".equals(action)) {
                return endWithoutScenes(request, "好的，本次不生成分镜。需要时可以随时再让我生成。");
            }
            if ("scene-mode-optimize".equals(action)) {
                // 基于现有分镜优化：识别用户需求里的「分镜N」——若点名了某个分镜，只拼该分镜优化，
                // 方案只含该分镜 → 写库走部分替换（保留其他分镜）；否则现有逻辑拼全部。
                // 也从 plan 里尝试恢复上一轮 scene-regenerate 的目标编号（scene-regenerate 无编号时全量）。
                java.util.Set<Integer> targetNums = new java.util.HashSet<>();
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("分镜\\s*(\\d+)").matcher(original);
                while (m2.find()) targetNums.add(Integer.parseInt(m2.group(1)));
                // 用户想选特定分镜（原文含「指定/哪些分镜」但没点名编号）→ 弹分镜选择卡
                if (targetNums.isEmpty() && isSceneSelectionIntent(original)) {
                    if (existingSceneCount(request.getConversation().getProjectId()) > 0) {
                        return showSceneSelectCard(request, rawRequirement(original), currentAssetIds(request));
                    }
                }
                String existing;
                if (targetNums.isEmpty()) {
                    existing = buildExistingSceneText(request.getConversation().getProjectId());
                } else {
                    existing = buildExistingSceneText(request.getConversation().getProjectId(), targetNums);
                }
                String supplemented = "【现有分镜】\n" + existing
                        + "\n【用户需求】\n" + original;
                if (targetNums.isEmpty()) {
                    supplemented += "\n请在保留现有分镜合理结构的基础上，按用户需求优化并生成新的分镜方案。";
                } else {
                    supplemented += "\n请只针对用户点名的分镜（" + targetNums + "）优化，输出方案时只包含这些分镜，"
                            + "保持这些分镜的编号不变，不要修改/生成其他分镜。";
                }
                return handleFromClarifyGate(request, supplemented, currentAssetIds(request));
            }
            // scene-mode-fresh：全新创建，正常流程
            if (hasMaterials(request)) {
                return handleMaterialBoundStoryboard(request, original,
                        request.getPicUrls(), effectiveAssetIds(request));
            }
            return handleFromClarifyGate(request, original, currentAssetIds(request));
        }

        // 调整意见（scene-regenerate）：上一轮方案文本 + 用户意见 → 重走生成链（LLM 基于意见重新优化）
        if ("scene-regenerate".equals(checkpoint.getAction())) {
            String prevPlan = support.planField(checkpoint.getPlan(), PlanField.CONTENT);
            String opinion = request.getCustomText();
            // 取消意图出口：调整意见填「算了/不弄了/取消」→ 干净收尾
            if (support.isCancelIntent(opinion)) {
                return endWithoutScenes(request, "好的，已取消这次分镜生成。需要时随时再叫我。");
            }
            String supplemented = prevPlan + "\n【用户调整意见】\n"
                    + (opinion == null || opinion.isBlank() ? "请重新生成一版更合适的方案" : opinion);
            return handleFromScriptGate(request, supplemented, currentAssetIds(request));
        }

        // 方案确认卡片（checkpoint action=agree）写库策略分发：replace=覆盖 / append=追加（现状）
        // / cancel=不写 / disagree=不满意→调整意见卡片；agree（无现有分镜）语义=新建，行为与现状一致
        String chosen = request.getAction();
        if ("cancel".equals(chosen)) {
            support.clearSessionAssets(request.getConversation().getId());
            return endWithoutScenes(request, "好的，已取消本次分镜导入，现有分镜保持不变。");
        }
        if ("disagree".equals(chosen)) {
            // 不满意 → 调整意见卡片（连续不满意达上限后不再弹，提示直接输入新需求）
            if (regenerateLimitReached(request.getConversation().getId())) {
                return endWithoutScenes(request, "已连续调整多次仍未满意，请直接输入新的分镜需求，我会重新处理。");
            }
            // 上一轮方案文本存 plan，用户自定义输入意见后重走生成链
            StringBuilder prev = new StringBuilder("上一轮分镜方案：\n");
            int idx = 1;
            for (AgentSceneItem s : support.parsePlanScenes(checkpoint.getPlan())) {
                if (s.scriptContent() != null && !s.scriptContent().isBlank()) {
                    prev.append(idx++).append(". ").append(s.scriptContent()).append("\n");
                }
            }
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    "这个分镜方案不满意？请告诉我需要调整什么（节奏、镜头数量、画面风格等），我会重新生成。",
                    "scene-regenerate",
                    List.of(Map.of("content", prev.toString())),
                    "human_input",
                    List.of(Map.of("id", "custom", "title", "✍ 自定义输入"))));
        }
        // 执行写分镜（EXECUTE step）。
        // 部分替换判断（2026-08-28）：用户「只针对分镜N优化」时，方案 items 的 scene_number 构成现有分镜的
        // 真子集（< 现有编号集合）——此时绝不能 replaceScenes（会清空全部/覆盖其他分镜），改用部分替换
        // updateScenesByNumber，只更新指定编号并保留其他分镜及其素材关联。
        List<AgentSceneItem> items = support.parsePlanScenes(checkpoint.getPlan());
        boolean partialReplace = isPartialSubset(items, request.getConversation().getProjectId());
        Map<String, Object> writeResult;
        if ("replace".equals(chosen) && partialReplace) {
            writeResult = safeUpdateScenesByNumber(request.getConversation().getProjectId(), items);
        } else {
            writeResult = "replace".equals(chosen)
                    ? safeReplaceScenes(request.getConversation().getProjectId(), items)
                    : safeWriteScenes(request.getConversation().getProjectId(), items);
        }
        int count = writeResult.getOrDefault("count", 0) instanceof Number n ? n.intValue() : 0;
        // 写库成功：不满意调整计数清零（新一轮生成起点）+ 用户确认的整套推荐参数应用到全部分镜
        support.clearRegenCount(request.getConversation().getId());
        if (count > 0) {
            Map<String, String> effectiveParams = request.getParams();
            if (effectiveParams == null || effectiveParams.isEmpty()) {
                effectiveParams = support.planRecommendedParams(checkpoint.getPlan());
            }
            support.applySceneParamsToProject(request.getConversation().getProjectId(), effectiveParams);
        }
        // 写库成功后自动关联 scene_assets：按分镜号匹配 checkpoint plan 里的资产关联结果（matchScenes 判定），
        // purpose 双写 image+video（图片/视频生成都能吃到）；无关联结果/失败跳过（分镜不受影响）。
        // 注意顺序：参数应用已加无-set 保护（getSqlSet 判空）不再抛异常；若未来仍有异常，关联失败不应
        // 阻断整体——本方法内部已 try-catch，但为稳妥保持 apply 在前、关联紧随其后
        if (count > 0) {
            linkSceneAssetsByMatch(request, checkpoint);
            linkSceneBindings(request, checkpoint);
            linkAllSelectedAssets(request, checkpoint);
        }
        // 本轮结束：勾选资产状态清理（下轮重新询问）
        support.clearSessionAssets(request.getConversation().getId());
        // sceneCount 传写库后项目分镜总数（writeScenes 为追加语义；前端用
        // sceneCount > 会话开始时数量 判断是否需要刷新分镜列表——总数才正确，replace 后即新数量）
        long totalScenes = storyboardClient.getProjectScenes(
                request.getConversation().getProjectId()).size();
        String msg = count > 0
                ? "✅ 分镜方案已确认，已生成 **" + count + " 个分镜**，请查看左侧分镜列表"
                : "⚠ 分镜方案已确认，但未解析到分镜内容，请重新描述需求";
        // 4.3 自动审查（2026-08-28 改为「软提示」）：不再写库成功即强制 handoff 到 scene-review——
        // 那样用户会无条件被抛进「分镜审查结果」卡，之后任何含"分镜"的消息又触发 review，形成死循环。
        // 改为正常 resumeStage 收尾（发「已生成 N 个分镜」确认消息），并在 message 里提示可手动审查，
        // 用户想查看缺图缺视频时主动说「审查分镜」即由意图识别正常路由到 scene-review。
        String msg2 = count > 0
                ? "✅ 分镜方案已确认，已生成 **" + count + " 个分镜**，请查看左侧分镜列表。\n\n"
                    + "需要我帮你检查哪些分镜缺图缺视频、或批量补生成吗？"
                : msg;
        // 推荐动作：审查分镜（不要求用户手动输入「审查分镜」，点按钮即带 routingHint 切链）
        java.util.List<SuggestedAction> suggestedActions = count > 0
                ? java.util.List.of(new SuggestedAction("scene-review", "审查分镜", "intent-scene-review"))
                : java.util.List.of();
        request.setSuggestedActions(suggestedActions);
        support.resumeStage(request, "正在写入分镜…", Map.of(
                "content", msg2,
                "confirm", Map.of("kind", "script", "sceneCount", totalScenes, "url", "", "actions", List.of()),
                "sceneCount", totalScenes,
                "suggestedActions", suggestedActions));
        return msg2;
    }

    /** 不写库收尾：message + message_end（sceneCount=-1，前端不触发分镜列表刷新） */
    private String endWithoutScenes(OrchestrationRequest request, String msg) {
        support.sendMessage(request, msg);
        support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", msg));
        return msg;
    }

    /** 本轮已确认的资产 ID（sessionAssets 快照；无记录返回空列表） */
    private List<String> currentAssetIds(OrchestrationRequest request) {
        return support.getSessionAssets(request.getConversation().getId());
    }

    /** 本轮实际生效资产：request.assetIds 优先，缺省回退会话快照。 */
    private List<String> effectiveAssetIds(OrchestrationRequest request) {
        List<String> ids = request.getAssetIds();
        return (ids == null || ids.isEmpty()) ? currentAssetIds(request) : ids;
    }

    /** 本轮是否已携带素材/资产（参考图、资产 ID，或会话已选资产）。 */
    private boolean hasMaterials(OrchestrationRequest request) {
        return (request.getPicUrls() != null && !request.getPicUrls().isEmpty())
                || (request.getAssetIds() != null && !request.getAssetIds().isEmpty())
                || support.hasSessionAssets(request.getConversation().getId());
    }

    /** 前端漏传参考图时，从会话 agent_assets 找回最近上传的 reference 图（并写回 request）。 */
    private void restoreMaterialsIfEmpty(OrchestrationRequest request) {
        if (request.getPicUrls() != null && !request.getPicUrls().isEmpty()) return;
        List<String> urls = support.referenceMaterialUrls(request.getConversation().getId());
        if (!urls.isEmpty()) {
            request.setPicUrls(urls);
            log.info("[分镜] 前端未携带参考图，已从会话资产找回 {} 张: {}", urls.size(), urls);
        }
    }

    /** 是否为「指定/选择/哪些分镜」意图（需要让用户选分镜，而非全量优化）；命中返回 true */
    private boolean isSceneSelectionIntent(String text) {
        if (text == null || text.isBlank()) return false;
        return java.util.regex.Pattern.compile(
                "指定\\s*分镜|指定的\\s*分镜|选择\\s*分镜|哪些\\s*分镜|部分\\s*分镜|"
                + "只优化|特定\\s*分镜|优化\\s*特定|哪些\\s*镜头|部分\\s*镜头|指定\\s*镜头|"
                + "挑\\s*几|选\\s*几|哪几|哪些|某些\\s*分镜|少数\\s*分镜")
                .matcher(text).find();
    }

    /**
     * 从补充需求文本中提取用户原始需求（剥掉「【现有分镜】…【用户需求】」与追加的全量生成指令；
     * 不命中【用户需求】标记时原样返回）。避免把「生成新的分镜方案」这类全量语气带进指定分镜优化。
     */
    private String rawRequirement(String text) {
        if (text == null || text.isBlank()) return text;
        String t = text;
        int idx = t.indexOf("【用户需求】");
        if (idx >= 0) {
            t = t.substring(idx + "【用户需求】".length()).trim();
            int cut = t.indexOf("请在保留现有分镜合理结构的基础上");
            if (cut > 0) t = t.substring(0, cut).trim();
        }
        return t;
    }

    /**
     * 弹「选择要优化的分镜」卡片：复用前端 human_input 的 assets 多选（前端零改动），
     * 选中的分镜编号以 assetIds 回传。卡片正文列出各分镜概要，勾选清单同源。
     */
    private String showSceneSelectCard(OrchestrationRequest request, String content, List<String> currentAssetIds) {
        var list = new java.util.ArrayList<>(storyboardClient.getProjectScenes(request.getConversation().getProjectId()));
        if (list.isEmpty()) {
            // 无现有分镜可选 → 直接走全量生成（兜底，不阻塞）
            return handleFromScriptGate(request, content, currentAssetIds);
        }
        list.sort(java.util.Comparator.comparingInt(Scene::getSceneNumber));
        StringBuilder sb = new StringBuilder("请选择要优化的分镜（可勾选多个，也可点「✍自定义输入」填写编号，如：分镜1、分镜3）：\n");
        List<Map<String, Object>> scenePayload = new java.util.ArrayList<>();
        for (Scene s : list) {
            String num = String.valueOf(s.getSceneNumber());
            String c = s.getScriptContent();
            if (c != null && !c.isBlank()) {
                String t = c.length() > 60 ? c.substring(0, 60) + "…" : c;
                sb.append("分镜").append(s.getSceneNumber()).append("：").append(t).append("\n");
            }
            String name = "分镜 " + num
                    + (c != null && !c.isBlank() ? " · " + (c.length() > 26 ? c.substring(0, 26) + "…" : c) : "");
            scenePayload.add(Map.of("id", num, "name", name, "type", "scene"));
        }
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                sb.toString(), "scene-select",
                List.of(Map.of("content", content, "assetIds", currentAssetIds == null ? List.of() : currentAssetIds)),
                "human_input",
                List.of(Map.of("id", "scene-confirm", "title", "确认所选分镜"),
                        Map.of("id", "all-scenes", "title", "优化全部分镜"),
                        Map.of("id", "custom", "title", "✍ 自定义输入")),
                List.of(), Map.of(), Map.of(), List.of(), List.of(),
                scenePayload));
    }

    /**
     * 解析分镜选择卡的选中/输入编号：
     * null=未识别到有效分镜（重新弹选择卡）；空集=「优化全部」；非空=用户点名的分镜编号。
     */
    private java.util.Set<Integer> resolveTargetScenes(OrchestrationRequest request) {
        String action = request.getAction();
        if ("all-scenes".equals(action)) {
            return new java.util.LinkedHashSet<>(); // 空集 = 全量
        }
        java.util.Set<Integer> nums = new java.util.LinkedHashSet<>();
        // 1) 勾选的分镜（assetIds = 选中分镜编号）
        if (request.getAssetIds() != null) {
            for (String id : request.getAssetIds()) {
                if (id == null || id.isBlank()) continue;
                try { nums.add(Integer.parseInt(id.trim())); } catch (NumberFormatException ignore) {}
            }
        }
        // 2) action 形如 scene-N（单选项回传）
        if (nums.isEmpty() && action != null && action.startsWith("scene-")) {
            try { nums.add(Integer.parseInt(action.substring("scene-".length()))); } catch (NumberFormatException ignore) {}
        }
        // 3) 自定义输入文本里的「分镜N」；含「全部/所有」→ 全量
        if (nums.isEmpty() && "custom".equals(action)) {
            String ct = request.getCustomText();
            if (ct != null && !ct.isBlank()) {
                if (java.util.regex.Pattern.compile("全部|所有|全走|全优化|不分镜").matcher(ct).find()) {
                    return new java.util.LinkedHashSet<>();
                }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("分镜\\s*(\\d+)").matcher(ct);
                while (m.find()) nums.add(Integer.parseInt(m.group(1)));
            }
        }
        return nums.isEmpty() ? null : nums;
    }

    /** 关联摘要文本（方案确认卡片展示）：每镜一行「镜N：资产名、资产名」 */
    private String buildMatchSummary(List<SceneAssetMatch> matches, List<AssetVO> chosen) {
        Map<String, String> nameById = new java.util.HashMap<>();
        for (AssetVO a : chosen) nameById.put(a.id(), a.name());
        StringBuilder sb = new StringBuilder();
        for (SceneAssetMatch m : matches) {
            if (m.assetIds().isEmpty()) continue;
            List<String> names = m.assetIds().stream()
                    .map(id -> nameById.getOrDefault(id, id))
                    .toList();
            sb.append("镜").append(m.sceneNumber()).append("：").append(String.join("、", names)).append("\n");
        }
        return sb.toString();
    }

    /** 写库后按 matchScenes 结果关联 scene_assets（按分镜号匹配；无匹配跳过） */
    private void linkSceneAssetsByMatch(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        List<SceneAssetMatch> matches = support.parsePlanAssetMatches(checkpoint.getPlan());
        if (matches.isEmpty()) return;
        try {
            List<Scene> scenes = storyboardClient.getProjectScenes(
                    request.getConversation().getProjectId());
            for (Scene s : scenes) {
                matches.stream().filter(m -> m.sceneNumber() == s.getSceneNumber()).findFirst()
                        .ifPresent(m -> storyboardClient.linkSceneAssets(s.getId(), m.assetIds()));
            }
        } catch (Exception e) {
            log.warn("分镜资产自动关联失败，跳过（分镜不受影响）: {}", e.getMessage());
        }
    }

    /** 写库后按方案设计师的每镜素材绑定挂载参考图/资产到对应分镜。 */
    private void linkSceneBindings(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        List<Map<String, Object>> bindings = support.planListField(checkpoint.getPlan(), "sceneBindings");
        if (bindings.isEmpty()) return;
        log.info("[分镜] 素材绑定挂载开始: bindings={}", bindings.size());
        try {
            java.util.Map<String, String> assetIdByUrl = new java.util.HashMap<>();
            for (AssetVO a : storyboardClient.getProjectAssets(request.getConversation().getProjectId())) {
                if (a == null || a.images() == null) continue;
                for (var img : a.images()) {
                    if (img.url() != null && !img.url().isBlank()) {
                        assetIdByUrl.putIfAbsent(img.url(), a.id());
                    }
                }
            }
            List<Scene> scenes = storyboardClient.getProjectScenes(request.getConversation().getProjectId());
            for (Map<String, Object> binding : bindings) {
                int num = binding.get("sceneNumber") instanceof Number n ? n.intValue()
                        : Integer.parseInt(String.valueOf(binding.getOrDefault("sceneNumber", "0")));
                Scene scene = scenes.stream().filter(s -> s.getSceneNumber() == num).findFirst().orElse(null);
                if (scene == null) continue;
                int linkedRefs = 0;
                for (StoryboardReferenceBinding ref : parseReferenceBindings(binding.get("referenceBindings"))) {
                    if (linkedRefs >= 9) break;
                    if (ref.url() != null && !ref.url().isBlank()) {
                        Map<String, Object> link = new java.util.LinkedHashMap<>();
                        link.put("url", ref.url());
                        link.put("type", "image");
                        link.put("purpose", "video");
                        link.put("role", normalizeRoleForRef(ref.role()));
                        link.put("fileName", ref.assetId() == null ? "" : ref.assetId());
                        storyboardClient.linkSceneReferences(scene.getId(), List.of(link));
                        linkedRefs++;
                    }
                    String assetRef = ref.assetId();
                    if (assetRef != null && !assetRef.isBlank() && !assetRef.startsWith("图")) {
                        int hash = assetRef.indexOf('#');
                        if (hash > 0) assetRef = assetRef.substring(0, hash);
                        storyboardClient.linkSceneAssets(scene.getId(), List.of(assetRef));
                    } else {
                        String resolvedAssetId = assetIdByUrl.get(ref.url());
                        if (resolvedAssetId != null) {
                            storyboardClient.linkSceneAssets(scene.getId(), List.of(resolvedAssetId));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("分镜素材绑定挂载失败，跳过（分镜不受影响）: {}", e.getMessage());
        }
    }

    private List<StoryboardReferenceBinding> parseReferenceBindings(Object raw) {
        if (raw == null) return List.of();
        try {
            return objectMapper.convertValue(raw, new TypeReference<List<StoryboardReferenceBinding>>() {});
        } catch (Exception e) {
            log.warn("解析分镜素材绑定失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String normalizeRoleForRef(String role) {
        StoryboardReferenceRole r = StoryboardReferenceRole.parse(role);
        return r == null ? "REFERENCE" : r.name();
    }

    /** 回退/兜底：用户显式选择的资产关联到本批写库的分镜（已存在的关联会去重）。 */
    private void linkAllSelectedAssets(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        List<String> ids = effectiveAssetIds(request);
        if (ids.isEmpty()) return;
        List<AgentSceneItem> items = support.parsePlanScenes(checkpoint.getPlan());
        if (items.isEmpty()) return;
        try {
            List<Scene> scenes = storyboardClient.getProjectScenes(request.getConversation().getProjectId());
            for (AgentSceneItem item : items) {
                scenes.stream().filter(s -> s.getSceneNumber() == item.sceneNumber()).findFirst()
                        .ifPresent(s -> storyboardClient.linkSceneAssets(s.getId(), ids));
            }
        } catch (Exception e) {
            log.warn("分镜资产兜底关联失败，跳过（分镜不受影响）: {}", e.getMessage());
        }
    }

    /** 不满意调整上限判定：本次 +1；达上限返回 true（调用方提示后结束，不再弹卡片）并清零计数 */
    private boolean regenerateLimitReached(String conversationId) {
        int n = support.incrRegenCount(conversationId);
        if (n > agentConfig.getMaxRegenerateRounds()) {
            support.clearRegenCount(conversationId);
            return true;
        }
        return false;
    }

    /** 当前项目分镜数 */
    private long existingSceneCount(String projectId) {
        return storyboardClient.getProjectScenes(projectId).size();
    }

    /** 现有分镜文本（scriptContent 列表，供「基于现有优化」拼入 LLM 上下文） */
    private String buildExistingSceneText(String projectId) {
        var list = new java.util.ArrayList<>(storyboardClient.getProjectScenes(projectId));
        list.sort(java.util.Comparator.comparingInt(Scene::getSceneNumber));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String c = list.get(i).getScriptContent();
            if (c != null && !c.isBlank()) {
                sb.append(i + 1).append(". ").append(c).append("\n");
            }
        }
        return sb.toString();
    }

    /** 现有分镜文本（只取指定 scene_number 的分镜；供「只优化分镜N」拼入 LLM，编号保持原值） */
    private String buildExistingSceneText(String projectId, java.util.Set<Integer> targetNums) {
        var list = new java.util.ArrayList<>(storyboardClient.getProjectScenes(projectId));
        list.sort(java.util.Comparator.comparingInt(Scene::getSceneNumber));
        StringBuilder sb = new StringBuilder();
        for (Scene s : list) {
            if (targetNums.contains(s.getSceneNumber()) && s.getScriptContent() != null && !s.getScriptContent().isBlank()) {
                sb.append("【分镜").append(s.getSceneNumber()).append("】").append(s.getScriptContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 包装 AgentTools：捕获异常转 error Map（保持 handler 错误处理语义） */
    private Map<String, Object> safeWriteScenes(String projectId, List<AgentSceneItem> scenes) {
        try { return agentTools.writeScenes(projectId, scenes); }
        catch (IllegalArgumentException e) { return Map.of("ok", false, "message", e.getMessage()); }
    }
    private Map<String, Object> safeReplaceScenes(String projectId, List<AgentSceneItem> scenes) {
        try { return agentTools.replaceScenes(projectId, scenes); }
        catch (IllegalArgumentException e) { return Map.of("ok", false, "message", e.getMessage()); }
    }
    /** 包装 AgentTools.replaceSpecificScenes：部分替换（只更新指定编号，保留其他分镜） */
    private Map<String, Object> safeUpdateScenesByNumber(String projectId, List<AgentSceneItem> scenes) {
        try { return agentTools.replaceSpecificScenes(projectId, scenes); }
        catch (IllegalArgumentException e) { return Map.of("ok", false, "message", e.getMessage()); }
    }

    /**
     * 部分替换判定：方案 items 的 scene_number 集合是否为项目现有分镜编号集合的<b>真子集</b>。
     * true → 用户只针对部分分镜（如「只优化分镜9」→ 方案只有 9），写库应部分替换，保留其他分镜；
     * false → 全新创建/覆盖全部，走原有 replace/write。
     */
    private boolean isPartialSubset(List<AgentSceneItem> items, String projectId) {
        if (items == null || items.isEmpty()) return false;
        java.util.Set<Integer> itemNums = new java.util.HashSet<>();
        for (AgentSceneItem s : items) {
            itemNums.add(s.sceneNumber());
        }
        if (itemNums.isEmpty()) return false;
        java.util.Set<Integer> existingNums = new java.util.HashSet<>();
        for (Scene s : storyboardClient.getProjectScenes(projectId)) {
            existingNums.add(s.getSceneNumber());
        }
        // 真子集：方案编号非空、且方案编号 ⊆ 现有编号、且现有编号里存在方案没覆盖的（否则是全覆盖/新增）
        if (existingNums.isEmpty()) return false;
        boolean allCovered = existingNums.containsAll(itemNums);
        boolean isSubset = itemNums.size() < existingNums.size();
        return allCovered && isSubset;
    }

    /**
     * 方案 scenes（List&lt;Map&gt;）的 scene_number 集合是否为项目现有分镜编号的<b>真子集</b>。
     * 与 {@link #isPartialSubset} 判定一致，但接收计划阶段尚未转 AgentSceneItem 的 Map 列表，
     * 供确认卡覆盖警告/按钮文案区分「部分更新 vs 全量覆盖」。
     */
    private boolean isPartialPlan(List<Map<String, Object>> scenes, String projectId) {
        if (scenes == null || scenes.isEmpty()) return false;
        java.util.Set<Integer> itemNums = new java.util.HashSet<>();
        for (Map<String, Object> s : scenes) {
            if (s.get("sceneNumber") instanceof Number n) itemNums.add(n.intValue());
        }
        if (itemNums.isEmpty()) return false;
        java.util.Set<Integer> existingNums = new java.util.HashSet<>();
        for (Scene s : storyboardClient.getProjectScenes(projectId)) {
            existingNums.add(s.getSceneNumber());
        }
        if (existingNums.isEmpty()) return false;
        return existingNums.containsAll(itemNums) && itemNums.size() < existingNums.size();
    }
}
