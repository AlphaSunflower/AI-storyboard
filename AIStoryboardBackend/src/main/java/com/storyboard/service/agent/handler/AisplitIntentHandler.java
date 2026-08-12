package com.storyboard.service.agent.handler;

import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.agent.AgentSceneItem;
import com.storyboard.service.agent.AgentTools;
import com.storyboard.service.ai.AgentAiConfigProperties;
import com.storyboard.service.ai.ScriptGenerationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final SceneMapper sceneMapper;
    private final AgentAiConfigProperties agentConfig;

    /**
     * 不满意调整计数：conversationId → 连续「不满意→调整→重生成」轮次。
     * 达 {@code maxRegenerateRounds} 上限后不再弹调整卡片，提示直接输入新需求；
     * 写库成功（agree/replace/append）或达上限清零。
     * ponytail: 内存态重启即失（无害）；多实例需落 DB 列。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> regenCount =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        // 现有分镜检测只在 handle 入口做一次——handleFromScriptGate 会被 scene-mode/scene-regenerate
        // resume 重入，放那里会二次弹卡片死循环
        long existing = existingSceneCount(request.getConversation().getProjectId());
        if (existing > 0) {
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    "检测到您当前项目已有 " + existing + " 个分镜，如何处理？",
                    "scene-mode",
                    List.of(Map.of("content", request.getContent(), "existingCount", existing)),
                    "human_input",
                    List.of(
                            Map.of("id", "scene-mode-optimize", "title", "基于现有分镜进一步优化"),
                            Map.of("id", "scene-mode-fresh", "title", "额外创建全新的分镜内容"),
                            Map.of("id", "scene-mode-cancel", "title", "本次不生成分镜"))));
        }
        return handleFromScriptGate(request, request.getContent());
    }

    /**
     * 从「剧本优化 gate」重走完整分镜链（澄清选项/调整意见补充后由 resume 调用，可重入）。
     *
     * @param content 用户需求（可为原始消息，或澄清选项/调整意见补充后的拼接文本）
     */
    public String handleFromScriptGate(OrchestrationRequest request, String content) {
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
        return handleFromPlanGate(request, script);
    }

    /**
     * 从「分镜方案 gate」重走（剧本已定，澄清选项补充后由 resume 调用，可重入）。
     *
     * @param script 已定剧本
     */
    public String handleFromPlanGate(OrchestrationRequest request, String script) {
        // 2. 分镜方案设计（结构化 message，流式增量转发）
        support.sendEvent(request, "workflow", Map.of("title", "正在设计分镜方案…", "status", "node_started"));
        AgentOrchestratorSupport.StoryboardPlanResult plan = support.callStoryboardPlan(script, request);
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

        // 3. 分镜 JSON（复用 ScriptGenerationService：LLM 生成 8 字段分镜列表）
        support.sendEvent(request, "workflow", Map.of("title", "正在生成分镜…", "status", "node_started"));
        List<Map<String, Object>> scenes = scriptGenerationService.generateScenes(
                request.getConversation().getProjectId(), script, "movie", null, "16:9", null);
        if (scenes == null || scenes.isEmpty()) {
            support.sendMessage(request, "⚠ 未能生成分镜内容，请重新描述需求。");
            return request.getLastMessage();
        }

        // 4. HITL 通用模板：方案消息 → checkpoint(agree) → human_input 事件。
        // 已有分镜 → 选项变为写库策略（replace/append/disagree/cancel）+ 覆盖警告；无 → 现状（满意/不满意）
        long existing = existingSceneCount(request.getConversation().getProjectId());
        List<Map<String, Object>> actions = existing > 0
                ? List.of(
                    Map.of("id", "replace", "title", "先删除现有分镜再导入"),
                    Map.of("id", "append", "title", "追加到现有分镜后面"),
                    Map.of("id", "disagree", "title", "不满意，重新生成"),
                    Map.of("id", "cancel", "title", "不生成分镜"))
                : List.of(Map.of("id", "agree", "title", "满意"), Map.of("id", "disagree", "title", "不满意"));
        String planText = "📋 分镜方案（共 " + scenes.size() + " 个镜头）：\n" + support.summarizeScenes(scenes);
        if (existing > 0) {
            // 覆盖导入会删除现有分镜及其产出素材——卡片正文前置警告
            planText = "⚠ 检测到您当前已有 " + existing + " 个分镜（含已生成的图片/视频素材），覆盖导入将全部删除。\n" + planText;
        }
        // 5. 整套统一推荐一套生成参数（图片+视频，LLM 预选 + 用户可改；推荐失败静默回退，卡片照常）
        AgentOrchestratorSupport.SceneParamsRecommendation rec = support.recommendSceneParams(script, request);
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "agree", scenes, "human_input", actions,
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
        // 澄清选项续流：所选选项标题拼入需求，重走对应 gate（kind=script 从剧本优化重走；plan 从分镜方案重走）。
        // action=custom（自定义输入）：直接用用户输入文本作为补充，不查 options 表
        if ("clarify-option".equals(checkpoint.getAction())) {
            String kind = support.planField(checkpoint.getPlan(), "kind");
            String original = support.planField(checkpoint.getPlan(), "originalContent");
            String chosenId = request.getAction();
            String title = "custom".equals(chosenId)
                    ? request.getCustomText()
                    : support.planListField(checkpoint.getPlan(), "options").stream()
                            .filter(o -> chosenId.equals(o.get("id")))
                            .map(o -> String.valueOf(o.getOrDefault("title", "")))
                            .findFirst().orElse("");
            String supplemented = title.isBlank() ? original : original + "\n（补充：" + title + "）";
            return "plan".equals(kind)
                    ? handleFromPlanGate(request, supplemented)
                    : handleFromScriptGate(request, supplemented);
        }

        // 卡片1：分镜处理方式（scene-mode）——基于现有优化 / 全新创建 / 不生成
        if ("scene-mode".equals(checkpoint.getAction())) {
            String action = request.getAction();
            String original = support.planField(checkpoint.getPlan(), "content");
            if ("scene-mode-cancel".equals(action)) {
                return endWithoutScenes(request, "好的，本次不生成分镜。需要时可以随时再让我生成。");
            }
            if ("scene-mode-optimize".equals(action)) {
                // 基于现有分镜优化：现有分镜文本 + 原始需求拼入剧本优化 gate 输入
                String existing = buildExistingSceneText(request.getConversation().getProjectId());
                String supplemented = "【现有分镜】\n" + existing
                        + "\n【用户需求】\n" + original
                        + "\n请在保留现有分镜合理结构的基础上，按用户需求优化并生成新的分镜方案。";
                return handleFromScriptGate(request, supplemented);
            }
            // scene-mode-fresh：全新创建，正常流程
            return handleFromScriptGate(request, original);
        }

        // 调整意见（scene-regenerate）：上一轮方案文本 + 用户意见 → 重走生成链（LLM 基于意见重新优化）
        if ("scene-regenerate".equals(checkpoint.getAction())) {
            String prevPlan = support.planField(checkpoint.getPlan(), "content");
            String opinion = request.getCustomText();
            String supplemented = prevPlan + "\n【用户调整意见】\n"
                    + (opinion == null || opinion.isBlank() ? "请重新生成一版更合适的方案" : opinion);
            return handleFromScriptGate(request, supplemented);
        }

        // 方案确认卡片（checkpoint action=agree）写库策略分发：replace=覆盖 / append=追加（现状）
        // / cancel=不写 / disagree=不满意→调整意见卡片；agree（无现有分镜）语义=新建，行为与现状一致
        String chosen = request.getAction();
        if ("cancel".equals(chosen)) {
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
        // 执行写分镜（EXECUTE step）；replace=同事务清空现有再写（覆盖）/ 其余=追加（现状）
        List<AgentSceneItem> items = support.parsePlanScenes(checkpoint.getPlan());
        Map<String, Object> writeResult = "replace".equals(chosen)
                ? agentTools.replaceScenes(request.getConversation().getProjectId(), items)
                : agentTools.writeScenes(request.getConversation().getProjectId(), items);
        int count = writeResult.getOrDefault("count", 0) instanceof Number n ? n.intValue() : 0;
        // 写库成功：不满意调整计数清零（新一轮生成起点）+ 用户确认的整套推荐参数应用到全部分镜
        regenCount.remove(request.getConversation().getId());
        if (count > 0) {
            support.applySceneParamsToProject(request.getConversation().getProjectId(), request.getParams());
        }
        // sceneCount 传写库后项目分镜总数（writeScenes 为追加语义；前端用
        // sceneCount > 会话开始时数量 判断是否需要刷新分镜列表——总数才正确，replace 后即新数量）
        long totalScenes = sceneMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.Scene>()
                        .eq(com.storyboard.entity.Scene::getProjectId, request.getConversation().getProjectId()));
        String msg = count > 0
                ? "✅ 分镜方案已确认，已生成 **" + count + " 个分镜**，请查看左侧分镜列表"
                : "⚠ 分镜方案已确认，但未解析到分镜内容，请重新描述需求";
        support.resumeStage(request, "正在写入分镜…", Map.of(
                "content", msg,
                "confirm", Map.of("kind", "script", "sceneCount", totalScenes, "url", "", "actions", List.of()),
                "sceneCount", totalScenes));
        return msg;
    }

    /** 不写库收尾：message + message_end（sceneCount=-1，前端不触发分镜列表刷新） */
    private String endWithoutScenes(OrchestrationRequest request, String msg) {
        support.sendMessage(request, msg);
        support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", msg));
        return msg;
    }

    /** 不满意调整上限判定：本次 +1；达上限返回 true（调用方提示后结束，不再弹卡片）并清零计数 */
    private boolean regenerateLimitReached(String conversationId) {
        int n = regenCount.merge(conversationId, 1, Integer::sum);
        if (n > agentConfig.getMaxRegenerateRounds()) {
            regenCount.remove(conversationId);
            return true;
        }
        return false;
    }

    /** 当前项目分镜数 */
    private long existingSceneCount(String projectId) {
        return sceneMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.Scene>()
                        .eq(com.storyboard.entity.Scene::getProjectId, projectId));
    }

    /** 现有分镜文本（scriptContent 列表，供「基于现有优化」拼入 LLM 上下文） */
    private String buildExistingSceneText(String projectId) {
        var list = sceneMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.Scene>()
                        .eq(com.storyboard.entity.Scene::getProjectId, projectId)
                        .orderByAsc(com.storyboard.entity.Scene::getSceneNumber));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String c = list.get(i).getScriptContent();
            if (c != null && !c.isBlank()) {
                sb.append(i + 1).append(". ").append(c).append("\n");
            }
        }
        return sb.toString();
    }
}
