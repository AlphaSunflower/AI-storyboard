package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanActionService;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanStore;
import com.moon.moonagent.dto.request.AgentTaskPlanActionRequest;
import com.moon.moonagent.dto.response.AgentTaskPlanView;
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

@RestController
@RequestMapping("/api/agent/conversations")
@RequiredArgsConstructor
public class AgentTaskPlanController {

    private final AgentChatService chatService;
    private final TaskPlanStore planStore;
    private final TaskPlanActionService actionService;

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
        chatService.getOwnedConversation(auth.getName(), id);
        TaskPlan plan = planStore.find(planId)
                .filter(candidate -> id.equals(candidate.conversationId()))
                .orElseThrow(() -> new BusinessException(40401, "任务计划不存在"));
        TaskPlan updated = actionService.apply(plan, request.action());
        return ApiResponse.ok(AgentTaskPlanView.from(planStore.update(updated)));
    }
}
