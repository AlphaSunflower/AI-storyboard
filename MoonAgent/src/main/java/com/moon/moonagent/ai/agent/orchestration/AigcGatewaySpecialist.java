package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentTraceEvent;
import com.moon.moonagent.ai.agent.PlanField;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillExpander;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.orchestration.MediaToolResult;
import com.moon.moonagent.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Skill 驱动的 AIGC 执行器。
 *
 * <p>初次请求先读取完整 SKILL.md，由模型产出方案和参数并暂停 HITL；
 * 用户确认后再调用 Skill 声明的 generate_image / generate_video MCP 工具。
 * 不委托 PicIntentHandler / VideoIntentHandler。
 */
@Component
@RequiredArgsConstructor
public class AigcGatewaySpecialist {

    private static final Logger log = LoggerFactory.getLogger(AigcGatewaySpecialist.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService PLAN_WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "aigc-plan-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private static final String PLAN_TEXT_START = "<PLAN_TEXT>";
    private static final String PLAN_TEXT_END = "</PLAN_TEXT>";
    private static final String PLAN_JSON_START = "<PLAN_JSON>";
    private static final String PLAN_JSON_END = "</PLAN_JSON>";

    private final ChatClient.Builder chatClientBuilder;
    private final GatewayModelService gatewayModelService;
    private final FileStorageService fileStorageService;
    private final SkillExpander skillExpander;
    private final MediaGenerationCoordinator coordinator;
    private final AgentOrchestratorSupport support;

    @Value("${ai.agent.media-plan-slow-notice-seconds:60}")
    private long mediaPlanSlowNoticeSeconds = 60;

    @Value("${ai.agent.media-plan-timeout-seconds:120}")
    private long mediaPlanTimeoutSeconds = 120;

    private volatile ChatClient chatClient;

    public SpecialistResult execute(String intent, SpecialistContext context) {
        String toolName = "intent-video".equals(intent) ? "generate_video" : "generate_image";
        if ("cancel".equals(context.action())) {
            return SpecialistResult.done("已取消媒体生成。");
        }
        if (context.directExecution()) {
            return executeConversational(intent, toolName, context);
        }
        if (!context.directExecution() && !isConfirmed(context)) {
            AgentSkill skill = context.skill();
            if (skill == null) return SpecialistResult.done("当前媒体 Skill 未加载，请检查 Skill 配置。");
            return SpecialistResult.done(planAndPause(intent, toolName, context, toRequest(context, skill)));
        }
        return executeTool(intent, toolName, context);
    }

    private SpecialistResult executeConversational(String intent, String toolName, SpecialistContext context) {
        if (isConfirmed(context)) {
            return executeTool(intent, toolName, context);
        }
        if (context.action() != null && context.action().startsWith("select_")) {
            return SpecialistResult.done(confirmSelectedPlan(intent, toolName, context));
        }
        AgentSkill skill = context.skill();
        if (skill == null) return SpecialistResult.done("当前媒体 Skill 未加载，请检查 Skill 配置。");
        Map<String, Object> plan = callSkillPlan(intent, skill, context, toRequest(context, skill));
        List<Map<String, Object>> plans = planList(plan.get("plans"));
        String display = stringValue(plan.get("_display"));
        if (Boolean.TRUE.equals(plan.get("_timedOut"))) {
            String timeoutMessage = display.isBlank()
                    ? "方案生成超过 " + Math.max(1, mediaPlanTimeoutSeconds) + " 秒，模型响应较慢。"
                    + "请回复“继续”让我重试，或简化一下本次需求。"
                    : display + "\n\n方案生成超过 " + Math.max(1, mediaPlanTimeoutSeconds)
                    + " 秒，已保留以上内容。请回复“继续”让我重试，或简化一下本次需求。";
            Map<String, Object> timeoutState = new LinkedHashMap<>(plan);
            timeoutState.put("intent", skill.intentType());
            timeoutState.put("skill", skill.name());
            timeoutState.put("toolName", toolName);
            timeoutState.put("plansText", display);
            timeoutState.put("originalMessage", context.userMessage() == null ? "" : context.userMessage());
            return SpecialistResult.question(timeoutMessage, timeoutState);
        }
        if (display.isBlank()) display = planText(toolName, plan);
        if (!display.isBlank() && !display.contains("请回复")) {
            display = display + "\n\n请回复 1/2/3 选择方案，或直接说明需要如何继续完善。";
        }
        Map<String, Object> questionState = new LinkedHashMap<>(plan);
        questionState.put("intent", skill.intentType());
        questionState.put("skill", skill.name());
        questionState.put("toolName", toolName);
        questionState.put("plansText", display);
        Map<String, Object> previousQuestion = parsePlan(context.cpPlan());
        String originalMessage = stringValue(previousQuestion.get("originalMessage"));
        if (originalMessage.isBlank()) originalMessage = context.userMessage() == null ? "" : context.userMessage();
        questionState.put("originalMessage", originalMessage);
        questionState.put("plans", plans);
        return SpecialistResult.question(display, questionState);
    }

    private String confirmSelectedPlan(String intent, String toolName, SpecialistContext context) {
        Map<String, Object> question = parsePlan(context.cpPlan());
        List<Map<String, Object>> plans = planList(question.get("plans"));
        int index = selectedPlanIndex(context.action());
        int planIndex = Math.max(0, Math.min(plans.size() - 1, index - 1));
        Map<String, Object> selected = plans.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(plans.get(planIndex));
        String plansText = stringValue(question.get("plansText"));
        String selectedText = extractSelectedPlanText(plansText, index);
        String originalMessage = stringValue(question.get("originalMessage"));
        String title = stringValue(selected.get("title"));
        if (title.isBlank()) title = "方案" + index;
        String summary = stringValue(selected.get("summary"));
        String body = "generate_video".equals(toolName)
                ? stringValue(selected.get("message"))
                : stringValue(selected.get("prompt"));
        if (body.isBlank()) body = selectedText;
        if (body.isBlank()) body = originalMessage;
        if (!"generate_video".equals(toolName) && !body.endsWith("，风格方向：" + title)) {
            body = body + "，采用方案" + index + "：" + title;
        }
        selected.put("title", title);
        selected.put("summary", summary);
        if ("generate_video".equals(toolName)) {
            selected.put("message", body);
        } else {
            selected.put("prompt", body);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", intent);
        payload.put("skill", question.getOrDefault("skill", ""));
        payload.put("content", originalMessage);
        payload.put("plans", List.of(selected));
        String cardText = "已选择方案" + index + "：" + title
                + (summary.isBlank() ? "" : "\n" + summary)
                + "\n\n点击「开始生成」提交图片/视频任务，或点击「继续完善」返回对话调整方案。";
        List<Map<String, Object>> actions = List.of(
                Map.of("id", toolName + ":" + index, "title", "开始生成"),
                Map.of("id", "refine", "title", "继续完善"),
                Map.of("id", "cancel", "title", "取消"));
        OrchestrationRequest request = toRequest(context, context.skill());
        request.setAction(context.action());
        String hitlText = support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                cardText, toolName, List.of(payload), "human_input", actions,
                List.of(), Map.of(), Map.of()));
        if (context.trace() != null) {
            context.trace().setHitlPaused(request.isHitlPaused());
            context.trace().setHitlMessageId(request.getHitlMessageId());
        }
        return hitlText;
    }

