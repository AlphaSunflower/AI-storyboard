package com.moon.moonagent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.dto.request.AgentTaskPlanActionRequest;
import com.moon.moonagent.dto.response.AgentTaskPlanView;
import com.moon.moonagent.entity.AgentTaskPlan;
import com.moon.moonagent.mapper.AgentTaskPlanMapper;
import com.moon.moonagent.orchestration.TaskPlan;
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

import java.util.List;
import java.util.Set;

/**
 * Read-only compatibility API for plans persisted by the retired task engine.
 * New turns and human input use the AgentScope turn protocol.
 */
@RestController
@RequestMapping("/api/agent/conversations")
@RequiredArgsConstructor
public class AgentTaskPlanController {

    private static final Set<String> ACTIVE_STATES =
            Set.of("DRAFT", "READY", "RUNNING", "WAITING_HUMAN", "WAITING_ASYNC", "REPLANNING");

    private final AgentChatService chatService;
    private final AgentTaskPlanMapper planMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/{id}/plans/active")
    public ApiResponse<AgentTaskPlanView> active(Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        AgentTaskPlan row = planMapper.selectOne(new LambdaQueryWrapper<AgentTaskPlan>()
                .eq(AgentTaskPlan::getConversationId, id)
                .in(AgentTaskPlan::getStatus, ACTIVE_STATES)
                .orderByDesc(AgentTaskPlan::getUpdatedAt)
                .last("LIMIT 1"));
        return ApiResponse.ok(toView(row));
    }

    /** Historical plan reads remain available after the legacy writer is retired. */
    @GetMapping("/{id}/plans")
    public ApiResponse<List<AgentTaskPlanView>> list(Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(planMapper.selectList(new LambdaQueryWrapper<AgentTaskPlan>()
                        .eq(AgentTaskPlan::getConversationId, id)
                        .orderByDesc(AgentTaskPlan::getUpdatedAt)
                        .last("LIMIT 50"))
                .stream()
                .map(this::toView)
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    @PostMapping("/{id}/plans/{planId}/actions")
    public ApiResponse<AgentTaskPlanView> action(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String planId,
            @Valid @RequestBody AgentTaskPlanActionRequest request) {
        throw new BusinessException(
                41001, "旧任务计划操作已停用，请使用 AgentScope turn/HITL 协议");
    }

    private AgentTaskPlanView toView(AgentTaskPlan row) {
        if (row == null) return null;
        if (row.getPlanJson() == null || row.getPlanJson().isBlank()) {
            return fallback(row);
        }
        try {
            return AgentTaskPlanView.from(objectMapper.readValue(row.getPlanJson(), TaskPlan.class));
        } catch (Exception exception) {
            return fallback(row);
        }
    }

    private static AgentTaskPlanView fallback(AgentTaskPlan row) {
        return new AgentTaskPlanView(
                row.getId(), row.getConversationId(), row.getGoal(),
                row.getStatus(), value(row.getVersion()), value(row.getReplanCount()),
                row.getError(), List.of(), List.of());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
