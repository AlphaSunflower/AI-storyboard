package com.moon.moonagent.ai.agent.agentscope;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentScopeInteractionTools {

    public static final String REQUEST_HUMAN_INPUT = "request_human_input";
    public static final String CHOOSE_STORYBOARD_COMMIT = "choose_storyboard_commit_mode";

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                REQUEST_HUMAN_INPUT,
                "Pause and ask the user one focused question. Use only for genuine ambiguity or a "
                        + "missing required creative decision, never as a default step.",
                AgentScopeTool.schema(Map.of(
                        "prompt", Map.of("type", "string"),
                        "options", Map.of("type", "array", "items", Map.of("type", "object")),
                        "allow_custom", Map.of("type", "boolean")), List.of("prompt")),
                false, true, true,
                (input, param) -> "");
    }

    public String requestHumanInput(
            String prompt,
            List<Map<String, Object>> options,
            Boolean allowCustom,
            AgentScopeRunContext context) {
        return "";
    }

    public AgentScopeTool storyboardCommitTool() {
        return new AgentScopeTool(
                CHOOSE_STORYBOARD_COMMIT,
                "Ask the user how to write a generated storyboard when the project already "
                        + "contains scenes. The backend fixes the options to append, overwrite or delete.",
                AgentScopeTool.schema(Map.of(
                        "existing_scene_count", Map.of("type", "integer")),
                        List.of("existing_scene_count")),
                false, true, true,
                (input, param) -> "");
    }
}
