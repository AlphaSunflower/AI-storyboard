package com.moon.moonagent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.capability.AgentCapabilityRegistry;
import com.moon.moonagent.ai.agent.orchestration.DynamicTaskEngine;
import com.moon.moonagent.ai.agent.orchestration.InvalidTaskPlanTransitionException;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanActionService;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanPlanner;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanStore;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanningRequest;
import com.moon.moonagent.dto.request.AgentTaskPlanActionRequest;
import com.moon.moonagent.dto.response.AgentTaskPlanView;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanScheduler;
import com.moon.moonagent.orchestration.TaskPlanState;
import com.storyboard.common.ApiResponse;
import com.storyboard.common.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/conversations")
@RequiredArgsConstructor
public class AgentTaskPlanController {

    private final AgentChatService chatService;
    private final TaskPlanStore planStore;
    private final TaskPlanActionService actionService;
    private final TaskPlanPlanner planner;
    private final AgentCapabilityRegistry capabilities;
    private final TaskPlanScheduler scheduler;
    private final DynamicTaskEngine dynamicTaskEngine;
    private final AgentCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/{id}/plans/active")
    public ApiResponse<AgentTaskPlanView> active(Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(planStore.findActive(id).map(AgentTaskPlanView::from).orElse(null));
    }

    @PostMapping("/{id}/plans/{planId}/actions")
    public ApiResponse<AgentTaskPlanView> action(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody AgentTaskPlanActionRequest request) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        TaskPlan plan = planStore.find(planId)
                .filter(candidate -> id.equals(candidate.conversationId()))
                .orElseThrow(() -> new BusinessException(40401, "任务计划不存在"));
        String action = request.action().trim().toLowerCase(Locale.ROOT);
        TaskPlan updated;
        try {
            updated = "replan".equals(action)
                    ? replan(conversation, plan)
                    : actionService.apply(plan, action);
        } catch (InvalidTaskPlanTransitionException e) {
            throw new BusinessException(40001, e.getMessage());
        }

        AgentCheckpoint pending = ("approve".equals(action) || "resume".equals(action))
                ? findPendingCheckpoint(id, planId)
                : null;
        updated = planStore.update(updated);

        if (pending != null) {
            if ("approve".equals(action) || "resume".equals(action)) {
                chatService.submitFormAndResume(
                        auth.getName(), id, pending.getFormToken(), "", checkpointResumeAction(pending, action),
                        "", Map.of(), List.of(), "", new SseEmitter(0L));
            } else {
                expirePendingCheckpoints(id);
                resumeIfReady(conversation, updated);
            }
        } else {
            if ("replan".equals(action) || "retry".equals(action)
                    || "cancel".equals(action) || "reject".equals(action)) {
                expirePendingCheckpoints(id);
            }
            resumeIfReady(conversation, updated);
        }
        return ApiResponse.ok(AgentTaskPlanView.from(updated));
    }

    private TaskPlan replan(AgentConversation conversation, TaskPlan plan) {
        if (plan.replanCount() >= plan.maxReplans()) {
            throw new BusinessException(40001, "任务计划已达到重新规划次数上限");
        }
        String preferredCapability = plan.nodes().stream()
                .filter(node -> node.state() != com.moon.moonagent.orchestration.TaskNodeState.COMPLETED)
                .map(com.moon.moonagent.orchestration.TaskNode::capabilityKey)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        TaskPlan fresh = planner.plan(new TaskPlanningRequest(
                conversation.getId(), plan.goal(), preferredCapability,
                plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans()),
                capabilities.descriptors());
        TaskPlan replanned = scheduler.replan(plan, fresh.nodes(), fresh.edges());
        return new TaskPlan(
                replanned.planId(), replanned.conversationId(), replanned.goal(),
                fresh.state() == TaskPlanState.WAITING_HUMAN
                        ? TaskPlanState.WAITING_HUMAN : replanned.state(),
                replanned.version(), replanned.maxNodes(), replanned.maxAttemptsPerNode(),
                replanned.maxReplans(), replanned.replanCount(), replanned.nodes(),
                replanned.edges(), fresh.error(), replanned.planSignature());
    }

    private void resumeIfReady(AgentConversation conversation, TaskPlan plan) {
        if (plan.state() == TaskPlanState.READY || plan.state() == TaskPlanState.RUNNING
                || plan.state() == TaskPlanState.REPLANNING) {
            dynamicTaskEngine.resumePlan(conversation, plan);
        }
    }

    private AgentCheckpoint findPendingCheckpoint(String conversationId, String planId) {
        AgentCheckpoint exact = checkpointMapper.selectOne(new LambdaQueryWrapper<AgentCheckpoint>()
                .eq(AgentCheckpoint::getConversationId, conversationId)
                .eq(AgentCheckpoint::getStatus, "pending")
                .eq(AgentCheckpoint::getPlanId, planId)
                .orderByDesc(AgentCheckpoint::getCreatedAt)
                .last("LIMIT 1"));
        if (exact != null) return exact;
        return checkpointMapper.selectOne(new LambdaQueryWrapper<AgentCheckpoint>()
                .eq(AgentCheckpoint::getConversationId, conversationId)
                .eq(AgentCheckpoint::getStatus, "pending")
                .isNull(AgentCheckpoint::getPlanId)
                .orderByDesc(AgentCheckpoint::getCreatedAt)
                .last("LIMIT 1"));
    }

    private void expirePendingCheckpoints(String conversationId) {
        checkpointMapper.update(null, new UpdateWrapper<AgentCheckpoint>()
                .eq("conversation_id", conversationId)
                .eq("status", "pending")
                .set("status", "expired"));
    }

    private String checkpointResumeAction(AgentCheckpoint checkpoint, String requestedAction) {
        String fromPlan = checkpointActionFromPlan(checkpoint.getPlan());
        if (!fromPlan.isBlank()) return fromPlan;
        if (checkpoint.getAction() != null && !checkpoint.getAction().isBlank()) return checkpoint.getAction();
        return "resume".equals(requestedAction) ? "agree" : requestedAction;
    }

    private String checkpointActionFromPlan(String planJson) {
        if (planJson == null || planJson.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode actions = root.path("_actions");
            if (!actions.isArray() || actions.isEmpty()) {
                JsonNode items = root.path("items");
                actions = items.isArray() && !items.isEmpty() ? items.get(0).path("actions") : null;
            }
            if (actions != null && actions.isArray()) {
                for (JsonNode action : actions) {
                    String id = action.path("id").asText("");
                    if (!id.isBlank() && !"cancel".equalsIgnoreCase(id)
                            && !"reject".equalsIgnoreCase(id)) {
                        return id;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the checkpoint action.
        }
        return "";
    }
}
