package com.moon.moonagent.ai.agent.agentscope;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class AgentScopeAssetGroupTools {

    public static final String LIST_ASSET_GROUPS = "list_asset_groups";

    private final AgentScopeAssetGroupService assetGroups;

    public AgentScopeTool tool() {
        return new AgentScopeTool(
                LIST_ASSET_GROUPS,
                "List asset groups attached to the current conversation. Returns group id, name, "
                        + "description, snapshot id, and each file's asset/media id, name, text "
                        + "constraint, file name, object key and readable content URL.",
                AgentScopeTool.schema(Map.of(
                        "group_id", Map.of("type", "string")), List.of()),
                true, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = context(param);
                    String groupId = string(input.get("group_id"));
                    return assetGroups.list(
                            AgentScopeSessionTools.requireConversation(context), groupId);
                });
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static AgentScopeRunContext context(io.agentscope.core.tool.ToolCallParam param) {
        return param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }
}
