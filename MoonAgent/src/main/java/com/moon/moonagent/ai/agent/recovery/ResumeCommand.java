package com.moon.moonagent.ai.agent.recovery;

import com.moon.moonagent.ai.agent.CanvasSessionContext;

import java.util.List;
import java.util.Map;

public record ResumeCommand(
        String action,
        String customText,
        Map<String, String> params,
        List<String> assetIds,
        String routingHint,
        String canvasContextJson) {

    public ResumeCommand {
        action = action == null ? "" : action;
        customText = customText == null ? "" : customText;
        params = params == null ? Map.of() : Map.copyOf(params);
        assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        routingHint = routingHint == null ? "" : routingHint;
        canvasContextJson = canvasContextJson == null ? "" : canvasContextJson;
    }

    public static ResumeCommand of(String action, String customText,
                                   Map<String, String> params, List<String> assetIds,
                                   String routingHint, CanvasSessionContext canvasContext) {
        return new ResumeCommand(action, customText, params, assetIds, routingHint,
                canvasContext == null ? "" : canvasContext.toJson());
    }

    public CanvasSessionContext canvasContext() {
        return CanvasSessionContext.fromJson(canvasContextJson);
    }
}
