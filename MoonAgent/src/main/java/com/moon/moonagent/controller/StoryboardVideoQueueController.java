package com.moon.moonagent.controller;

import com.moon.moonagent.dto.request.StoryboardVideoEnqueueRequest;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.StoryboardVideoQueue;
import com.moon.moonagent.service.StoryboardVideoQueueService;
import com.moon.moonagent.service.StoryboardQueueRequestGuard;
import com.storyboard.common.ApiResponse;
import com.storyboard.common.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分镜视频批量生成队列端点（/chat Moon 智能体）。
 *
 * <p>对外暴露：批量入队（POST）、查询整队（GET）、取消单条（DELETE）。
 * 鉴权由 Gateway 统一验签后透传（与 AgentConversationController 同语义）。
 */
@RestController
@RequestMapping("/api/agent/storyboards/video/queue")
@RequiredArgsConstructor
public class StoryboardVideoQueueController {

    private final StoryboardVideoQueueService queueService;
    private final StoryboardClient storyboardClient;
    private final StoryboardQueueRequestGuard requestGuard;

    /** 批量入队勾选的分镜视频任务。 */
    @PostMapping
    public ApiResponse<List<StoryboardVideoQueue>> enqueue(
            Authentication auth, @Valid @RequestBody StoryboardVideoEnqueueRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(40001, "请先勾选要生成视频的分镜");
        }
        requestGuard.requireOwnedScope(
                auth.getName(), request.projectId(), request.conversationId(),
                request.items().stream().map(item -> item == null ? null : item.sceneId()).toList());
        return ApiResponse.ok(queueService.enqueue(request));
    }

    /**
     * 查询某项目的队列（右上角任务队列面板 + 分镜行状态共用）。
     * {@code status=active} 仅返回排队中/生成中；缺省返回全量（兼容分镜行状态）。
     */
    @GetMapping
    public ApiResponse<List<StoryboardVideoQueue>> list(
            Authentication auth, @RequestParam String projectId,
            @RequestParam(required = false) String status) {
        assertProjectOwner(auth, projectId);
        return ApiResponse.ok(queueService.listByProject(projectId, status));
    }

    /** 取消单条队列任务。 */
    @DeleteMapping("/{queueId}")
    public ApiResponse<Void> cancel(
            Authentication auth, @PathVariable String queueId) {
        assertProjectOwner(auth, queueService.getProjectId(queueId));
        queueService.cancel(queueId);
        return ApiResponse.ok("已取消", null);
    }

    /** 校验当前用户是否为该项目的属主（越权访问返回 40301）。 */
    private void assertProjectOwner(Authentication auth, String projectId) {
        if (auth == null || auth.getName() == null || projectId == null || projectId.isBlank()) {
            throw new BusinessException(40301, "无权访问该项目");
        }
        Map<String, Object> project = storyboardClient.getProject(projectId);
        if (project == null || !auth.getName().equals(project.get("userId"))) {
            throw new BusinessException(40301, "无权访问该项目");
        }
    }
}
