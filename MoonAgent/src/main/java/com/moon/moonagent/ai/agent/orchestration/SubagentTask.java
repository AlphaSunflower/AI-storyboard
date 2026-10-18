package com.moon.moonagent.ai.agent.orchestration;

import java.util.List;

/**
 * 通用子智能体任务图里的一个节点。
 *
 * @param id          图内唯一 id
 * @param role        角色名（对应本地 roles/*.yaml 或后续注册中心条目）
 * @param instruction 给子智能体的任务说明
 * @param dependsOn   依赖的节点 id；依赖全部成功后才调度
 * @param skills      允许使用的技能（父级 profile 的子集；空 = 继承父级）
 * @param tools       允许使用的工具（父级 profile 的子集；空 = 继承父级）
 * @param maxTurns    单节点最大模型轮数
 * @param outputSchema 可选结构化输出契约；目前支持 {@code parameter-table}
 * @param allowWrite   是否允许写工具；默认 false。当前仅在 HITL 冒泡完成后才会真正放开。
 */
public record SubagentTask(
        String id,
        String role,
        String instruction,
        List<String> dependsOn,
        List<String> skills,
        List<String> tools,
        int maxTurns,
        String outputSchema,
        boolean allowWrite) {

    public SubagentTask(String id, String role, String instruction, List<String> dependsOn,
                        List<String> skills, List<String> tools, int maxTurns) {
        this(id, role, instruction, dependsOn, skills, tools, maxTurns, null, false);
    }

    public SubagentTask(String id, String role, String instruction, List<String> dependsOn,
                        List<String> skills, List<String> tools, int maxTurns,
                        String outputSchema) {
        this(id, role, instruction, dependsOn, skills, tools, maxTurns, outputSchema, false);
    }

    public SubagentTask {
        id = require(id, "id");
        role = require(role, "role");
        instruction = require(instruction, "instruction");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        skills = skills == null ? List.of() : List.copyOf(skills);
        tools = tools == null ? List.of() : List.copyOf(tools);
        maxTurns = maxTurns <= 0 ? 8 : maxTurns;
        outputSchema = outputSchema == null || outputSchema.isBlank()
                ? null : outputSchema.trim();
    }
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("子任务 " + field + " 不能为空");
        }
        return value.trim();
    }
}
