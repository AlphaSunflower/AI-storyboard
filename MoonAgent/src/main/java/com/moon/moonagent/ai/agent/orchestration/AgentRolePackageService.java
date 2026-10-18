package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentInstalledPackage;

import java.util.List;

/**
 * 动态角色/工具/技能包（P0 只消费 role）。
 */
public interface AgentRolePackageService {

    /** 按 kind 列出包（管理/排查用）。 */
    List<AgentInstalledPackage> list(String kind);

    /** 已启用的角色包，解析并校验后的角色；非法包跳过并告警。 */
    List<SubagentRole> enabledRoles();
}
