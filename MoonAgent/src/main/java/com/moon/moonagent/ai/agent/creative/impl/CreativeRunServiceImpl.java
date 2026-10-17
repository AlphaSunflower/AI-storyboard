package com.moon.moonagent.ai.agent.creative.impl;

import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowModels;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.creative.CreativeCritiqueService;
import com.moon.moonagent.ai.agent.creative.CreativePlan;
import com.moon.moonagent.ai.agent.creative.CreativeRunService;
import com.moon.moonagent.ai.agent.creative.MediaCritique;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContext;
import com.moon.moonagent.ai.agent.creative.ProjectCreativeContextService;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventStore;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CreativeRunServiceImpl implements CreativeRunService {

    private final AgentChatService chatService;
    private final AgentTurnEventStore turnStore;
    private final AgentWorkflowService workflows;
    private final ProjectCreativeContextService creativeContext;
    private final CreativeCritiqueService critiques;

    @Override
    public CreativePlan plan(String userId, String runId) {
        AgentConversation conversation = ownedConversation(userId, runId);
        AgentWorkflowModels.ActiveWorkflow active = workflows.active(conversation.getId());
        if (active == null) {
            return new CreativePlan(
                    runId, "AUTO_SKILL", "not_started",
                    false, false, false, List.of(),
                    List.of(), List.of(), conversation.getUpdatedAt());
        }
        ProjectCreativeContext context = creativeContext.get(
                userId, conversation.getProjectId());
        return new CreativePlan(
                runId,
                active.plan().mode().name(),
                String.valueOf(active.metadata().getOrDefault("status", "running")),
                active.plan().constraint().serial(),
                active.plan().constraint().noSkip(),
                active.plan().constraint().requireMarkers(),
                active.plan().steps().stream()
                        .map(step -> new CreativePlan.Step(
                                step.id(), step.index(), step.title(), step.toolName(),
                                step.status().name(), step.dependsOn(), step.repeatCount(),
                                step.completedCount(), step.marker(), step.error()))
                        .toList(),
                context.hardConstraints().stream()
                        .map(item -> item.statement()).toList(),
                context.softConstraints().stream()
                        .map(item -> item.statement()).toList(),
                active.updatedAt());
    }

    @Override
    public List<MediaCritique> critiques(String userId, String runId) {
        AgentConversation conversation = ownedConversation(userId, runId);
        return critiques.listByTurn(
                conversation.getProjectId(), conversation.getId(), runId);
    }

    @Override
    public RepairTarget repairTarget(String userId, String runId, String itemId,
                                     String instruction) {
        AgentConversation conversation = ownedConversation(userId, runId);
        MediaCritique target = critiques.listByTurn(
                        conversation.getProjectId(), conversation.getId(), runId)
                .stream()
                .filter(critique -> itemId.equals(critique.id())
                        || itemId.equals(critique.itemId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        40401, "待修复项不存在或不属于当前轮次"));
        String extra = instruction == null || instruction.isBlank()
                ? "" : "\nUser repair instruction: " + instruction.trim();
        String prompt = """
                Repair the generated media item without replacing approved work.
                Violations:
                %s
                Reasons:
                %s
                Keep all HARD project constraints unchanged. Generate one corrected result and
                report it directly to the user.
                %s
                """.formatted(target.violations(), target.reasons(), extra);
        return new RepairTarget(
                conversation.getId(), prompt,
                target.assetUrl() == null || target.assetUrl().isBlank()
                        ? List.of() : List.of(target.assetUrl()));
    }

    private AgentConversation ownedConversation(String userId, String runId) {
        AgentTurnEntity turn = turnStore.find(runId);
        if (turn == null) throw new BusinessException(40401, "运行记录不存在或已过期");
        return chatService.getOwnedConversation(userId, turn.getConversationId());
    }
}
