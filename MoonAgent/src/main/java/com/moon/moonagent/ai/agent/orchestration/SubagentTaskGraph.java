package com.moon.moonagent.ai.agent.orchestration;

import java.util.List;

/**
 * 通用子智能体任务图。
 *
 * @param task           整图目标（给父级看的摘要）
 * @param nodes          节点集合
 * @param maxConcurrency 单层最大并发
 * @param failFast       任一节点失败后是否取消后续所有节点
 * @param context        所有子 Agent 共享的只读常量（几何/参数/硬约束）；不可被节点改写
 */
public record SubagentTaskGraph(
        String task,
        List<SubagentTask> nodes,
        int maxConcurrency,
        boolean failFast,
        String context) {

    public SubagentTaskGraph(String task, List<SubagentTask> nodes,
                             int maxConcurrency, boolean failFast) {
        this(task, nodes, maxConcurrency, failFast, "");
    }

    public SubagentTaskGraph {
        task = task == null ? "" : task.trim();
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        maxConcurrency = maxConcurrency <= 0 ? 3 : maxConcurrency;
        context = context == null ? "" : context.trim();
    }
}
