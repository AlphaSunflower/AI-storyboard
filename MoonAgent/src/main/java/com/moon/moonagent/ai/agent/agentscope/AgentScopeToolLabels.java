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
