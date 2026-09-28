package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.task.AgentTaskEventHub;
import com.moon.moonagent.ai.agent.task.AgentTaskView;
import com.storyboard.common.ApiResponse;
import com.storyboard.common.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/tasks")
public class AgentTaskController {

    private final ObjectProvider<AgentScopeAsyncTaskService> taskServiceProvider;
    private final AgentTaskEventHub taskEvents;
    private final AgentChatService chatService;

    public AgentTaskController(
            ObjectProvider<AgentScopeAsyncTaskService> taskServiceProvider,
            AgentTaskEventHub taskEvents,
            AgentChatService chatService) {
        this.taskServiceProvider = taskServiceProvider;
        this.taskEvents = taskEvents;
        this.chatService = chatService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            Authentication auth,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AgentScopeAsyncTaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService == null) {
            return ApiResponse.ok(Map.of("records", java.util.List.of(), "total", 0L,
                    "page", Math.max(1, page), "size", Math.max(1, size)));
        }
        return ApiResponse.ok(taskService.listTasks(
                auth.getName(), conversationId, projectId, status, page, size));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Object> detail(Authentication auth, @PathVariable String taskId) {
        AgentScopeAsyncTaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService != null) {
            Map<String, Object> task = taskService.getStatus(auth.getName(), taskId);
            if (!task.isEmpty()) return ApiResponse.ok(task);
        }
        try {
            return ApiResponse.ok(chatService.getVideoTaskStatus(auth.getName(), taskId));
        } catch (BusinessException e) {
            throw new BusinessException(40401, "任务不存在");
        }
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication auth) {
        SseEmitter emitter = taskEvents.subscribe(auth.getName());
        AgentScopeAsyncTaskService taskService = taskServiceProvider.getIfAvailable();
        if (taskService != null) {
            taskService.listTaskViews(auth.getName(), null, null, null, 200)
                    .forEach(task -> {
                        try {
                            emitter.send(SseEmitter.event().name("snapshot")
                                    .data(AgentRuntimeSupport.sseJson(Map.of("task", task))));
                        } catch (Exception ignored) {
                            // 单次首包失败由前端轮询兜底。
                        }
                    });
        }
        return emitter;
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<AgentTaskView> cancel(Authentication auth, @PathVariable String taskId) {
        AgentScopeAsyncTaskService taskService = taskServiceProvider.getIfAvailable();
        AgentTaskView task = taskService == null ? null : taskService.cancelTask(auth.getName(), taskId);
        if (task == null) throw new BusinessException(40401, "任务不存在");
        return ApiResponse.ok(task);
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<AgentTaskView> retry(Authentication auth, @PathVariable String taskId) {
        AgentScopeAsyncTaskService taskService = taskServiceProvider.getIfAvailable();
        AgentTaskView task = taskService == null ? null : taskService.retryTask(auth.getName(), taskId);
        if (task == null) throw new BusinessException(40401, "任务不存在");
        return ApiResponse.ok(task);
    }

    @PostMapping("/{taskId}/followups")
    public ApiResponse<AgentTaskView> followUp(
            Authentication auth,
            @PathVariable String taskId,
            @RequestBody FollowUpRequest request) {
        AgentScopeAsyncTaskService taskService = taskServiceProvider.getIfAvailable();
        AgentTaskView task = taskService == null ? null : taskService.followUpTask(
                auth.getName(), taskId, request == null ? "" : request.instruction());
        if (task == null) throw new BusinessException(40401, "任务不存在");
        return ApiResponse.ok(task);
    }

    public record FollowUpRequest(String instruction) {
    }
}
