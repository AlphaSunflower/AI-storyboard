package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.audit.ToolInvocationQueryService;
import com.moon.moonagent.dto.response.ToolInvocationView;
import com.storyboard.common.ApiResponse;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentToolInvocationController {

    private final ToolInvocationQueryService queryService;

    @GetMapping("/tool-invocations")
    public ApiResponse<Map<String, Object>> list(
            Authentication auth,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(queryService.list(
                auth.getName(), taskId, conversationId, cursor, limit));
    }

    @GetMapping("/tool-invocations/{invocationId}")
    public ApiResponse<ToolInvocationView> detail(
            Authentication auth,
            @PathVariable String invocationId) {
        ToolInvocationView view = queryService.detail(auth.getName(), invocationId);
        if (view == null) throw new BusinessException(40401, "工具调用记录不存在");
        return ApiResponse.ok(view);
    }

    @GetMapping("/tool-audit/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(queryService.config());
    }
}
