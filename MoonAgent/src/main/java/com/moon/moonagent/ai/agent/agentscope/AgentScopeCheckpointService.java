package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.recovery.CheckpointResumeException;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentScopeCheckpointService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentOrchestratorSupport support;
    private final AgentCheckpointMapper checkpointMapper;

    public AgentScopeCheckpointService(AgentOrchestratorSupport support,
                                       AgentCheckpointMapper checkpointMapper) {
        this.support = support;
        this.checkpointMapper = checkpointMapper;
    }

    public String pauseForExternalInput(OrchestrationRequest request, String replyId,
                                        ToolUseBlock toolCall) {
        Map<String, Object> input = toolCall.getInput() == null ? Map.of() : toolCall.getInput();
        String prompt = stringValue(input.get("prompt"));
        if (prompt.isBlank()) prompt = "请补充一个必要信息后继续。";
        boolean allowCustom = Boolean.TRUE.equals(input.get("allow_custom"));
        List<Map<String, Object>> options = optionMaps(input.get("options"));
        List<Map<String, Object>> actions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            Map<String, Object> option = options.get(i);
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("id", "option-" + i);
            String title = stringValue(option.get("title"));
            action.put("title", title.isBlank() ? fallbackOptionTitle(option.get("value"), i) : title);
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

        support.runHITLStage(request, null,
                new AgentOrchestratorSupport.StagePlan(
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
        List<String> names = new ArrayList<>();
        for (ToolUseBlock call : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.getId());
            item.put("name", call.getName());
            item.put("input", call.getInput() == null ? Map.of() : call.getInput());
            calls.add(item);
            names.add(call.getName());
        }
        List<String> displayNames = names.stream().map(AgentScopeToolLabels::displayName).toList();
        String prompt = "是否允许执行高风险操作：“" + String.join("”“", displayNames) + "”？";
        List<Map<String, Object>> actions = List.of(
                Map.of("id", "approve", "title", "允许执行"),
                Map.of("id", "reject", "title", "拒绝"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "permission");
        payload.put("replyId", replyId);
        payload.put("toolCalls", calls);
        support.runHITLStage(request, null,
                new AgentOrchestratorSupport.StagePlan(
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
        support.runHITLStage(request, null,
                new AgentOrchestratorSupport.StagePlan(
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
            boolean approved = "approve".equals(action);
            return new ResumeValue(kind, "", "", approved ? "approved" : "rejected", approved);
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
            return new ResumeValue(kind, toolCallId, toolName, choice, true);
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
        if (value.isBlank()) value = "用户已取消或未提供额外信息";
        return new ResumeValue(kind, toolCallId, toolName, value, true);
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
        String value = stringValue(rawValue).trim().toLowerCase();
        return switch (value) {
            case "append" -> "追加分镜";
            case "overwrite", "replace" -> "覆盖分镜";
            default -> "选项" + (index + 1);
        };
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

    public record ResumeValue(String kind, String toolCallId, String toolName,
                              String text, boolean approved) {
    }
}
