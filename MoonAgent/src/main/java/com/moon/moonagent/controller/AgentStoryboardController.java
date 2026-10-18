package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.storyboard.production.StoryboardChangeLog;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.storyboard.common.ApiResponse;
import com.storyboard.common.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/storyboards/runs")
public class AgentStoryboardController {

    private final StoryboardProductionService productionService;
    private final ObjectProvider<AgentScopeAsyncTaskService> asyncTasksProvider;
    private final AgentConversationMapper conversationMapper;

    public AgentStoryboardController(
            StoryboardProductionService productionService,
            ObjectProvider<AgentScopeAsyncTaskService> asyncTasksProvider,
            AgentConversationMapper conversationMapper) {
        this.productionService = productionService;
        this.asyncTasksProvider = asyncTasksProvider;
        this.conversationMapper = conversationMapper;
    }

    @GetMapping("/{runId}")
    public ApiResponse<StoryboardRunSnapshot> get(Authentication auth, @PathVariable String runId) {
        StoryboardRunSnapshot run = ownedRun(auth, runId);
        return ApiResponse.ok(run);
    }

    /**
     * 分镜草稿改动日志（字段级）：刷新、换设备、审批之后都能追溯"谁把哪一镜改成了什么"。
     */
    @GetMapping("/{runId}/changes")
    public ApiResponse<List<StoryboardChangeLog.ShotChange>> changes(
            Authentication auth, @PathVariable String runId) {
        StoryboardRunSnapshot run = ownedRun(auth, runId);
        AgentConversation conversation = requireConversation(run.conversationId(), auth.getName());
        return ApiResponse.ok(productionService.changes(runId, auth.getName(), conversation.getProjectId()));
    }

    @PutMapping("/{runId}/draft")
    public ApiResponse<StoryboardRunSnapshot> saveDraft(
            Authentication auth,
            @PathVariable String runId,
            @RequestBody DraftRequest request) {
        StoryboardRunSnapshot run = ownedRun(auth, runId);
        AgentConversation conversation = requireConversation(run.conversationId(), auth.getName());
        return ApiResponse.ok(productionService.replaceDraft(
                runId,
                auth.getName(),
                conversation.getProjectId(),
                request == null ? 0 : request.expectedVersion(),
                request == null ? List.of() : request.pseudoShots(),
                request == null ? List.of() : request.finalShots()));
    }

    @PostMapping("/{runId}/approve")
    public ApiResponse<ApprovalResult> approve(
            Authentication auth,
            @PathVariable String runId,
            @RequestBody ApprovalRequest request) {
        StoryboardRunSnapshot run = ownedRun(auth, runId);
        AgentConversation conversation = requireConversation(run.conversationId(), auth.getName());
        Stage stage = parseStage(request == null ? "" : request.stage());
        if (stage != Stage.PLOT_REVIEW && stage != Stage.PSEUDO_REVIEW && stage != Stage.FINAL_REVIEW) {
            throw new BusinessException(40001, "不支持的审核阶段: " + stage);
        }
        if (run.stage() != stage) {
            throw new BusinessException(40901, "分镜运行阶段已变化，请刷新后重试");
        }
        AgentScopeAsyncTaskService asyncTasks = asyncTasksProvider.getIfAvailable();
        if (asyncTasks == null) {
            throw new BusinessException(40903, "AgentScope 执行器未启用");
        }
        String taskId = asyncTasks.startStoryboardApprovalTask(
                conversation,
                runId,
                stage.name(),
                request == null ? "" : request.decision(),
                "");
        return ApiResponse.ok(new ApprovalResult(taskId, stage.name()));
    }

    private StoryboardRunSnapshot ownedRun(Authentication auth, String runId) {
        StoryboardRunSnapshot run = productionService.getOwned(runId, auth.getName(), null);
        requireConversation(run.conversationId(), auth.getName());
        return run;
    }

    private AgentConversation requireConversation(String conversationId, String userId) {
        AgentConversation conversation = conversationId == null || conversationId.isBlank()
                ? null : conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new BusinessException(40401, "分镜会话不存在或无权访问");
        }
        return conversation;
    }

    private static Stage parseStage(String value) {
        try {
            return Stage.valueOf(value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException(40001, "无效审核阶段: " + value);
        }
    }

    public record DraftRequest(
            int expectedVersion,
            List<PseudoShot> pseudoShots,
            List<StoryboardShot> finalShots
    ) {
    }

    public record ApprovalRequest(String stage, String decision) {
    }

    public record ApprovalResult(String taskId, String stage) {
    }
}
