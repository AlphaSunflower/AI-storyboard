package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowModels;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.entity.AgentConversation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeWorkflowTools {

    public static final String RESOLVE_WORKFLOW = "resolve_agent_workflow";

    private final AgentWorkflowService workflowService;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                RESOLVE_WORKFLOW,
                "Resolve whether the current user prompt defines an explicit workflow. "
                        + "Call this before a storyboard request that contains numbered stages, "
                        + "serial execution, no-skip rules, or required stage markers.",
                AgentScopeTool.schema(Map.of(
                        "prompt", Map.of("type", "string")), List.of("prompt")),
                false, false, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    String prompt = input.get("prompt") == null
                            ? "" : String.valueOf(input.get("prompt"));
                    AgentWorkflowModels.Plan plan = workflowService.resolve(conversation, prompt);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("mode", plan.mode().name());
                    result.put("runId", plan.runId());
                    result.put("serial", plan.constraint().serial());
                    result.put("noSkip", plan.constraint().noSkip());
                    result.put("requireMarkers", plan.constraint().requireMarkers());
                    result.put("steps", plan.steps());
                    result.put("instruction", plan.mode() == AgentWorkflowModels.Mode.AUTO_SKILL
                            ? "No explicit user workflow. Continue with the matching skill normally."
                            : "Follow the returned steps in order. Use only the next required tool; "
                            + "the runtime will emit completion markers automatically.");
                    return result;
                });
    }
}
