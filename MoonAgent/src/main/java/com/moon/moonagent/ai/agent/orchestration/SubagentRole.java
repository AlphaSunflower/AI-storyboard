package com.moon.moonagent.ai.agent.orchestration;

import java.util.List;

/**
 * 本地角色清单（agents/roles/*.json）里的一条。
 *
 * @param prompt   角色系统提示词
 * @param tools    该角色允许的只读工具名（父级工具集的子集）
 * @param maxTurns 单次子任务最大模型轮数
 */
public record SubagentRole(
        String name,
        String description,
        String prompt,
        List<String> tools,
        int maxTurns) {

    public SubagentRole {
        name = require(name, "name");
        description = description == null ? "" : description.trim();
        prompt = require(prompt, "prompt");
        tools = tools == null ? List.of() : List.copyOf(tools);
        maxTurns = maxTurns <= 0 ? 8 : maxTurns;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("角色 " + field + " 不能为空");
        }
        return value.trim();
    }
}
