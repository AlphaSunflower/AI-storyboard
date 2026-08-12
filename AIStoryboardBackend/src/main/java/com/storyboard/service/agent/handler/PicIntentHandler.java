package com.storyboard.service.agent.handler;

import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.service.agent.AgentTools;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.ImageRefinePromptService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * pic 图片链：有参考图 → 视觉模型改图方案 + HITL「生成图片/继续完善」→ resume 图改图；
 * 无参考图 → LLM 提示词直接文生图（自动完成，无 HITL）。
 */
@Component
@RequiredArgsConstructor
public class PicIntentHandler implements IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(PicIntentHandler.class);

    private final AgentOrchestratorSupport support;
    private final ImageRefinePromptService imageRefinePromptService;
    private final AgentTools agentTools;
    private final AiConfigProperties config;

    @Override
    public String intentType() {
        return "intent-pic";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of("generate_image", "refine");
    }

    @Override
    public String handle(OrchestrationRequest request) {
        String source = request.getPicUrl();
        try {
            if (source != null && !source.isBlank()) {
                // 1. 视觉模型看图 + 诉求 → 改图提示词（图改图方案）
                support.sendEvent(request, "workflow", Map.of("title", "正在理解图片与需求…", "status", "node_started"));
                String refinedPrompt = imageRefinePromptService.buildRefinedPrompt(source, request.getContent());
                String planText = "🖼 已结合你的参考图与需求，生成了改图方案：\n" + refinedPrompt
                        + "\n\n点击「生成图片」开始生成，或「继续完善」调整需求。";
                // 2. HITL 通用模板：方案 → checkpoint(generate_image) → human_input 事件
                //    models 过滤走 Gemini 原生接口的模型（generateContent 不支持 /images/edits multipart 图改图，实测 404）
                List<Map<String, Object>> models = support.buildModels("image").stream()
                        .filter(m -> !config.getGeminiImageModelSet().contains(String.valueOf(m.get("value"))))
                        .toList();
                return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                        planText, "generate_image",
                        List.of(Map.of("prompt", refinedPrompt, "source", source)), "human_input",
                        List.of(Map.of("id", "generate_image", "title", "生成图片"),
                                Map.of("id", "refine", "title", "继续完善"),
                                Map.of("id", "custom", "title", "✍ 自定义输入")),
                        models, Map.of(), Map.of()));
            } else {
                // 无参考图：LLM 生成图片提示词 → HITL 方案确认卡片（与有图链同构：用户确认后才生成，
                // 满足「计划形式」交互——2026-08-12 用户要求，替代原直接自动文生图）
                support.sendEvent(request, "workflow", Map.of("title", "正在设计图片方案…", "status", "node_started"));
                String prompt = support.callImagePrompt(request.getContent());
                String planText = "🖼 图片生成方案：\n" + prompt + "\n\n点击「生成图片」开始生成，或「继续完善」调整需求。";
                // models 不过滤 Gemini：文生图（mode=null）走 generations 接口，网关对 Gemini 转原生格式可用
                List<Map<String, Object>> models = support.buildModels("image");
                return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                        planText, "generate_image",
                        List.of(Map.of("prompt", prompt, "source", "")), "human_input",
                        List.of(Map.of("id", "generate_image", "title", "生成图片"),
                                Map.of("id", "refine", "title", "继续完善"),
                                Map.of("id", "custom", "title", "✍ 自定义输入")),
                        models, Map.of(), Map.of()));
            }
        } catch (Exception e) {
            log.error("PicIntentHandler.handle 失败: conversationId={}, error={}",
                    request.getConversation().getId(), e.getMessage(), e);
            return support.sendFriendlyError(request, e.getMessage(), "图片方案暂时没生成出来，请稍后重试或换个说法。");
        }
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 分支 1：继续完善 → LLM 生成修改方向选项卡片（人工介入点选，替代让用户打字）
        if ("refine".equals(request.getAction())) {
            return resumeRefineOptions(request, checkpoint);
        }
        // 分支 2：选项卡片提交（Orchestrator 特判转此）→ 合并所选方向重新设计方案卡片（先展示新方案再确认）。
        // 注意用 checkpoint.getAction() 判断：request.getAction() 此时是用户点的选项 id（opt1/custom），不是 checkpoint action
        if ("pic-option".equals(checkpoint.getAction())) {
            return resumeRefineOption(request, checkpoint);
        }
        // 分支 2.5：方案卡片的「✍ 自定义输入」（Orchestrator custom 特判转此）→ 合并自定义意见重新设计方案卡片
        if ("custom".equals(request.getAction()) && "generate_image".equals(checkpoint.getAction())) {
            return resumeRefineOption(request, checkpoint);
        }
        // 分支 3（默认 generate_image）：方案确认 → 图改图执行（checkpoint plan 存 prompt+source）
        String prompt = support.planField(checkpoint.getPlan(), "prompt");
        String source = support.planField(checkpoint.getPlan(), "source");
        // 用户提交的模型/尺寸参数（卡片选择）优先，未提交走默认（图改图默认模型=defaultImageEditModel）
        String model = request.getParams().getOrDefault("model", null);
        String size = request.getParams().getOrDefault("size", null);
        Map<String, Object> result = agentTools.refineImage(request.getConversation().getId(), prompt, source, model, size);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            String url = String.valueOf(result.get("imageUrl"));
            String content = "![生成图片](" + url + ")";
            support.resumeStage(request, "正在生成图片…", Map.of(
                    "content", content,
                    "confirm", Map.of(
                            "kind", "image", "url", url, "assetId", result.getOrDefault("assetId", ""),
                            "sceneCount", 0,
                            "actions", List.of(
                                    Map.of("id", "refine", "title", "继续完善"),
                                    Map.of("id", "done", "title", "满意完成"))),
                    "sceneCount", -1L));
            return content;
        } else {
            // 上游生成失败（如 safety 审核拒绝）：LLM 翻译成友好中文回复（提示改措辞），不直接展示英文报错
            return support.sendFriendlyError(request, String.valueOf(result.getOrDefault("message", "")),
                    "图片没生成出来，请稍后重试或调整一下描述。");
        }
    }

    /**
     * 「继续完善」：LLM 按当前方案动态生成修改方向选项（场景/服装/氛围/画风等）→ 选项卡片。
     * 用户点选或自定义输入，均走 pic-option checkpoint 续流。
     */
    private String resumeRefineOptions(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String basePrompt = support.planField(checkpoint.getPlan(), "prompt");
        String source = support.planField(checkpoint.getPlan(), "source");
        boolean hasSource = source != null && !source.isBlank();
        AgentOrchestratorSupport.PicRefineOptionsResult opt = support.callPicRefineOptions(basePrompt, hasSource);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> o : opt.options()) {
            actions.add(Map.of("id", String.valueOf(o.get("id")), "title", String.valueOf(o.get("title"))));
        }
        actions.add(Map.of("id", "custom", "title", "✍ 自定义输入"));
        String planText = "🖼 想怎么调整这张图片？\n"
                + (opt.message() == null || opt.message().isBlank() ? "" : opt.message() + "\n")
                + "\n点选修改方向，或「✍ 自定义输入」直接描述。";
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "pic-option",
                List.of(Map.of("prompt", basePrompt, "source", source == null ? "" : source,
                        "options", opt.options())),
                "human_input", actions));
    }

    /**
     * 选项卡片提交：合并所选修改方向（选项 title 或自定义文本）到原需求 → 重新设计方案卡片
     * （先展示调整后的新方案，用户确认后才生成——「不断完善方案」交互）。
     */
    private String resumeRefineOption(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String basePrompt = support.planField(checkpoint.getPlan(), "prompt");
        String source = support.planField(checkpoint.getPlan(), "source");
        // 所选方向：custom 输入优先，否则按 action id 查 plan options 的 title
        String chosen = request.getCustomText();
        if (chosen == null || chosen.isBlank()) {
            for (Map<String, Object> o : support.planListField(checkpoint.getPlan(), "options")) {
                if (String.valueOf(o.get("id")).equals(request.getAction())) {
                    chosen = String.valueOf(o.get("title"));
                    break;
                }
            }
        }
        if (chosen == null || chosen.isBlank()) {
            chosen = request.getAction();
        }
        // 合并方向重新设计提示词：有源图 → 视觉模型看图 + 新诉求；无源图 → LLM 重写
        String refinedPrompt;
        if (source != null && !source.isBlank()) {
            refinedPrompt = imageRefinePromptService.buildRefinedPrompt(source, basePrompt + "，调整方向：" + chosen);
        } else {
            refinedPrompt = support.callImagePrompt(basePrompt + "，调整方向：" + chosen);
        }
        String planText = "🖼 调整后的图片方案：\n" + refinedPrompt
                + "\n\n点击「生成图片」开始生成，或「继续完善」再次调整。";
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, "generate_image",
                List.of(Map.of("prompt", refinedPrompt, "source", source == null ? "" : source)), "human_input",
                List.of(Map.of("id", "generate_image", "title", "生成图片"),
                        Map.of("id", "refine", "title", "继续完善")),
                List.of(), Map.of(), Map.of()));
    }
}
