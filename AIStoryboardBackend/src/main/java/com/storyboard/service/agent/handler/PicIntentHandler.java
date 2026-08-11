package com.storyboard.service.agent.handler;

import com.storyboard.entity.AgentCheckpoint;
import com.storyboard.service.agent.AgentTools;
import com.storyboard.service.ai.ImageRefinePromptService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    @Override
    public String intentType() {
        return "intent-pic";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of("generate_image");
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
                // 2. HITL 通用模板：方案 → checkpoint(generate_image) → human_input 事件（带网关图片模型选项，卡片可选模型/尺寸）
                List<Map<String, Object>> models = support.buildModels("image");
                return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                        planText, "generate_image",
                        List.of(Map.of("prompt", refinedPrompt, "source", source)), "human_input",
                        List.of(Map.of("id", "generate_image", "title", "生成图片"),
                                Map.of("id", "refine", "title", "继续完善")),
                        models, Map.of(), Map.of()));
            } else {
                // 无参考图：LLM 生成图片提示词 → 直接文生图（自动完成，无 HITL）
                support.sendEvent(request, "workflow", Map.of("title", "正在生成图片…", "status", "node_started"));
                String prompt = support.callImagePrompt(request.getContent());
                Map<String, Object> result = agentTools.refineImage(request.getConversation().getId(), prompt, null, null, null);
                if (Boolean.TRUE.equals(result.get("ok"))) {
                    String url = String.valueOf(result.get("imageUrl"));
                    String content = "![生成图片](" + url + ")";
                    support.sendMessage(request, content);
                    support.sendEvent(request, "confirm_result", Map.of(
                            "kind", "image", "url", url, "assetId", result.getOrDefault("assetId", ""),
                            "sceneCount", 0,
                            "actions", List.of(
                                    Map.of("id", "refine", "title", "继续完善"),
                                    Map.of("id", "done", "title", "满意完成"))));
                    support.sendEvent(request, "message_end", Map.of(
                            "messageId", "", "sceneCount", -1L, "content", content));
                    return content;
                } else {
                    support.sendEvent(request, "error", Map.of("code", "50202",
                            "message", String.valueOf(result.getOrDefault("message", "图片生成失败，请稍后重试"))));
                    return "";
                }
            }
        } catch (Exception e) {
            log.error("PicIntentHandler.handle 失败: conversationId={}, error={}",
                    request.getConversation().getId(), e.getMessage(), e);
            support.sendEvent(request, "error", Map.of("code", "50202", "message", "图片方案生成失败，请稍后重试"));
            return "";
        }
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 图片方案确认：图改图执行（checkpoint plan 存 prompt+source）；收尾事件序列走 resumeStage 模板
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
            support.sendEvent(request, "error", Map.of("code", "50202",
                    "message", String.valueOf(result.getOrDefault("message", "图片生成失败，请稍后重试"))));
            return "";
        }
    }
}