    private static String extractSelectedPlanText(String plansText, int index) {
        if (plansText == null || plansText.isBlank()) return "";
        String marker = "【方案" + index;
        int start = plansText.indexOf(marker);
        if (start < 0) return plansText.trim();
        int next = plansText.indexOf("【方案", start + marker.length());
        return (next < 0 ? plansText.substring(start) : plansText.substring(start, next)).trim();
    }

    private SpecialistResult executeTool(String intent, String toolName, SpecialistContext context) {
        if (!skillExpander.hasTool(toolName)) {
            return SpecialistResult.failed("媒体 MCP 工具尚未就绪，请确认 LLM 网关已启动并完成连接。");
        }
        ToolCallback[] callbacks = skillExpander.toolCallbacks(List.of(toolName));
        if (callbacks.length == 0) {
            return SpecialistResult.failed("媒体 MCP 工具尚未就绪，请确认 LLM 网关已启动并完成连接。");
        }

        AtomicReference<MediaToolResult> toolResult = new AtomicReference<>();
        ToolCallback tool = new MediaToolCallback(
                callbacks[0], coordinator, context.conversation(), toolResult::set);
        OffsetDateTime started = OffsetDateTime.now();
        try {
            String input = JSON.writeValueAsString(buildToolInput(toolName, context));
            tool.call(input);
            MediaToolResult result = toolResult.get();
            if (result == null) {
                return SpecialistResult.failed("媒体生成失败：工具未返回结果。");
            }
            if (!isBlank(result.error())) {
                return SpecialistResult.failed("媒体生成失败：" + result.error());
            }
            if ("generate_image".equals(toolName) && result.resultUrls().isEmpty()) {
                return SpecialistResult.failed("媒体生成失败：未返回图片结果。");
            }
            if ("generate_video".equals(toolName) && isBlank(result.taskId())) {
                return SpecialistResult.failed("媒体生成失败：未返回视频任务 ID。");
            }
            String answer = formatAnswer(toolName, result);
            emitVideoAccepted(context, result);
            emit(context, answer);
            if (context.trace() != null) {
                context.trace().add(new AgentTraceEvent(UUID.randomUUID().toString(),
                        "aigc_gateway", "moon", toolName, "node_completed",
                        "媒体工具执行", "", intent, toolName, OffsetDateTime.now(),
                        Duration.between(started, OffsetDateTime.now()).toMillis()));
            }
            return SpecialistResult.done(answer);
        } catch (Exception e) {
            log.error("AIGC gateway specialist failed: intent={}, error={}", intent, e.getMessage(), e);
            return SpecialistResult.failed("媒体生成失败：" + e.getMessage());
        }
    }

