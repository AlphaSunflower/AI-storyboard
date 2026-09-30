package com.moon.moonagent.ai.agent.agentscope;

final class AgentScopeToolLabels {

    private AgentScopeToolLabels() {
    }

    static String displayName(String toolName) {
        if (toolName == null || toolName.isBlank()) return "未知操作";
        return switch (toolName) {
            case AgentScopeImageTools.GENERATE_IMAGE -> "生成图片";
            case AgentScopeImageTools.GENERATE_IMAGE_BATCH -> "批量生成图片";
            case AgentScopeVideoTools.GENERATE_VIDEO -> "生成视频";
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
}
