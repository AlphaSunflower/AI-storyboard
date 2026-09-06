package com.moon.moonagent.ai.agent.specialist.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillExpander;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用技能执行器 —— 无 handler 能力的通用 LLM 工具循环。
 *
 * <p>适用：技能只有 {@code SKILL.md}（声明 tools/model_role/hitl/body 提示词）而没有
 * {@code IntentHandler} 时，由本执行器接管。复用 Spring AI 2.0 自动注册的
 * {@code ToolCallingAdvisor}（{@code ChatClient.tools(...)} 触发），不再手写工具循环。
 *
 * <p>行为定案（按 hitl 声明决定护栏）：
 * <ul>
 *   <li>技能声明了 {@code hitl} → 先弹通用确认卡（cpAction=skill-confirm，plan 携带 intent/skill），
 *       用户确认后才真正调用写工具；</li>
 *   <li>未声明 → 直接执行工具循环。</li>
 * </ul>
 * 系统提示词 = skill 描述 + body（{@code ### stepId} 分段）；历史按 {@link OtherSpecialist} 手动拼装。
 */
@Component
@RequiredArgsConstructor
public class GenericSkillSpecialist implements SpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(GenericSkillSpecialist.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    /** 动态 HITL 卡片：hitl 清单为 [{id: "dynamic", title: ...}] 时，选项由 LLM 随机生成 */
    private static final String DYNAMIC_HITL_ID = "dynamic";
    /** LLM 随机生成的选项数（第 5 项固定为「测试成功」） */
    private static final int DYNAMIC_OPTION_COUNT = 4;
    /** 固定第 5 项 id / 标题 */
    private static final String SUCCESS_ACTION_ID = "success";
    private static final String SUCCESS_LABEL = "测试成功";
    /** 通用技能多步澄清的卡 action 前缀：skill-step:<stepId> */
    private static final String STEP_ACTION_PREFIX = "skill-step:";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final AgentMessageMapper messageMapper;
    private final FileStorageService fileStorageService;
    private final SkillExpander skillExpander;
    private final SkillManager skillManager;
    private final AgentOrchestratorSupport support;

    private volatile ChatClient chatClient;

    @Override
    public String intentType() {
        return "intent-generic";
    }

    @Override
    public SpecialistResult execute(SpecialistContext context) {
        AgentSkill skill = context.skill();
        if (skill == null) {
            return SpecialistResult.done("抱歉，这个能力暂时不可用。");
        }
        OrchestrationRequest request = toRequest(context, skill);
        boolean resume = context.action() != null && !context.action().isBlank();
        try {
            if (isMultiStep(skill)) {
                // 多步澄清（flow 声明）：run 进入第 1 步；resume 恢复第 N 步继续推进
                if (!resume) {
                    return finish(context, request, SpecialistResult.done(runMultiStep(context, skill, request)));
                }
                return finish(context, request, SpecialistResult.done(resumeMultiStep(context, skill, request)));
            }
            boolean dynamicHitl = isDynamicHitl(skill);
            if (dynamicHitl) {
                // 动态 HITL：先弹卡（4 个 LLM 随机选项 + 第 5 项「测试成功」）；resume 按所选返回
                if (!resume) {
                    return finish(context, request, SpecialistResult.done(confirmDynamicAndPause(context, skill, request)));
                }
                return finish(context, request, SpecialistResult.done(resumeDynamicChoice(context, skill, request)));
            }
            if (!skill.hitlManifest().isEmpty() && !resume) {
                return finish(context, request, SpecialistResult.done(confirmAndPause(context, skill, request)));
            }
            return finish(context, request, SpecialistResult.done(executeTools(context, skill, request, resume)));
        } catch (Exception e) {
            log.error("GenericSkillSpecialist({}) 执行失败: {}", skill.intentType(), e.getMessage(), e);
            return finish(context, request, SpecialistResult.done(support.sendFriendlyError(request, e.getMessage(), "处理出了点问题，请稍后重试。")));
        }
    }

    /** 先弹确认卡（checkpoint 落库 cpAction=skill-confirm），本轮到 END 等待 resume。 */
    private String confirmAndPause(SpecialistContext context, AgentSkill skill, OrchestrationRequest request) {
        Map<String, Object> planItem = Map.of(
                "intent", skill.intentType(),
                "skill", skill.name(),
                "content", context.userMessage() == null ? "" : context.userMessage());
        String label = (skill.label() != null && !skill.label().isBlank()) ? skill.label() : skill.name();
        String cardText = "我将为你执行「" + label + "」，确认后开始：";
        AgentOrchestratorSupport.StagePlan plan = new AgentOrchestratorSupport.StagePlan(
                cardText, "skill-confirm", List.of(planItem), "human_input", skill.hitlManifest());
        return support.runHITLStage(request, null, plan);
    }

    /** 技能以 {@code hitl: [{id: dynamic, ...}]} 声明时，卡片选项由 LLM 随机生成（动态 HITL）。 */
    private boolean isDynamicHitl(AgentSkill skill) {
        if (skill.hitlManifest() == null || skill.hitlManifest().size() != 1) {
            return false;
        }
        Object id = skill.hitlManifest().getFirst().get("id");
        return DYNAMIC_HITL_ID.equals(String.valueOf(id));
    }

    /** 动态 HITL 弹卡：生成 4 个 LLM 随机选项 + 固定第 5 项「测试成功」，checkpoint 落 actions 供 resume 取标题。 */
    private String confirmDynamicAndPause(SpecialistContext context, AgentSkill skill, OrchestrationRequest request) {
        List<Map<String, Object>> actions = generateDynamicActions();
        Map<String, Object> planItem = new LinkedHashMap<>();
        planItem.put("intent", skill.intentType());
        planItem.put("skill", skill.name());
        planItem.put("content", context.userMessage() == null ? "" : context.userMessage());
        planItem.put("actions", actions);
        String cardText = "请选择一个选项（第 " + DYNAMIC_OPTION_COUNT + " 项之后为「"
                + SUCCESS_LABEL + "」）：";
        AgentOrchestratorSupport.StagePlan plan = new AgentOrchestratorSupport.StagePlan(
                cardText, "skill-confirm", List.of(planItem), "human_input", actions);
        return support.runHITLStage(request, null, plan);
    }

    /** 生成 4 个 LLM 随机选项 + 第 5 项「测试成功」卡片动作清单。 */
    private List<Map<String, Object>> generateDynamicActions() {
        List<String> titles = callLlmForRandomOptions();
        List<Map<String, Object>> actions = new ArrayList<>();
        for (int i = 0; i < titles.size() && i < DYNAMIC_OPTION_COUNT; i++) {
            actions.add(Map.of("id", "opt" + (i + 1), "title", titles.get(i)));
        }
        actions.add(Map.of("id", SUCCESS_ACTION_ID, "title", SUCCESS_LABEL));
        return actions;
    }

    /** 调 LLM 生成 4 个随机简短中文选项；失败回退默认选项。 */
    private List<String> callLlmForRandomOptions() {
        String model = gatewayModelService.getDefaultTextModel();
        try {
            String raw = getClient().prompt()
                    .options(OpenAiChatOptions.builder().model(model).timeout(TIMEOUT))
                    .system("你是测试选项生成器。请生成 " + DYNAMIC_OPTION_COUNT
                            + " 个随机的、简短的中文选项（每个 2~8 个字，内容随意、可有梗），"
                            + "只输出一个 JSON 字符串数组，例如 [\"选项一\",\"选项二\",\"选项三\",\"选项四\"]。"
                            + "不要输出任何其他内容。")
                    .user("生成 " + DYNAMIC_OPTION_COUNT + " 个随机选项")
                    .call()
                    .content();
            if (raw != null && !raw.isBlank()) {
                @SuppressWarnings("unchecked")
                List<String> parsed = objectMapper.readValue(extractJsonArray(raw), List.class);
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed.subList(0, Math.min(parsed.size(), DYNAMIC_OPTION_COUNT))
                            .stream().map(String::valueOf).toList();
                }
            }
        } catch (Exception e) {
            log.warn("测试技能：LLM 生成选项失败，回退默认选项: {}", e.getMessage());
        }
        return List.of("选项一", "选项二", "选项三", "选项四");
    }

    /** 从 LLM 原始输出中剥离首个 JSON 数组（兼容代码块/前缀文本）。 */
    private static String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    /** 动态 HITL resume：按所选选项 id 从 checkpoint.plan 取标题返回（第 5 项「测试成功」）。 */
    private String resumeDynamicChoice(SpecialistContext context, AgentSkill skill, OrchestrationRequest request) {
        String actionId = context.action() == null ? "" : context.action();
        String title = "";
        List<Map<String, Object>> actions = support.planListField(context.cpPlan(), "actions");
        if (actions != null) {
            for (Map<String, Object> action : actions) {
                if (actionId.equals(String.valueOf(action.get("id")))) {
                    title = String.valueOf(action.getOrDefault("title", ""));
                    break;
                }
            }
        }
        String reply = SUCCESS_ACTION_ID.equals(actionId)
                ? SUCCESS_LABEL
                : (title != null && !title.isBlank() ? "你选择了：" + title : "你选择了：" + actionId);
        support.sendMessage(request, reply);
        support.sendEvent(request, "message_end", Map.of(
                "messageId", "", "sceneCount", -1L, "content", reply));
        return reply;
    }

    // ===== 多步澄清（flow 驱动）=====

    /** 技能声明了 flow（非空）才启用步骤机；否则走单发逻辑。 */
    private boolean isMultiStep(AgentSkill skill) {
        return skill.flow() != null && !skill.flow().isEmpty();
    }

    /** run 入口：从 flow 第 1 步开始。 */
    private String runMultiStep(SpecialistContext context, AgentSkill skill, OrchestrationRequest request) {
        return processStep(context, skill, request, skill.flow(), 0, new ArrayList<>(), context.userMessage());
    }

    /** resume 入口：从 checkpoint.plan 恢复 step + collected，推进到下一步。 */
    private String resumeMultiStep(SpecialistContext context, AgentSkill skill, OrchestrationRequest request) {
        List<String> flow = skill.flow();
        String cpPlan = context.cpPlan() == null ? "" : context.cpPlan();
        String stepId = support.planField(cpPlan, "step");
        List<Map<String, Object>> collected = support.planListField(cpPlan, "collected");
        if (collected == null) collected = new ArrayList<>();

        String actionId = context.action() == null ? "" : context.action();
        if (!actionId.isBlank()) {
            String title = findTitleIn(support.planListField(cpPlan, "actions"), actionId);
            String value = title.isEmpty() ? actionId : title;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", stepId);
            entry.put("value", value);
            entry.put("optionId", actionId);
            collected.add(entry);
        } else {
            String custom = context.customText() == null ? "" : context.customText();
            if (!custom.isBlank()) {
                collected.add(Map.of("step", stepId, "value", custom));
            }
        }
        int index = flow.indexOf(stepId);
        int nextIndex = (index >= 0) ? index + 1 : flow.size();
        return processStep(context, skill, request, flow, nextIndex, collected, "");
    }

    /**
     * 单步处理：LLM 决定「再澄清（弹卡）」或「已明确（并入已收集 → 推进下一步）」。
     * collected 为跨步累积的答案；currentInput 仅 run 第 1 步带用户原始输入，后续步从 collected 取上下文。
     */
    private String processStep(SpecialistContext context, AgentSkill skill, OrchestrationRequest request,
                               List<String> flow, int index, List<Map<String, Object>> collected, String currentInput) {
        if (index >= flow.size()) {
            return finishFlow(context, skill, request, collected);
        }
        String stepId = flow.get(index);
        String prompt = stepPrompt(stepId, skill);
        StepResult result = callStepLlm(context, skill, request, prompt, collected, currentInput);
        if (result.needClarify()) {
            List<Map<String, Object>> actions = result.options();
            if (actions == null || actions.isEmpty()) {
                actions = List.of(Map.of("id", "yes", "title", "确定"), Map.of("id", "no", "title", "不合适"));
            }
            Map<String, Object> planItem = new LinkedHashMap<>();
            planItem.put("intent", skill.intentType());
            planItem.put("skill", skill.name());
            planItem.put("step", stepId);
            planItem.put("collected", collected);
            planItem.put("actions", actions);
            String cardText = (result.message() != null && !result.message().isBlank()) ? result.message()
                    : "第 " + (index + 1) + "/" + flow.size() + " 步：请选择";
            AgentOrchestratorSupport.StagePlan plan = new AgentOrchestratorSupport.StagePlan(
                    cardText, STEP_ACTION_PREFIX + stepId, List.of(planItem), "human_input", actions);
            return support.runHITLStage(request, null, plan);
        }
        if (result.message() != null && !result.message().isBlank()) {
            collected.add(Map.of("step", stepId, "value", result.message()));
        }
        return processStep(context, skill, request, flow, index + 1, collected, "");
    }

    /** 本步 LLM：system=该步提示词，user=已收集 + 本次输入；期望输出 needClarify/message/options。 */
    private StepResult callStepLlm(SpecialistContext context, AgentSkill skill, OrchestrationRequest request,
                                   String prompt,
                                   List<Map<String, Object>> collected, String currentInput) {
        String model = isVision(skill) ? gatewayModelService.getDefaultVisionModel()
                : gatewayModelService.getDefaultTextModel();
        StringBuilder sys = new StringBuilder();
        if (prompt != null && !prompt.isBlank()) sys.append(prompt).append("\n\n");
        sys.append("请只输出一个 JSON 对象：{\"needClarify\":true|false,\"message\":\"给用户的话\","
                + "\"options\":[{\"id\":\"opt1\",\"title\":\"...\"}]}。needClarify=true 表示还需用户选择/补充"
                + "（options 给出 2~4 个可点选项）；false 表示本步已明确，进入下一步。");
        StringBuilder user = new StringBuilder();
        String collectedText = summarizeCollected(collected);
        if (!collectedText.isBlank()) user.append(collectedText);
        if (currentInput != null && !currentInput.isBlank()) {
            if (user.length() > 0) user.append("\n\n");
            user.append("用户本次输入: ").append(currentInput);
        }
        if (user.length() == 0) {
            user.append(context.userMessage() == null ? "" : context.userMessage());
        }
        OffsetDateTime started = OffsetDateTime.now();
        ChatResponse response = getClient().prompt()
                .options(OpenAiChatOptions.builder().model(model).timeout(TIMEOUT))
                .system(sys.toString())
                .user(user.toString())
                .call()
                .chatResponse();
        AssistantMessage output = response != null && response.getResult() != null
                ? response.getResult().getOutput() : null;
        String raw = output == null ? "" : (output.getText() == null ? "" : output.getText());
        publishReasoning(context, request, output, model, started);
        return parseStepResult(raw);
    }

    /** 非流式响应保存真实推理摘要；模型无可见推理时给用户一个可展开的完成摘要。 */
    private void publishReasoning(SpecialistContext context, OrchestrationRequest request,
                                  AssistantMessage output, String model, OffsetDateTime started) {
        String reason = extractReasoning(output == null ? null : output.getMetadata());
        String fallback = "已完成当前步骤的判断。";
        String reasonText = reason == null || reason.isBlank() ? fallback : reason;
        Long durationMs = Duration.between(started, OffsetDateTime.now()).toMillis();
        AgentReasoningSnapshot snapshot = new AgentReasoningSnapshot(
                summarize(reasonText), reasonText, durationMs,
                reason == null || reason.isBlank() ? "trace-summary" : model);
        request.setReasoning(snapshot);
        if (request.getTrace() != null) request.getTrace().reasoning(snapshot);
        if (context.emitter() != null) {
            try {
                context.emitter().send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                        .name("reasoning_delta").data(AgentOrchestratorSupport.sseJson(Map.of(
                                "details", reasonText,
                                "summary", snapshot.summary(),
                                "durationMs", durationMs,
                                "source", snapshot.source()))));
            } catch (Exception e) {
                log.debug("GenericSkill reasoning 发送失败: error={}", e.getMessage());
            }
        }
    }

    private String extractReasoning(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        for (String key : new String[]{"reasoning_content", "reasoningContent", "thoughts", "thinking"}) {
            Object value = metadata.get(key);
            if (value == null) continue;
            String text = value instanceof String s ? s : String.valueOf(value);
            if (text != null && !text.isBlank()) return text;
        }
        return null;
    }

    private String summarize(String text) {
        if (text == null || text.isBlank()) return "";
        String trimmed = text.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }

    private String stepPrompt(String stepId, AgentSkill skill) {
        String p = skill.nodePrompts().get(stepId);
        return (p != null && !p.isBlank()) ? p : skill.systemPrompt();
    }

    private static String summarizeCollected(List<Map<String, Object>> collected) {
        if (collected == null || collected.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("已收集信息：\n");
        for (Map<String, Object> entry : collected) {
            sb.append("- ").append(entry.getOrDefault("step", "?")).append(": ")
                    .append(entry.getOrDefault("value", "")).append("\n");
        }
        return sb.toString().trim();
    }

    private String findTitleIn(List<Map<String, Object>> actions, String actionId) {
        if (actions == null) return "";
        for (Map<String, Object> a : actions) {
            if (actionId.equals(String.valueOf(a.get("id")))) {
                return String.valueOf(a.getOrDefault("title", ""));
            }
        }
        return "";
    }

    /** flow 走完的收尾：demo 技能返回「测试成功」；真实技能可在此改造成执行工具。 */
    private String finishFlow(SpecialistContext context, AgentSkill skill, OrchestrationRequest request,
                              List<Map<String, Object>> collected) {
        String finalMsg = SUCCESS_LABEL
                + (collected != null && !collected.isEmpty() ? "（已收集 " + collected.size() + " 项信息）" : "");
        support.sendMessage(request, finalMsg);
        support.sendEvent(request, "message_end", Map.of(
                "messageId", "", "sceneCount", -1L, "content", finalMsg));
        return finalMsg;
    }

    private StepResult parseStepResult(String raw) {
        if (raw == null || raw.isBlank()) return new StepResult(true, "", List.of());
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            boolean need = root.has("needClarify") ? root.get("needClarify").asBoolean(true) : true;
            String message = root.path("message").asText("");
            List<Map<String, Object>> options = new ArrayList<>();
            if (root.path("options").isArray()) {
                for (JsonNode o : root.path("options")) {
                    options.add(objectMapper.convertValue(o, Map.class));
                }
            }
            return new StepResult(need, message, options);
        } catch (Exception e) {
            log.warn("多步澄清：LLM 输出解析失败，按需要澄清处理: {}", e.getMessage());
            return new StepResult(true, "", List.of());
        }
    }

    /** 剥离 LLM 输出中的首个 JSON 对象（兼容代码块/前缀文本）。 */
    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    private record StepResult(boolean needClarify, String message, List<Map<String, Object>> options) {}

    /** 工具循环执行（resume 时在系统提示词追加「已确认，请执行」语义）。 */
    private String executeTools(SpecialistContext context, AgentSkill skill, OrchestrationRequest request,
                                boolean resume) {
        String systemPrompt = skill.systemPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "你是 Moon 智能体的技能执行器，用可用的工具完成用户需求，并给出简洁的中文结果。";
        }
        if (resume) {
            systemPrompt = systemPrompt
                    + "\n\n用户已确认上述操作。请继续执行，调用所需工具完成该需求，并给出结果。";
        }

        String model = isVision(skill) ? gatewayModelService.getDefaultVisionModel()
                : gatewayModelService.getDefaultTextModel();
        ChatClient client = getClient();
        ToolCallback[] tools = skillExpander.toolCallbacks(skill.tools());

        String text = buildHistoryText(context) + "\n\n用户: " + context.userMessage();
        UserMessage userMessage = buildUserMessage(text, context.picUrls(), isVision(skill));

        OffsetDateTime started = OffsetDateTime.now();
        ChatResponse response = client.prompt()
                .options(OpenAiChatOptions.builder().model(model).timeout(TIMEOUT))
                .system(systemPrompt)
                .messages(userMessage)
                .tools((Object[]) tools)
                .call()
                .chatResponse();
        AssistantMessage output = response != null && response.getResult() != null
                ? response.getResult().getOutput() : null;
        String answer = output == null ? "" : (output.getText() == null ? "" : output.getText());
        publishReasoning(context, request, output, model, started);
        String content = answer == null ? "" : answer;
        support.sendMessage(request, content);
        support.sendEvent(request, "message_end", Map.of(
                "messageId", "", "sceneCount", -1L, "content", content));
        return content;
    }

    private boolean isVision(AgentSkill skill) {
        return "vision".equalsIgnoreCase(skill.modelRole());
    }

    /** 手动拼历史（对齐 OtherSpecialist：最近 20 条，时间升序） */
    private String buildHistoryText(SpecialistContext context) {
        try {
            List<AgentMessage> recent = messageMapper.selectList(
                    new LambdaQueryWrapper<AgentMessage>()
                            .eq(AgentMessage::getConversationId, context.conversation().getId())
                            .orderByDesc(AgentMessage::getCreatedAt)
                            .last("LIMIT 20")).reversed();
            StringBuilder history = new StringBuilder();
            for (AgentMessage msg : recent) {
                history.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }
            return history.toString().trim();
        } catch (Exception e) {
            log.warn("GenericSkillSpecialist 拼历史失败: {}", e.getMessage());
            return "";
        }
    }

    /** 构造用户消息：vision 角色且有参考图时循环读本地图转 data URI 作为多模态图片；失败降级纯文本。 */
    private UserMessage buildUserMessage(String text, List<String> picUrls, boolean vision) {
        if (vision && picUrls != null && !picUrls.isEmpty()) {
            try {
                var builder = UserMessage.builder().text(text);
                List<Media> medias = new ArrayList<>();
                for (String picUrl : picUrls) {
                    if (picUrl == null || picUrl.isBlank()) continue;
                    String dataUri = fileStorageService.toImageDataUri(picUrl);
                    int sep = dataUri.indexOf(';');
                    String mime = dataUri.startsWith("data:") && sep > 5 ? dataUri.substring(5, sep) : "image/png";
                    medias.add(Media.builder().mimeType(MimeType.valueOf(mime)).data(dataUri).build());
                }
                if (!medias.isEmpty()) {
                    return builder.media(medias).build();
                }
            } catch (Exception e) {
                log.warn("GenericSkillSpecialist 参考图加入主回答失败，降级纯文本: {}", e.getMessage());
            }
        }
        return UserMessage.builder().text(text).build();
    }

    private OrchestrationRequest toRequest(SpecialistContext context, AgentSkill skill) {
        OrchestrationRequest request = new OrchestrationRequest(
                context.conversation(), context.userMessage(), context.picUrls(), context.emitter());
        request.setIntentType(skill.intentType());
        request.setAction(context.action() == null ? "" : context.action());
        request.setCustomText(context.customText() == null ? "" : context.customText());
        request.setParams(context.params() == null ? Map.of() : context.params());
        request.setAssetIds(context.assetIds() == null ? java.util.List.of() : context.assetIds());
        request.setTrace(context.trace());
        return request;
    }

    private SpecialistResult finish(SpecialistContext context, OrchestrationRequest request, SpecialistResult result) {
        if (context.trace() != null) {
            context.trace().setTurnMetadata(request.getTurnMetadata());
            context.trace().setHitlPaused(request.isHitlPaused());
            context.trace().setHitlMessageId(request.getHitlMessageId());
            context.trace().setSuggestedActions(request.getSuggestedActions());
            context.trace().reasoning(request.getReasoning());
        }
        return result;
    }

    private ChatClient getClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder
                            .defaultAdvisors(new SimpleLoggerAdvisor())
                            .build();
                }
            }
        }
        return chatClient;
    }
}