    /**
     * 先按 SKILL.md 生成方案，不发工具调用，不提前进入 MCP。
     */
    private String planAndPause(String intent, String toolName, SpecialistContext context,
                                OrchestrationRequest request) {
        AgentSkill skill = context.skill();
        if (skill == null) return "当前媒体 Skill 未加载，请检查 Skill 配置。";

        Map<String, Object> plan = callSkillPlan(intent, skill, context, request);
        List<Map<String, Object>> plans = planList(plan.get("plans"));
        String primaryAction = "generate_video".equals(toolName) ? "generate_video" : "generate_image";
        List<Map<String, Object>> actions = mediaActions(toolName, plans.size());
        String streamedText = stringValue(plan.get("_display"));
        String planText = streamedText.isBlank() ? planText(toolName, plan) : streamedText;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", skill.intentType());
        payload.put("skill", skill.name());
        payload.put("content", context.userMessage() == null ? "" : context.userMessage());
        payload.put("picUrls", context.picUrls() == null ? List.of() : context.picUrls());
        payload.put("plans", plans);

        String eventName = "generate_video".equals(toolName) ? "video_plan" : "human_input";
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                planText, primaryAction, List.of(payload), eventName, actions,
                List.of(), Map.of(), Map.of()), !streamedText.isBlank());
    }

    /**
     * 只读取 SKILL.md 做方案，不提供任何工具，保证初始化阶段不会直接出生成工具。
     */
    private Map<String, Object> callSkillPlan(String intent, AgentSkill skill, SpecialistContext context,
                                              OrchestrationRequest request) {
        List<String> sources = context.picUrls() == null ? List.of() : context.picUrls();
        boolean vision = !sources.isEmpty() || "vision".equalsIgnoreCase(skill.modelRole());
        String model = vision ? gatewayModelService.getDefaultVisionModel()
                : gatewayModelService.getDefaultTextModel();
        String input = planningInput(context, intent);
        String schema = "intent-video".equals(intent)
                ? PLAN_TEXT_START + "\n给用户看的三套视频方案正文\n" + PLAN_TEXT_END + "\n"
                        + PLAN_JSON_START + "\n{\"message\":\"已准备三套方案\",\"plans\":["
                        + "{\"title\":\"\",\"summary\":\"\",\"message\":\"完整H3提示词\",\"duration\":8,"
                        + "\"referenceRole\":\"FIRST_FRAME|LAST_FRAME|STYLE_REFERENCE\","
                        + "\"params\":{\"resolution\":\"\",\"aspectRatio\":\"\"},"
                        + "\"reasons\":{\"resolution\":\"\",\"aspectRatio\":\"\",\"duration\":\"\"}}]}\n"
                        + PLAN_JSON_END
                : PLAN_TEXT_START + "\n给用户看的三套图片方案正文\n" + PLAN_TEXT_END + "\n"
                        + PLAN_JSON_START + "\n{\"message\":\"已准备三套方案\",\"plans\":["
                        + "{\"title\":\"\",\"summary\":\"\",\"prompt\":\"完整生图提示词\","
                        + "\"params\":{\"size\":\"\",\"quality\":\"\",\"n\":1},"
                        + "\"reasons\":{\"size\":\"\",\"quality\":\"\",\"n\":\"\"}}]}\n"
                        + PLAN_JSON_END;
        String system = skill.systemPrompt()
                + "\n\n当前只做方案设计，禁止调用任何工具，禁止声称已经生成。"
                + "必须完整遵循上面的 SKILL.md 流程、约束和输出要求。"
                + "先输出 PLAN_TEXT 中给用户看的完整三套方案，再输出 PLAN_JSON 中给程序使用的结构化数据。"
                + ("intent-video".equals(intent)
                ? "\n每套视频方案必须根据用户意图自行选择 referenceRole："
                + "FIRST_FRAME（参考图作为首帧）、LAST_FRAME（参考图作为尾帧）或 STYLE_REFERENCE（仅风格参考）。"
                + "不要省略该字段，不要用固定规则代替判断。"
                : "")
                + "严格使用下面格式：\n" + schema;
        StringBuilder raw = new StringBuilder();
        DisplayCursor cursor = new DisplayCursor();
        AtomicBoolean slowNoticeSent = new AtomicBoolean(false);
        ScheduledFuture<?> slowNotice = PLAN_WATCHDOG.schedule(() -> {
            if (slowNoticeSent.compareAndSet(false, true)) {
                support.sendMessage(request, "\n\n方案生成较慢，仍在等待模型返回，请稍候。\n\n");
            }
        }, Math.max(1, mediaPlanSlowNoticeSeconds), TimeUnit.SECONDS);
        try {
            for (String chunk : getClient().prompt()
                    .options(OpenAiChatOptions.builder().model(model)
                            .timeout(Duration.ofSeconds(Math.max(
                                    mediaPlanSlowNoticeSeconds + 1, mediaPlanTimeoutSeconds))))
                    .system(system)
                    .messages(buildUserMessage(input, sources, vision))
                    .stream()
                    .content()
                    .toIterable()) {
                raw.append(chunk);
                emitDisplayDelta(request, raw.toString(), cursor, false);
            }
            emitDisplayDelta(request, raw.toString(), cursor, true);
            Map<String, Object> parsed = parsePlan(betweenMarkers(
                    raw.toString(), PLAN_JSON_START, PLAN_JSON_END));
            if (parsed.isEmpty()) parsed = parsePlan(raw.toString());
            if (!parsed.isEmpty()) {
                String display = betweenMarkers(raw.toString(), PLAN_TEXT_START, PLAN_TEXT_END);
                parsed.put("_display", display);
                request.setLastMessage(display);
                return parsed;
            }
        } catch (Exception e) {
            log.warn("AIGC skill plan failed: intent={}, error={}", intent, e.getMessage());
        } finally {
            slowNotice.cancel(false);
        }
        String display = betweenMarkers(raw.toString(), PLAN_TEXT_START, PLAN_TEXT_END);
        if (display.isBlank()) display = raw.toString().trim();
        Map<String, Object> result = new LinkedHashMap<>();
        if (!display.isBlank()) result.put("_display", display);
        result.put("_missingJson", true);
        result.put("_timedOut", true);
        result.put("plans", List.of());
        return result;
    }

    private void emitDisplayDelta(OrchestrationRequest request, String raw, DisplayCursor cursor, boolean finished) {
        int start = raw.indexOf(PLAN_TEXT_START);
        if (start < 0) return;
        int contentStart = start + PLAN_TEXT_START.length();
        if (!cursor.started) {
            cursor.started = true;
            cursor.emitted = contentStart;
        }
        int end = raw.indexOf(PLAN_TEXT_END, cursor.emitted);
        int safeEnd = end >= 0 ? end : (finished ? raw.length()
                : Math.max(cursor.emitted, raw.length() - (PLAN_TEXT_END.length() - 1)));
        if (safeEnd <= cursor.emitted) return;
        String delta = raw.substring(cursor.emitted, safeEnd);
        cursor.emitted = safeEnd;
        if (!delta.isBlank()) support.sendMessage(request, delta);
    }

    private static String betweenMarkers(String raw, String startMarker, String endMarker) {
        if (raw == null) return "";
        int start = raw.indexOf(startMarker);
        if (start < 0) return "";
        start += startMarker.length();
        int end = raw.indexOf(endMarker, start);
        return end < 0 ? raw.substring(start).trim() : raw.substring(start, end).trim();
    }

    private static final class DisplayCursor {
        private boolean started;
        private int emitted;
    }

    String planningInput(SpecialistContext context, String intent) {
        String custom = context.customText() == null ? "" : context.customText().trim();
        String previous = "intent-video".equals(intent)
                ? support.planField(context.cpPlan(), PlanField.MESSAGE)
                : support.planField(context.cpPlan(), PlanField.PROMPT);
        if (!custom.isBlank()) {
            Map<String, Object> question = parsePlan(context.cpPlan());
            String original = stringValue(question.get("originalMessage"));
            String plansText = stringValue(question.get("plansText"));
            StringBuilder input = new StringBuilder();
            if (!original.isBlank()) input.append("原始需求：").append(original).append("\n\n");
            if (!plansText.isBlank()) {
                input.append("上一轮方案：\n").append(plansText).append("\n\n");
            } else if (!previous.isBlank()) {
                input.append("上一轮方案：").append(previous).append("\n\n");
            }
            input.append("用户调整意见：").append(custom)
                    .append("\n请基于原始需求和上一轮方案修改，保持任务主题不变。");
            return input.toString();
        }
        if (context.userMessage() != null && !context.userMessage().isBlank()) return context.userMessage();
        return previous;
    }

    private Map<String, Object> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode root = JSON.readTree(extractJsonObject(raw));
            if (!root.isObject()) return Map.of();
            return JSON.convertValue(root, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("AIGC skill plan JSON parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    private String planText(String toolName, Map<String, Object> plan) {
        List<Map<String, Object>> plans = planList(plan.get("plans"));
        StringBuilder text = new StringBuilder();
        text.append("generate_video".equals(toolName)
                ? "📹 已准备三套视频方案，请选择一套开始生成：\n"
                : "🖼 已准备三套图片方案，请选择一套开始生成：\n");
        for (int i = 0; i < plans.size(); i++) {
            Map<String, Object> item = plans.get(i);
            String title = stringValue(item.get("title"));
            String summary = stringValue(item.get("summary"));
            String body = "generate_video".equals(toolName)
                    ? stringValue(item.get("message"))
                    : stringValue(item.get("prompt"));
            text.append("\n【方案").append(i + 1);
            if (!title.isBlank()) text.append("｜").append(title);
            text.append("】\n");
            if (!summary.isBlank()) text.append(summary).append("\n");
            if (!body.isBlank()) text.append(body).append("\n");
        }
        return text.toString().trim();
    }

    private static List<Map<String, Object>> mediaActions(String toolName, int planCount) {
        List<Map<String, Object>> actions = new ArrayList<>();
        int count = Math.max(1, Math.min(3, planCount));
        for (int i = 1; i <= count; i++) {
            actions.add(Map.of(
                    "id", toolName + ":" + i,
                    "title", ("generate_video".equals(toolName) ? "开始生成 · 方案" : "开始生图 · 方案") + i));
        }
        actions.add(Map.of("id", "cancel",
                "title", "generate_video".equals(toolName) ? "不生成" : "不生图"));
        return actions;
    }

    private static List<Map<String, Object>> planList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> plans = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((key, content) -> normalized.put(String.valueOf(key), content));
                plans.add(normalized);
            }
        }
        return plans;
    }

    private static Map<String, Object> fallbackPlans(String intent, String input) {
        List<String> variants = List.of("忠实还原", "电影感强化", "插画感强化");
        List<Map<String, Object>> plans = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", variants.get(i));
            item.put("summary", i == 0 ? "优先保留原构图和内容" : "在保留主体基础上强化" + variants.get(i));
            if (intent.startsWith("intent-video")) {
                item.put("message", input + "\n风格方向：" + variants.get(i));
                item.put("duration", 8);
                item.put("params", Map.of("resolution", "720P", "aspectRatio", "16:9"));
            } else {
                item.put("prompt", input + "，风格方向：" + variants.get(i));
                item.put("params", Map.of("size", "2K", "n", 1));
            }
            plans.add(item);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("message", "模型方案解析失败，已生成三套基础方案。");
        fallback.put("plans", plans);
        return fallback;
    }

    private OrchestrationRequest toRequest(SpecialistContext context, AgentSkill skill) {
        OrchestrationRequest request = new OrchestrationRequest(
                context.conversation(), context.userMessage(), context.picUrls(), context.emitter());
        request.setIntentType(skill.intentType());
        request.setAction(context.action() == null ? "" : context.action());
        request.setCustomText(context.customText() == null ? "" : context.customText());
        request.setParams(context.params() == null ? Map.of() : context.params());
        request.setAssetIds(context.assetIds() == null ? List.of() : context.assetIds());
        request.setTrace(context.trace());
        request.setCanvasContext(context.canvasContext());
        return request;
    }

    private UserMessage buildUserMessage(String text, List<String> sources, boolean vision) {
        if (!vision || sources.isEmpty()) return UserMessage.builder().text(text).build();
        try {
            var builder = UserMessage.builder().text(text);
            List<Media> media = new ArrayList<>();
            for (String source : sources) {
                if (source == null || source.isBlank()) continue;
                String dataUri = fileStorageService.toImageDataUri(source);
                int separator = dataUri.indexOf(';');
                String mime = dataUri.startsWith("data:") && separator > 5
                        ? dataUri.substring(5, separator) : "image/png";
                media.add(Media.builder().mimeType(MimeType.valueOf(mime)).data(dataUri).build());
            }
            return media.isEmpty() ? builder.build() : builder.media(media).build();
        } catch (Exception e) {
            log.warn("AIGC skill reference image load failed: {}", e.getMessage());
            return UserMessage.builder().text(text).build();
        }
    }

    private ObjectNode buildToolInput(String toolName, SpecialistContext context) {
        return "generate_video".equals(toolName)
                ? buildVideoInput(context)
                : buildImageInput(context);
    }

    private ObjectNode buildImageInput(SpecialistContext context) {
        List<String> sources = context.picUrls() == null ? List.of() : context.picUrls();
        Map<String, Object> plan = selectedPlan(context);
        Map<String, String> params = selectedParams(context, plan);
        ObjectNode input = JSON.createObjectNode();
        input.put("mode", sources.isEmpty() ? "TEXT_TO_IMAGE" : "IMAGE_EDIT");
        input.put("prompt", selectedPrompt(context, plan, PlanField.PROMPT));
        ArrayNode images = input.putArray("sourceImages");
        sources.forEach(images::add);
        input.put("model", valueOrEmpty(context.params().get("model")));
        input.put("size", valueOrEmpty(params.get("size")));
        input.put("quality", valueOrEmpty(params.get("quality")));
        Integer n = positiveInt(params.get("n"));
        input.put("n", n == null ? 1 : n);
        return input;
    }

    private ObjectNode buildVideoInput(SpecialistContext context) {
        Map<String, Object> plan = selectedPlan(context);
        Map<String, String> params = selectedParams(context, plan);
        ObjectNode input = JSON.createObjectNode();
        input.put("prompt", selectedPrompt(context, plan, PlanField.MESSAGE));

        List<String> sources = videoSources(context);
        ArrayNode references = input.putArray("referenceImages");
        if (!sources.isEmpty()) {
            String semanticRole = referenceRoleFor(context);
            for (int i = 0; i < sources.size(); i++) {
                ObjectNode reference = references.addObject();
                reference.put("url", sources.get(i));
                reference.put("role", referenceRole(semanticRole, i, sources.size()));
            }
        }

        input.put("model", valueOrEmpty(context.params().get("model")));
        String duration = params.get("duration");
        if (isBlank(duration)) duration = stringValue(plan.get("duration"));
        if (isBlank(duration)) duration = support.planField(context.cpPlan(), PlanField.DURATION);
        Integer durationSeconds = positiveInt(duration);
        input.put("duration", durationSeconds == null ? 8 : durationSeconds);
        input.put("aspectRatio", valueOrEmpty(params.get("aspectRatio")));
        input.put("resolution", valueOrEmpty(params.get("resolution")));
        input.put("size", valueOrEmpty(params.get("size")));
        return input;
    }

    private List<String> videoSources(SpecialistContext context) {
        List<String> sources = context.picUrls() == null
                ? new ArrayList<>() : new ArrayList<>(context.picUrls());
        String assetIds = support.planField(context.cpPlan(), PlanField.ASSET_IDS);
        if (!isBlank(assetIds)) {
            List<String> assetImages = support.assetImageUrls(
                    context.conversation().getProjectId(), assetIds);
            if (assetImages != null && !assetImages.isEmpty()) {
                sources = new ArrayList<>(assetImages);
            }
        }
        return sources.stream().filter(value -> !isBlank(value)).distinct().toList();
    }

    String referenceRoleFor(SpecialistContext context) {
        String explicit = support.planField(context.cpPlan(), "referenceRole");
        if (!isBlank(explicit)) return explicit;
        Map<String, Object> question = parsePlan(context.cpPlan());
        List<Map<String, Object>> plans = planList(question.get("plans"));
        if (!plans.isEmpty()) {
            int index = selectedPlanIndex(context.action()) - 1;
            if (index < 0 || index >= plans.size()) index = 0;
            String selected = stringValue(plans.get(index).get("referenceRole"));
            if (!isBlank(selected)) return selected;
        }
        return "STYLE_REFERENCE";
    }

    private String confirmedPrompt(SpecialistContext context, PlanField field) {
        String prompt = support.planField(context.cpPlan(), field);
        if (isBlank(prompt)) prompt = context.userMessage();
        if (isBlank(prompt)) prompt = support.planField(context.cpPlan(), PlanField.CONTENT);
        return prompt == null ? "" : prompt;
    }

    private Map<String, Object> selectedPlan(SpecialistContext context) {
        List<Map<String, Object>> plans = support.planListField(context.cpPlan(), "plans");
        if (plans == null || plans.isEmpty()) return Map.of();
        int index = selectedPlanIndex(context.action()) - 1;
        if (index < 0 || index >= plans.size()) index = 0;
        return plans.get(index);
    }

    private String selectedPrompt(SpecialistContext context, Map<String, Object> plan, PlanField field) {
        String prompt = PlanField.MESSAGE.equals(field)
                ? stringValue(plan.get("message"))
                : stringValue(plan.get("prompt"));
        if (!isBlank(prompt)) return prompt;
        return confirmedPrompt(context, field);
    }

    private Map<String, String> selectedParams(SpecialistContext context, Map<String, Object> plan) {
        Map<String, String> params = new LinkedHashMap<>(stringMap(plan.get("params")));
        if (context.params() != null) params.putAll(context.params());
        return params;
    }

    private static int selectedPlanIndex(String action) {
        if (isBlank(action)) return 1;
        int separator = Math.max(action.lastIndexOf(':'), action.lastIndexOf('-'));
        if (separator < 0 || separator + 1 >= action.length()) return 1;
        try {
            return Math.max(1, Integer.parseInt(action.substring(separator + 1)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private String formatAnswer(String toolName, MediaToolResult result) {
        if (result == null) return "生成请求已提交。";
        if ("generate_image".equals(toolName) && result.resultUrls() != null && !result.resultUrls().isEmpty()) {
            return result.resultUrls().stream()
                    .map(url -> "![生成图片](" + url + ")")
                    .collect(Collectors.joining("\n"));
        }
        if ("generate_video".equals(toolName) && !isBlank(result.taskId())) {
            return "视频任务已提交，任务 ID：" + result.taskId() + "，正在生成中。";
        }
        if (!isBlank(result.error())) return "媒体生成失败：" + result.error();
        return "生成请求已提交。";
    }

    private static String referenceRole(String semanticRole, int index, int count) {
        String role = semanticRole == null ? "" : semanticRole.trim().toUpperCase();
        if ("FIRST_LAST_FRAME".equals(role) && count > 1) {
            return index == 0 ? "FIRST_FRAME" : "LAST_FRAME";
        }
        if ("LAST_FRAME".equals(role)) return "LAST_FRAME";
        if ("FIRST_FRAME".equals(role) && index == 0) return "FIRST_FRAME";
        return count == 1 && index == 0 ? "FIRST_FRAME" : "STYLE_REFERENCE";
    }

    private void emitVideoAccepted(SpecialistContext context, MediaToolResult result) {
        if (result == null || !"video".equals(result.mediaType())
                || isBlank(result.taskId()) || context.emitter() == null) {
            return;
        }
        try {
            context.emitter().send(SseEmitter.event().name("task_accepted")
                    .data(AgentOrchestratorSupport.sseJson(Map.of(
                            "taskId", result.taskId(),
                            "taskKind", "video",
                            "status", result.status(),
                            "message", "视频任务已受理，正在排队生成…"))));
        } catch (Exception e) {
            log.debug("AIGC video task_accepted SSE send failed: {}", e.getMessage());
        }
    }

    private static boolean isConfirmed(SpecialistContext context) {
        if (context == null || isBlank(context.action()) || isBlank(context.cpAction())) return false;
        if ("generate_image".equals(context.cpAction())) {
            return "generate_image".equals(context.action()) || context.action().startsWith("generate_image:");
        }
        if ("generate_video".equals(context.cpAction())) {
            return "generate_video".equals(context.action()) || context.action().startsWith("generate_video:");
        }
        return false;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null && !String.valueOf(item).isBlank()) {
                out.put(String.valueOf(key), String.valueOf(item));
            }
        });
        return out;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Integer positiveInt(String value) {
        if (isBlank(value)) return null;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void putIfText(ObjectNode node, String key, String value) {
        if (!isBlank(value)) node.put(key, value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ChatClient getClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = chatClientBuilder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
                }
            }
        }
        return chatClient;
    }

    private void emit(SpecialistContext context, String answer) {
        if (context.emitter() == null) return;
        try {
            context.emitter().send(SseEmitter.event().name("message")
                    .data(AgentOrchestratorSupport.sseJson(Map.of("content", answer))));
            context.emitter().send(SseEmitter.event().name("message_end")
                    .data(AgentOrchestratorSupport.sseJson(Map.of(
                            "messageId", "", "sceneCount", -1L, "content", answer))));
        } catch (Exception e) {
            log.debug("AIGC media SSE send failed: {}", e.getMessage());
        }
    }
}
