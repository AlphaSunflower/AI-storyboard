package com.moon.moonagent.controller;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.creative.CreativeCritiqueService;
import com.moon.moonagent.ai.agent.creative.CreativePlan;
import com.moon.moonagent.ai.agent.creative.CreativeRunService;
import com.moon.moonagent.ai.agent.creative.MediaCritique;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContext;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.memory.AgentMemoryService;
import com.moon.moonagent.dto.request.AgentGlobalMemoryRequest;
import com.moon.moonagent.dto.request.AgentMemorySettingsRequest;
import com.moon.moonagent.dto.request.CreativeContextUpdateRequest;
import com.moon.moonagent.dto.request.AgentMemoryUpdateRequest;
import com.moon.moonagent.dto.response.AgentGlobalMemoryVO;
import com.moon.moonagent.dto.response.AgentMemorySettingsVO;
import com.moon.moonagent.dto.response.AgentMemoryVO;
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
    private final AgentMemoryService memoryService;
    private final CreativeRunService creativeRunService;
    private final CreativeCritiqueService critiqueService;
    private final AgentChatService chatService;
    private final AgentAiConfigProperties agentConfig;

    @GetMapping("/memories")
    public ApiResponse<List<AgentMemoryVO>> listMyMemories(Authentication auth) {
        return ApiResponse.ok(memoryService.listByUser(auth.getName(), 500).stream()
                .map(AgentCreativeController::toMemoryVO)
                .toList());
    }

    /** 记忆管理状态：总开关 + 全局记忆 + 项目记忆容量。 */
    @GetMapping("/memories/settings")
    public ApiResponse<AgentMemorySettingsVO> memorySettings(Authentication auth) {
        return ApiResponse.ok(memorySettingsOf(auth.getName()));
    }

    /** 打开/关闭记忆管理；关闭后智能体不能写/召回，用户不能改全局记忆。 */
    @PutMapping("/memories/settings")
    public ApiResponse<AgentMemorySettingsVO> updateMemorySettings(
            Authentication auth, @RequestBody AgentMemorySettingsRequest request) {
        if (request == null) {
            throw new com.storyboard.common.BusinessException(40001, "enabled 不能为空");
        }
        memoryService.setEnabled(auth.getName(), request.enabled());
        return ApiResponse.ok(memorySettingsOf(auth.getName()));
    }

    /** 保存（新增或覆盖）账号级全局记忆；全账号只有一条。 */
    @PutMapping("/memories/global")
    public ApiResponse<AgentGlobalMemoryVO> saveGlobalMemory(
            Authentication auth, @RequestBody AgentGlobalMemoryRequest request) {
        if (request == null) {
            throw new com.storyboard.common.BusinessException(40001, "content 不能为空");
        }
        return ApiResponse.ok(toGlobalMemoryVO(
                memoryService.saveGlobal(auth.getName(), request.content())));
    }

    /** 清空全局记忆；关闭记忆管理时仍允许。 */
    @DeleteMapping("/memories/global")
    public ApiResponse<Boolean> clearGlobalMemory(Authentication auth) {
        return ApiResponse.ok(memoryService.clearGlobal(auth.getName()));
    }

    @PutMapping("/memories/{memoryId}")
    public ApiResponse<AgentMemoryVO> updateMyMemory(
            Authentication auth, @PathVariable String memoryId,
            @RequestBody AgentMemoryUpdateRequest request) {
        var updated = memoryService.updateForUser(
                auth.getName(), memoryId, request.kind(), request.content(),
                request.confidence(), parseExpiresAt(request.expiresAt()));
        if (updated == null) {
            throw new com.storyboard.common.BusinessException(40401, "记忆不存在或不属于当前用户");
        }
        return ApiResponse.ok(toMemoryVO(updated));
    }

    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<Boolean> deleteMyMemory(
            Authentication auth, @PathVariable String memoryId) {
        return ApiResponse.ok(memoryService.deleteForUser(auth.getName(), memoryId));
    }

    @GetMapping("/projects/{projectId}/memories")
    public ApiResponse<List<AgentMemoryVO>> listMemories(
            Authentication auth, @PathVariable String projectId) {
        // 复用创作上下文的所有权校验，避免跨用户读项目记忆。
        creativeContextService.get(auth.getName(), projectId);
        return ApiResponse.ok(memoryService.list(projectId, 200).stream()
                .map(AgentCreativeController::toMemoryVO)
                .toList());
    }

    @DeleteMapping("/projects/{projectId}/memories/{memoryId}")
    public ApiResponse<Boolean> deleteMemory(
            Authentication auth, @PathVariable String projectId, @PathVariable String memoryId) {
        creativeContextService.get(auth.getName(), projectId);
        return ApiResponse.ok(memoryService.delete(projectId, memoryId));
    }

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

    /** 解析可选的 ISO-8601 到期时间；空串表示永不过期。 */
    private static java.time.OffsetDateTime parseExpiresAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            throw new com.storyboard.common.BusinessException(40001, "expiresAt 格式必须为 ISO-8601");
        }
    }

    private AgentMemorySettingsVO memorySettingsOf(String userId) {
        return new AgentMemorySettingsVO(
                memoryService.enabled(userId),
                toGlobalMemoryVO(memoryService.global(userId)),
                memoryService.limits());
    }

    private static AgentGlobalMemoryVO toGlobalMemoryVO(
            com.moon.moonagent.entity.AgentGlobalMemory memory) {
        return memory == null ? null
                : new AgentGlobalMemoryVO(memory.getContent(), memory.getUpdatedAt());
    }

    private static AgentMemoryVO toMemoryVO(
            com.moon.moonagent.entity.AgentMemory memory) {
        return new AgentMemoryVO(
                memory.getId(), memory.getProjectId(), memory.getConversationId(),
                memory.getKind(), memory.getContent(), memory.getSource(),
                memory.getConfidence(), memory.getExpiresAt(), memory.getUpdatedAt());
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
