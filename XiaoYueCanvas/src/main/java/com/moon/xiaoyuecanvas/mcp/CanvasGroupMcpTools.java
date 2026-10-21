package com.moon.xiaoyuecanvas.mcp;

import com.moon.xiaoyuecanvas.bridge.CanvasBrowserMailbox;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画布分组工具（浏览器执行）。
 *
 * <p>执行入口只存在于分组：分组标题条渲染「执行」按钮，未分组的节点没有任何执行入口。
 * 智能体要跑一整条工作流时，必须先把参与节点分到同一组，再由用户在分组上点「执行」确认；
 * 本工具只做组织，不触发任何生成，因此不做强制预览。
 *
 * <p>命令经 {@link CanvasBrowserMailbox} 排队，浏览器轮询领取后在页面内写分组
 * （成员关系存在 {@code node.data.groupId/groupTitle/groupCreatedAt}）。
 * MoonAgent 注入 userId/projectId/canvasId/runId/operationKey，模型不可见。
 */
@Component
public class CanvasGroupMcpTools {

    private final CanvasBrowserMailbox mailbox;

    public CanvasGroupMcpTools(CanvasBrowserMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @Tool(description = "把多个画布节点分成一组；分组标题上会出现「执行」按钮，执行入口只存在于分组。")
    @McpTool(name = "canvas_group_nodes",
            description = "把多个画布节点分成一组；分组标题上会出现「执行」按钮，执行入口只存在于分组。")
    public Map<String, Object> canvasGroupNodes(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Node ids to group together, at least 2")
            @McpToolParam(description = "Node ids to group together, at least 2") List<String> nodeIds,
            @ToolParam(description = "Optional group title", required = false)
            @McpToolParam(description = "Optional group title", required = false) String title) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeIds", nodeIds == null ? List.of() : nodeIds);
        if (title != null && !title.isBlank()) params.put("title", title.trim());
        return CanvasExecuteMcpTools.toToolPayload(mailbox.command(
                userId, projectId, canvasId, "canvas.group.create", params, false, null));
    }
}
