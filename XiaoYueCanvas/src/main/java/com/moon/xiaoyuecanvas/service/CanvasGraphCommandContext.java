package com.moon.xiaoyuecanvas.service;

/** Internal context injected by MoonAgent for every canvas graph MCP call. */
public record CanvasGraphCommandContext(
        String userId,
        String projectId,
        String canvasId,
        String runId,
        String operationKey
) {
}
