package com.storyboard.service.agent.handler;

import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.agent.AgentSceneItem;
import com.storyboard.service.agent.AgentTools;
import com.storyboard.service.ai.ScriptGenerationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * aisplit 分镜链：剧本优化 gate → 分镜方案 → 分镜 JSON → HITL「满意/不满意」→ resume 写库。
 *
 * <p>剧本优化与分镜方案两个 gate 均为手动澄清循环：type=0 追问并结束本轮，type=1 继续。
 * 追问升级为「选项卡片」（LLM 输出 options 数组 → human_input 事件按钮供用户点选），
 * 用户选择后经 resume(clarify-option) 把选项标题拼入需求重走对应 gate；
 * LLM 未输出 options 时退回纯文本追问（兜底，不阻塞）。
 * message 字段流式增量转发（打字机效果）。
 */
@Component
@RequiredArgsConstructor
public class AisplitIntentHandler implements IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(AisplitIntentHandler.class);

    private final AgentOrchestratorSupport support;
    private final ScriptGenerationService scriptGenerationService;
    private final AgentTools agentTools;
    private final SceneMapper sceneMapper;

    @Override
    public String intentType() {
        return "intent-aisplit";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of("agree");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        return handleFromScriptGate(request, request.getContent());
    }

    /**
     * 从「剧本优化 gate」重走完整分镜链（澄清选项补充后由 resume 调用，可重入）。
     *
     * @param content 用户需求（可为原始消息，或澄清选项补充后的拼接文本）
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

        // 4. HITL 通用模板：方案消息 → checkpoint(agree) → human_input 事件（满意/不满意）
        String planText = "📋 分镜方案（共 " + scenes.size() + " 个镜头）：\n" + support.summarizeScenes(scenes);
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "agree", scenes, "human_input",
                List.of(Map.of("id", "agree", "title", "满意"), Map.of("id", "disagree", "title", "不满意"))));
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
            support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                    questionText, "clarify-option",
                    List.of(Map.of("kind", kind, "originalContent", originalContent, "options", withCustom)),
                    "human_input", withCustom));
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

        // 执行写分镜（EXECUTE step：自动模式工具调用）；收尾事件序列走 resumeStage 模板
        List<AgentSceneItem> items = support.parsePlanScenes(checkpoint.getPlan());
        int count = agentTools.writeScenes(request.getConversation().getProjectId(), items)
                .getOrDefault("count", 0) instanceof Number n ? n.intValue() : 0;
        // sceneCount 传写库后项目分镜总数（writeScenes 为追加语义；前端用
        // sceneCount > 会话开始时数量 判断是否需要刷新分镜列表——总数才正确）
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
}
