package com.moon.moonagent.controller;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.creative.CreativeCritiqueService;
import com.moon.moonagent.ai.agent.creative.CreativePlan;
import com.moon.moonagent.ai.agent.creative.CreativeRunService;
import com.moon.moonagent.ai.agent.creative.MediaCritique;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContext;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.dto.request.CreativeContextUpdateRequest;
import com.moon.moonagent.dto.response.ProjectCreativeContextVO;
import com.storyboard.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentCreativeController {

    private final ProjectCreativeContextService creativeContextService;
    private final CreativeRunService creativeRunService;
    private final CreativeCritiqueService critiqueService;
    private final AgentChatService chatService;
    private final AgentAiConfigProperties agentConfig;

    @GetMapping("/projects/{projectId}/creative-context")
    public ApiResponse<ProjectCreativeContextVO> getCreativeContext(
            Authentication auth, @PathVariable String projectId) {
        return ApiResponse.ok(toVO(creativeContextService.get(auth.getName(), projectId)));
    }

    @PutMapping("/projects/{projectId}/creative-context")
    public ApiResponse<ProjectCreativeContextVO> updateCreativeContext(
            Authentication auth, @PathVariable String projectId,
            @RequestBody CreativeContextUpdateRequest request) {
        ProjectCreativeContextService.CreativeContextPatch patch =
                new ProjectCreativeContextService.CreativeContextPatch(
                        request.objective(), request.creativeDirection(), request.visualStyle(),
                        request.references(), request.hardConstraints(), request.softConstraints(),
                        request.approvedAssetIds(), request.rejectedDirections(),
                        request.feedback(), request.modelPreferences());
        return ApiResponse.ok(toVO(creativeContextService.patch(
                auth.getName(), projectId, patch)));
    }

    @GetMapping("/runs/{runId}/plan")
    public ApiResponse<CreativePlan> plan(Authentication auth, @PathVariable String runId) {
        return ApiResponse.ok(creativeRunService.plan(auth.getName(), runId));
    }

    @GetMapping("/runs/{runId}/critique")
    public ApiResponse<List<MediaCritique>> critiques(
            Authentication auth, @PathVariable String runId) {
        return ApiResponse.ok(creativeRunService.critiques(auth.getName(), runId));
    }

    @GetMapping("/runs/{runId}/critique/{itemId}")
    public ApiResponse<MediaCritique> critique(
            Authentication auth, @PathVariable String runId, @PathVariable String itemId) {
        return ApiResponse.ok(creativeRunService.critiques(auth.getName(), runId).stream()
                .filter(item -> itemId.equals(item.id()) || itemId.equals(item.itemId()))
                .findFirst()
                .orElse(null));
    }

    @PostMapping(value = "/runs/{runId}/items/{itemId}/repair",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter repair(Authentication auth, @PathVariable String runId,
                             @PathVariable String itemId,
                             @RequestParam(required = false) String instruction) {
        CreativeRunService.RepairTarget target =
                creativeRunService.repairTarget(auth.getName(), runId, itemId, instruction);
        SseEmitter emitter = new SseEmitter(
                Math.max(1, agentConfig.getSseTimeoutSeconds()) * 1000L);
        chatService.streamAgentScopeTurn(
                auth.getName(), target.conversationId(), target.prompt(),
                target.referenceUrls(), List.of(), null, null, null,
                List.of(), List.of(), emitter);
        return emitter;
    }

    private static ProjectCreativeContextVO toVO(ProjectCreativeContext context) {
        return new ProjectCreativeContextVO(
                context.projectId(),
                context.objective(),
                context.creativeDirection(),
                context.visualStyle(),
                context.references(),
                context.hardConstraints(),
                context.softConstraints(),
                context.approvedAssetIds(),
                context.rejectedDirections(),
                context.feedback(),
                context.modelPreferences(),
                context.version(),
                context.updatedAt());
    }
}
