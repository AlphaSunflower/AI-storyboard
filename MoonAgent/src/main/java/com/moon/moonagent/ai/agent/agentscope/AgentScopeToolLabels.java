package com.moon.moonagent.ai.agent.agentscope;

final class AgentScopeToolLabels {

    private AgentScopeToolLabels() {
    }

    static String displayName(String toolName) {
        if (toolName == null || toolName.isBlank()) return "未知操作";
        // 单一真源：Moon 自研工具的标签在 ToolCatalog 登记；这里只保留框架工具与画布命令的兜底。
        String catalogLabel = ToolCatalog.label(toolName);
        if (catalogLabel != null) return catalogLabel;
        String canvasLabel = canvasGraphDisplayName(toolName);
        if (canvasLabel != null) return canvasLabel;
        return switch (toolName) {
            case "read_file" -> "读取文件";
            case "write_file" -> "写入文件";
            case "edit_file" -> "编辑文件";
            case "grep_files" -> "搜索文件内容";
            case "glob_files" -> "查找文件";
            case "list_files" -> "列出文件";
            case "execute" -> "执行命令";
            case "deliver_artifact" -> "交付产物";
            case "task_list" -> "查询后台任务";
            case "task_output" -> "读取后台任务结果";
            case "wait_async_results" -> "等待后台任务";
            case "agent_list" -> "查询子智能体";
            case "load_skill_through_path" -> "读取技能";
            case "reset_equipped_tools" -> "调整可用工具";
            case AgentScopeCanvasTools.PREPARE_CANVAS_SESSION -> "准备并打开画布";
            default -> toolName;
        };
    }

    /**
     * 画布图命令（服务端 MCP 工具）的中文名。
     *
     * <p>这些工具名是动态发现的，无法在 {@link ToolCatalog} 中逐条登记；因此保留在这里。
     * 返回 {@code null} 表示不是画布图命令。
     */
    private static String canvasGraphDisplayName(String toolName) {
        return switch (toolName) {
            case "canvas_graph_apply_plan" -> "构建画布（一次提交整图）";
            case "canvas_graph_create_nodes" -> "批量创建画布节点";
            case "canvas_graph_create_node" -> "创建画布节点";
            case "canvas_graph_create_edges" -> "批量连接画布节点";
            case "canvas_graph_create_edge" -> "连接画布节点";
            case "canvas_graph_update_node" -> "更新画布节点";
            case "canvas_graph_import_media_batch" -> "批量导入画布素材";
            case "canvas_graph_import_media" -> "导入画布素材";
            case "canvas_graph_create_result_slots" -> "创建结果槽";
            case "canvas_graph_mark_result_slots" -> "标记结果槽";
            case "canvas_graph_layout_graph" -> "整理画布布局";
            case "canvas_group_nodes" -> "把节点分成一组";
            case "canvas_graph_get_state", "canvas_graph_get_node",
                    "canvas_graph_list_node_types" -> "读取画布状态";
            default -> null;
        };
    }
}
