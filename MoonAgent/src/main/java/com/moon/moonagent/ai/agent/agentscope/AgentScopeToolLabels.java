package com.moon.moonagent.ai.agent.agentscope;

final class AgentScopeToolLabels {

    private AgentScopeToolLabels() {
    }

    static String displayName(String toolName) {
        if (toolName == null || toolName.isBlank()) return "未知操作";
        String canvasLabel = canvasGraphDisplayName(toolName);
        if (canvasLabel != null) return canvasLabel;
        return switch (toolName) {
            case AgentScopeImageTools.GENERATE_IMAGE -> "生成图片";
            case AgentScopeImageTools.GENERATE_IMAGE_BATCH -> "批量生成图片";
            case AgentScopeVideoTools.GENERATE_VIDEO -> "生成视频";
            case AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY -> "一键成片（生图 → 视频 / 分镜全流程）";
            case "refineImage" -> "完善图片";
            case AgentScopeInteractionTools.REQUEST_HUMAN_INPUT -> "请求用户补充信息";
            case AgentScopeCanvasTools.PREPARE_CANVAS_SESSION -> "准备并打开画布";
            case AgentScopeStoryboardTools.START_STORYBOARD -> "启动剧情与分镜流程";
            case AgentScopeStoryboardTools.REVISE_STORYBOARD -> "修订分镜草稿";
            case AgentScopeStoryboardTools.APPROVE_STORYBOARD -> "确认当前分镜阶段";
            case AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGE -> "生成分镜图片";
            case AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEO -> "生成分镜视频";
            case AgentScopeStoryboardTools.GENERATE_STORYBOARD_VIDEOS -> "批量生成分镜视频";
            case AgentScopeStoryboardTools.LIST_STORYBOARD_DRAFT -> "查询分镜草稿";
            case AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD -> "查询项目分镜";
            case AgentScopeStoryboardTools.UPDATE_PROJECT_STORYBOARD_SHOT -> "修改项目分镜参数";
            case AgentScopeStoryboardTools.UPSERT_STORYBOARD_SHOT -> "添加或修改分镜草稿";
            case AgentScopeStoryboardTools.DELETE_STORYBOARD_DRAFT_SHOT -> "删除分镜草稿";
            case AgentScopeStoryboardTools.REORDER_STORYBOARD_DRAFT -> "调整分镜草稿顺序";
            case AgentScopeStoryboardTools.DELETE_PROJECT_STORYBOARD_SHOT -> "删除项目分镜";
            case AgentScopeStoryboardTools.CLEAR_PROJECT_STORYBOARD -> "清空项目分镜";
            case AgentScopeStoryboardTools.GENERATE_STORYBOARD_IMAGES -> "批量生成分镜图片";
            case AgentScopeVideoMergeTools.MERGE_STORYBOARD_VIDEOS -> "合并完整分镜视频";
            case AgentScopeCreativeTools.GET_CONTEXT -> "读取项目创作记忆";
            case AgentScopeCreativeTools.UPDATE_CONTEXT -> "更新项目创作记忆";
            case AgentScopeCreativeTools.EXTRACT_CONSTRAINTS -> "提取创作约束";
            case AgentScopeCreativeTools.REVIEW_MEDIA -> "检查生成结果";
            case AgentScopeCreativeTools.LIST_CRITIQUES -> "读取生成检查记录";
            case AgentScopeCreativeTools.SAVE_TIMELINE -> "保存时间线";
            case AgentScopeCreativeTools.GET_TIMELINE -> "读取时间线";
            case AgentScopeSessionTools.LIST_SESSION_ASSETS -> "查询会话素材";
            case AgentScopeSessionTools.RESOLVE_REFERENCE_ASSETS -> "查找参考素材";
            case AgentScopeSessionTools.GET_TASK_STATUS -> "查询生成任务状态";
            case "read_file" -> "读取文件";
            case "write_file" -> "写入文件";
            case "edit_file" -> "编辑文件";
            case "grep_files" -> "搜索文件内容";
            case "glob_files" -> "查找文件";
            case "list_files" -> "列出文件";
            case "execute" -> "执行命令";
            case "deliver_artifact" -> "交付产物";
            case AgentScopeSandboxTools.RUN_PYTHON_SCRIPT -> "执行 Python 脚本";
            case AgentScopeWebTools.WEB_SEARCH -> "网页搜索";
            case AgentScopeWebTools.WEB_FETCH -> "抓取网页";
            case AgentScopeAssetGroupTools.LIST_ASSET_GROUPS -> "查询资产组";
            case "task_list" -> "查询后台任务";
            case "task_output" -> "读取后台任务结果";
            case "wait_async_results" -> "等待后台任务";
            case "agent_list" -> "查询子智能体";
            case "load_skill_through_path" -> "读取技能";
            case "reset_equipped_tools" -> "调整可用工具";
            default -> toolName;
        };
    }

    /**
     * 画布图命令（服务端 MCP 工具）的中文名。
     *
     * <p>这些工具名过去没有映射，工具草稿与轨迹里显示的是原始英文名
     * （{@code canvas_graph_create_nodes}），用户在智能体建图时看不懂它在做什么。
     * 新增的整图提交与批量连线尤其需要能一眼分辨，因此这里补齐。
     *
     * <p>返回 {@code null} 表示不是画布图命令，交给主 switch 处理。
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
            case "canvas_graph_get_context", "canvas_graph_get_state", "canvas_graph_list_nodes",
                    "canvas_graph_get_node", "canvas_graph_list_edges",
                    "canvas_graph_list_node_types" -> "读取画布状态";
            default -> null;
        };
    }
}
