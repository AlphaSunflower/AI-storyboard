package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentApprovalMode;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.human.ApprovalOutcome;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeException;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentScopeCheckpointService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern STORYBOARD_COUNT = Pattern.compile(
            "(\\d+)\\s*(?:条|个|张)?\\s*分镜");

    private final AgentRuntimeSupport support;
    private final AgentCheckpointMapper checkpointMapper;
    private final AgentScopeProperties properties;

    public AgentScopeCheckpointService(AgentRuntimeSupport support,
                                       AgentCheckpointMapper checkpointMapper) {
        this(support, checkpointMapper, new AgentScopeProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentScopeCheckpointService(AgentRuntimeSupport support,
                                       AgentCheckpointMapper checkpointMapper,
                                       AgentScopeProperties properties) {
        this.support = support;
        this.checkpointMapper = checkpointMapper;
        this.properties = properties == null ? new AgentScopeProperties() : properties;
    }

    public String pauseForExternalInput(OrchestrationRequest request, String replyId,
                                        ToolUseBlock toolCall) {
        Map<String, Object> input = toolCall.getInput() == null ? Map.of() : toolCall.getInput();
        String prompt = stringValue(input.get("prompt"));
        if (prompt.isBlank()) prompt = "请补充一个必要信息后继续。";
        boolean allowCustom = Boolean.TRUE.equals(input.get("allow_custom"));
        List<Map<String, Object>> options = optionMaps(input.get("options"));
        Integer storyboardCount = storyboardCommitSceneCount(prompt, options);
        if (storyboardCount != null) {
            return pauseForStoryboardCommit(request, replyId, toolCall, storyboardCount);
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            Map<String, Object> option = options.get(i);
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("id", "option-" + i);
            String title = stringValue(option.get("title"));
            action.put("title", title.isBlank()
                    ? fallbackOptionTitle(optionFallbackValue(option), i)
                    : title);
            if (!stringValue(option.get("description")).isBlank()) {
                action.put("description", stringValue(option.get("description")));
            }
            actions.add(action);
        }
        if (actions.isEmpty() || allowCustom) {
            actions.add(Map.of("id", "custom", "title", "自定义输入"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("replyId", replyId);
        payload.put("toolCallId", toolCall.getId());
        payload.put("toolName", toolCall.getName());
        payload.put("options", options);
        payload.put("allowCustom", allowCustom);

        support.runHITLStage(request,
                new AgentRuntimeSupport.StagePlan(
                        prompt, "agentscope-reply", List.of(payload), "human_input", actions),
                checkpoint -> {
                    checkpoint.setOwnerEngine("AGENTSCOPE");
                    checkpoint.setStateSchemaVersion(3);
                    checkpoint.setRuntimeSessionId(request.getConversation().getId());
                    checkpoint.setRuntimeReplyId(replyId);
                    checkpoint.setRuntimeToolCallId(toolCall.getId());
                    checkpoint.setRuntimeToolName(toolCall.getName());
                });
        return latestPendingToken(request.getConversation().getId());
    }

    public String pauseForConfirmation(OrchestrationRequest request, String replyId,
                                       List<ToolUseBlock> toolCalls) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolUseBlock call : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.getId());
            item.put("name", call.getName());
            item.put("input", call.getInput() == null ? Map.of() : call.getInput());
            calls.add(item);
        }
        AgentApprovalMode mode = AgentApprovalMode.from(
                request == null || request.getConversation() == null
                        ? null : request.getConversation().getApprovalMode());
        String prompt = confirmationPrompt(mode, toolCalls);
        List<Map<String, Object>> actions = mode == AgentApprovalMode.PLAN_APPROVAL
                ? List.of(
                        Map.of("id", "approve", "title", planApproveTitle(toolCalls)),
                        Map.of("id", "reject", "title", "取消"))
                : List.of(
                        Map.of("id", "approve", "title", "允许执行"),
                        Map.of("id", "reject", "title", "拒绝"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "permission");
        payload.put("approvalMode", mode.name());
        payload.put("replyId", replyId);
        payload.put("toolCalls", calls);
        payload.put("writeToolOptions", writeToolOptions(toolCalls));
        support.runHITLStage(request,
                new AgentRuntimeSupport.StagePlan(
                        prompt, "agentscope-confirm", List.of(payload), "human_input", actions),
                checkpoint -> {
                    checkpoint.setOwnerEngine("AGENTSCOPE");
                    checkpoint.setStateSchemaVersion(3);
                    checkpoint.setRuntimeSessionId(request.getConversation().getId());
                    checkpoint.setRuntimeReplyId(replyId);
                    checkpoint.setRuntimeToolCallsJson(writeJson(calls));
                    if (!toolCalls.isEmpty()) {
                        checkpoint.setRuntimeToolCallId(toolCalls.getFirst().getId());
                        checkpoint.setRuntimeToolName(toolCalls.getFirst().getName());
                    }
                });
        return latestPendingToken(request.getConversation().getId());
    }

    String confirmationPrompt(AgentApprovalMode mode, List<ToolUseBlock> toolCalls) {
        AgentApprovalMode effective = mode == null ? AgentApprovalMode.PLAN_APPROVAL : mode;
        String heading = effective == AgentApprovalMode.PLAN_APPROVAL
                ? (hasGenerationTool(toolCalls) ? "生成方案待确认" : "执行方案待确认")
                : "请求批准";
        String intro = effective == AgentApprovalMode.PLAN_APPROVAL
                ? "请确认以下执行方案。批准后仅执行本次列出的操作；方案之外的新操作仍需单独确认。"
                : "以下高风险操作需要你明确授权后才会执行。";
        StringBuilder summary = new StringBuilder();
        for (ToolUseBlock call : toolCalls == null ? List.<ToolUseBlock>of() : toolCalls) {
            if (summary.length() > 0) summary.append("\n\n");
            summary.append(summarizeToolCall(call));
        }
        if (summary.length() == 0) summary.append("• 未提供可执行方案");
        return heading + "\n\n" + intro + "\n\n" + summary;
    }

    private String planApproveTitle(List<ToolUseBlock> toolCalls) {
        return hasGenerationTool(toolCalls) ? "按方案生成" : "按方案执行";
    }

    private static boolean hasGenerationTool(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null) return false;
        return toolCalls.stream()
                .map(ToolUseBlock::getName)
                .anyMatch(name -> name != null
                        && (name.contains("generate")
                        || AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY.equals(name)));
    }

    private String summarizeToolCall(ToolUseBlock call) {
        String toolName = call == null ? "" : call.getName();
        Map<String, Object> input = call == null || call.getInput() == null
                ? Map.of() : call.getInput();
        String displayName = AgentScopeToolLabels.displayName(toolName);
        List<String> lines = switch (toolName) {
            case AgentScopeImageTools.GENERATE_IMAGE -> imageSummary(input, false);
            case AgentScopeImageTools.GENERATE_IMAGE_BATCH -> imageSummary(input, true);
            case AgentScopeVideoTools.GENERATE_VIDEO -> videoSummary(input);
            // 复合旅程：确认卡片必须逐条列出真正会执行的步骤，而不是只说"执行一个工具"。
            // 写任务图：把 allowWrite 节点和实际工具列出来，用户批准的是明确的写入面。
            case AgentScopeOrchestrationTools.RUN_WRITE_TASK_GRAPH -> writeGraphSummary(input);
            case AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY -> journeySummary(input);
            default -> genericSummary(toolName, input);
        };
        StringBuilder out = new StringBuilder("• ").append(displayName);
        for (String line : lines) {
            out.append("\n  - ").append(line);
        }
        return out.toString();
    }

    private List<Map<String, Object>> writeToolOptions(List<ToolUseBlock> toolCalls) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (ToolUseBlock call : toolCalls == null ? List.<ToolUseBlock>of() : toolCalls) {
            if (call == null || !AgentScopeOrchestrationTools.RUN_WRITE_TASK_GRAPH
                    .equals(call.getName()) || call.getInput() == null) continue;
            Object rawNodes = call.getInput().get("nodes");
            if (!(rawNodes instanceof List<?> nodes)) continue;
            for (Object raw : nodes) {
                if (!(raw instanceof Map<?, ?> node)
                        || !Boolean.TRUE.equals(node.get("allowWrite"))) continue;
                List<String> tools = stringList(node.get("tools"));
                if (tools.isEmpty()) tools = List.of("write_file", "run_python_script");
                options.add(Map.of(
                        "nodeId", stringValue(node.get("id")),
                        "role", stringValue(node.get("role")),
                        "tools", tools));
            }
        }
        return options;
    }

    private List<String> writeGraphSummary(Map<String, Object> input) {
        List<String> lines = new ArrayList<>();
        Object rawNodes = input.get("nodes");
        if (rawNodes instanceof List<?> nodes) {
            for (Object raw : nodes) {
                if (!(raw instanceof Map<?, ?> node)) continue;
                if (!Boolean.TRUE.equals(node.get("allowWrite"))) continue;
                String id = stringValue(node.get("id"));
                String role = stringValue(node.get("role"));
                List<String> tools = stringList(node.get("tools"));
                String toolText = tools.isEmpty() ? "角色默认写工具" : String.join(", ", tools);
                lines.add("节点 " + (id.isBlank() ? "(未命名)" : id)
                        + (role.isBlank() ? "" : "（" + role + "）")
                        + "：" + toolText);
            }
        }
        if (lines.isEmpty()) lines.add("未声明 allowWrite 节点");
        return lines;
    }

    /**
     * 复合旅程的确认方案：按阈值判定分支并逐条列举具体步骤。
     *
     * <p>与 {@code AgentScopeJourneyTools.start} 共用 {@link AgentScopeJourneyPlanner}，
     * 因此卡片上给出的步骤和工具真正调度的步骤是同一份清单。
     */
    private List<String> journeySummary(Map<String, Object> input) {
        List<String> lines = new ArrayList<>();
        List<String> shotPrompts = stringList(input.get("shot_prompts"));
        int imageCount = shotPrompts.isEmpty()
                ? Math.max(1, positiveInt(input.get("image_count"), 1))
                : shotPrompts.size();
        String duration = stringValue(input.get("target_duration_seconds"));
        boolean wantStoryboard = Boolean.TRUE.equals(input.get("want_storyboard"));
        AgentScopeJourneyPlanner.JourneyPlan plan = AgentScopeJourneyPlanner.plan(
                duration, wantStoryboard, properties.getJourneyDirectVideoMaxSeconds(),
                imageCount, shotPrompts.size());
        lines.add("分支：" + AgentScopeJourneyPlanner.branchLabel(plan.branch()));
        lines.add("阈值：" + plan.thresholdSeconds() + " 秒（目标时长 "
                + (duration.isBlank() ? "未指定" : duration) + "）");
        lines.add("将执行以下 " + plan.steps().size() + " 个步骤：");
        for (AgentScopeJourneyPlanner.JourneyStep step : plan.steps()) {
            lines.add("  " + step.display());
        }
        lines.add("画幅：" + valueOr(input.get("aspect_ratio"), "自动")
                + "；分辨率：" + valueOr(input.get("resolution"), "自动")
                + "；模型：" + valueOr(input.get("model"), "系统默认"));
        int references = stringList(input.get("source_asset_ids")).size()
                + stringList(input.get("source_urls")).size();
        lines.add("参考素材：" + references + " 项");
        lines.add("提示词：" + truncate(stringValue(input.get("prompt")), 320));
        lines.add("批准后仅执行以上列出的步骤；新增操作需再次确认。");
        return lines;
    }

    private static List<String> imageSummary(Map<String, Object> input, boolean batch) {
        List<String> lines = new ArrayList<>();
        List<String> prompts = stringList(input.get("prompts"));
        int count = batch
                ? (prompts.isEmpty() ? positiveInt(input.get("n"), 2) : prompts.size())
                : positiveInt(input.get("n"), 1);
        lines.add("数量：" + count + " 张");
        lines.add("模型：" + valueOr(input.get("model"), "系统默认"));
        lines.add("尺寸：" + valueOr(input.get("size"), "自动"));
        lines.add("质量：" + valueOr(input.get("quality"), "自动"));
        int references = stringList(input.get("source_asset_ids")).size()
                + stringList(input.get("source_urls")).size();
        lines.add("参考素材：" + references + " 项");
        if (batch && !prompts.isEmpty()) {
            lines.add("逐条提示词：");
            for (int i = 0; i < prompts.size(); i++) {
                lines.add("  " + (i + 1) + ". " + truncate(prompts.get(i), 220));
            }
        } else {
            lines.add("提示词：" + truncate(stringValue(input.get("prompt")), 320));
        }
        if (batch && Boolean.TRUE.equals(input.get("follow_up_video"))) {
            lines.add("后续视频：" + truncate(valueOr(input.get("video_prompt"), "使用生成图继续"), 180)
                    + "（" + valueOr(input.get("video_duration"), "自动")
                    + " / " + valueOr(input.get("video_aspect_ratio"), "自动")
                    + " / " + valueOr(input.get("video_resolution"), "自动") + "）");
        }
        return lines;
    }

    private static List<String> videoSummary(Map<String, Object> input) {
        List<String> lines = new ArrayList<>();
        lines.add("模型：" + valueOr(input.get("model"), "系统默认"));
        lines.add("时长：" + valueOr(input.get("duration"), "自动") + " 秒");
        lines.add("画幅：" + valueOr(input.get("aspect_ratio"), "自动"));
        lines.add("分辨率：" + valueOr(input.get("resolution"), "自动"));
        int references = stringList(input.get("reference_asset_ids")).size()
                + stringList(input.get("reference_urls")).size();
        lines.add("参考图：" + references + " 张");
        lines.add("提示词：" + truncate(stringValue(input.get("prompt")), 320));
        return lines;
    }

    private static List<String> genericSummary(String toolName, Map<String, Object> input) {
        List<String> lines = new ArrayList<>();
        for (String key : List.of(
                "scene_id", "shot_id", "sceneId", "shotId", "count", "n",
                "mode", "overwrite", "confirmed", "reason")) {
            Object value = input.get(key);
            if (value == null || stringValue(value).isBlank()) continue;
            lines.add(key + "：" + truncate(stringValue(value), 180));
        }
        if (lines.isEmpty()) {
            lines.add(toolName == null || toolName.isBlank()
                    ? "将执行一项高风险操作。"
                    : "将执行 " + AgentScopeToolLabels.displayName(toolName) + "。");
        }
        return lines;
    }

    private static int positiveInt(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(1, number.intValue());
        try {
            return Math.max(1, Integer.parseInt(stringValue(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String valueOr(Object value, String fallback) {
        String text = stringValue(value);
        return text.isBlank() ? fallback : text;
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf)
                .toList();
    }

    public String pauseForStoryboardCommit(OrchestrationRequest request, String replyId,
                                           ToolUseBlock toolCall, int existingSceneCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "storyboard-commit");
        payload.put("replyId", replyId);
        payload.put("toolCallId", toolCall.getId());
        payload.put("toolName", toolCall.getName());
        payload.put("existingSceneCount", Math.max(0, existingSceneCount));
        List<Map<String, Object>> actions = List.of(
                Map.of("id", "append", "title", "追加到末尾",
                        "description", "保留现有分镜，把新分镜接在后面"),
                Map.of("id", "overwrite", "title", "覆盖全部分镜",
                        "description", "删除现有分镜及其素材关联，再写入新分镜"),
                Map.of("id", "delete", "title", "删除全部分镜",
                        "description", "只删除现有分镜，本次不写入新分镜"));
        support.runHITLStage(request,
                new AgentRuntimeSupport.StagePlan(
                        "当前项目已有 " + existingSceneCount
                                + " 个分镜。本次生成结果要怎么处理？",
                        "storyboard-commit", List.of(payload), "human_input", actions),
                checkpoint -> {
                    checkpoint.setOwnerEngine("AGENTSCOPE");
                    checkpoint.setStateSchemaVersion(3);
                    checkpoint.setRuntimeSessionId(request.getConversation().getId());
                    checkpoint.setRuntimeReplyId(replyId);
                    checkpoint.setRuntimeToolCallId(toolCall.getId());
                    checkpoint.setRuntimeToolName(toolCall.getName());
                });
        return latestPendingToken(request.getConversation().getId());
    }

    private String latestPendingToken(String conversationId) {
        AgentCheckpoint checkpoint = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentCheckpoint>()
                        .eq(AgentCheckpoint::getConversationId, conversationId)
                        .eq(AgentCheckpoint::getStatus, "pending")
                        .orderByDesc(AgentCheckpoint::getCreatedAt)
                        .last("LIMIT 1"));
        return checkpoint == null || checkpoint.getFormToken() == null
                ? "" : checkpoint.getFormToken();
    }

    public ResumeValue resolve(AgentCheckpoint checkpoint, String action, String customText) {
        Map<String, Object> plan = parse(checkpoint == null ? null : checkpoint.getPlan());
        Map<String, Object> item = firstItem(plan);
        String kind = stringValue(item.get("kind"));
        if (kind.isBlank()) kind = "external";
        if ("permission".equals(kind)) {
            // 闭合词表 + fail-closed：只认 allowed/rejected/cancelled，其他一律 unavailable。
            ApprovalOutcome outcome = ApprovalOutcome.fromAction(action);
            return new ResumeValue(kind, "", "", outcome.wireValue(), outcome);
        }
        String toolCallId = stringValue(checkpoint.getRuntimeToolCallId());
        String toolName = stringValue(checkpoint.getRuntimeToolName());
        if (toolCallId.isBlank() || toolName.isBlank()) {
            throw new CheckpointResumeException(40902,
                    "AgentScope 运行态引用缺失，请重新发起");
        }
        if ("storyboard-commit".equals(kind)) {
            String choice = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
            if (!List.of("append", "overwrite", "delete").contains(choice)) choice = "append";
            return new ResumeValue(kind, toolCallId, toolName, choice, ApprovalOutcome.ALLOWED);
        }
        String value = customText == null ? "" : customText.trim();
        if (value.isBlank() && action != null && action.startsWith("option-")) {
            try {
                int index = Integer.parseInt(action.substring("option-".length()));
                List<Map<String, Object>> options = optionMaps(item.get("options"));
                if (index >= 0 && index < options.size()) {
                    Map<String, Object> option = options.get(index);
                    String candidate = stringValue(option.get("value"));
                    if (candidate.isBlank()) candidate = stringValue(option.get("title"));
                    value = candidate;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the human-readable action title.
            }
        }
        if (value.isBlank()) value = actionTitle(plan, action);
        if (value.isBlank()) {
            // 既没有自定义文本，也没有可识别的选项：审批不可判定，按不可用处理。
            return new ResumeValue(kind, toolCallId, toolName,
                    ApprovalOutcome.UNAVAILABLE.wireValue(), ApprovalOutcome.UNAVAILABLE);
        }
        return new ResumeValue(kind, toolCallId, toolName, value, ApprovalOutcome.ALLOWED);
    }

    public List<ToolUseBlock> permissionToolCalls(AgentCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getRuntimeToolCallsJson() == null
                || checkpoint.getRuntimeToolCallsJson().isBlank()) {
            throw new CheckpointResumeException(40902,
                    "AgentScope 权限运行态缺失，请重新发起");
        }
        List<?> list;
        try {
            list = JSON.readValue(checkpoint.getRuntimeToolCallsJson(),
                    new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            throw new CheckpointResumeException(40902,
                    "AgentScope 权限运行态无法解析，请重新发起");
        }
        List<ToolUseBlock> calls = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Map<String, Object> input = new LinkedHashMap<>();
            Object rawInput = map.get("input");
            if (rawInput instanceof Map<?, ?> inputMap) {
                inputMap.forEach((key, itemValue) ->
                        input.put(String.valueOf(key), itemValue));
            }
            calls.add(ToolUseBlock.builder()
                    .id(stringValue(map.get("id")))
                    .name(stringValue(map.get("name")))
                    .input(input)
                    .build());
        }
        return calls;
    }

    private static String actionTitle(Map<String, Object> plan, String action) {
        Object raw = plan.get("_actions");
        if (!(raw instanceof List<?> list)) return "";
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            if (action != null && action.equals(stringValue(map.get("id")))) {
                String title = stringValue(map.get("title"));
                String description = stringValue(map.get("description"));
                return description.isBlank() ? title : title + "：" + description;
            }
        }
        return "";
    }

    private static Map<String, Object> firstItem(Map<String, Object> plan) {
        Object items = plan.get("items");
        if (items instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
            return out;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> optionMaps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            out.add(normalized);
        }
        return out;
    }

    private static String fallbackOptionTitle(Object rawValue, int index) {
        String raw = stringValue(rawValue).trim();
        String value = raw.toLowerCase();
        return switch (value) {
            case "append" -> "追加分镜";
            case "overwrite", "replace" -> "覆盖分镜";
            default -> raw.isBlank() ? "选项" + (index + 1) : raw;
        };
    }

    private static Object optionFallbackValue(Map<String, Object> option) {
        for (String key : List.of("value", "label", "text", "name")) {
            Object value = option.get(key);
            if (value != null && !stringValue(value).isBlank()) return value;
        }
        return null;
    }

    private static Integer storyboardCommitSceneCount(
            String prompt, List<Map<String, Object>> options) {
        if (prompt == null || options == null || options.size() < 2) return null;
        String value = prompt.trim();
        if (!value.contains("分镜")
                || !(value.contains("处理") || value.contains("清理")
                || value.contains("写入") || value.contains("追加")
                || value.contains("覆盖") || value.contains("删除"))) {
            return null;
        }
        Matcher matcher = STORYBOARD_COUNT.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = JSON.readTree(raw);
            return JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new CheckpointResumeException(40902, "AgentScope 运行态保存失败，请重新发起");
        }
    }

    /**
     * 一次 checkpoint 恢复的解析结果。
     *
     * <p>{@code outcome} 是闭合审批词表；{@code approved()} 只是兼容旧调用方的派生判断，
     * 仅 {@link ApprovalOutcome#ALLOWED} 为真，其他三种终态都不得执行工具。
     */
    public record ResumeValue(String kind, String toolCallId, String toolName,
                              String text, ApprovalOutcome outcome) {

        /** 是否允许执行；仅为 {@link ApprovalOutcome#ALLOWED} 时为真。 */
        public boolean approved() {
            return outcome != null && outcome.allowsExecution();
        }
    }
}
