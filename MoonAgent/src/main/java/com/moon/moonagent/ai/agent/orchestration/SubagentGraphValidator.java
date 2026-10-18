package com.moon.moonagent.ai.agent.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务图校验与分层。
 *
 * <p>硬边界（第一版）：节点数 ≤ 12、单节点直接下游 ≤ 6、深度 ≤ 2（根为第 0 层，
 * 最多 3 层）。超限直接拒绝，不做静默截断。
 */
public final class SubagentGraphValidator {

    public static final int MAX_NODES = 12;
    public static final int MAX_FANOUT = 6;
    public static final int MAX_DEPTH = 2;

    private SubagentGraphValidator() {
    }

    /**
     * 校验并返回按依赖划分的层级；同一层内的节点互不依赖，可以并行。
     *
     * @throws IllegalArgumentException 图非法（空、重复、缺依赖、成环、超限）
     */
    public static List<List<SubagentTask>> validateAndLevels(SubagentTaskGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            throw new IllegalArgumentException("任务图不能为空");
        }
        if (graph.nodes().size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "任务图最多 " + MAX_NODES + " 个节点，当前 " + graph.nodes().size());
        }

        Map<String, SubagentTask> byId = new LinkedHashMap<>();
        for (SubagentTask task : graph.nodes()) {
            if (byId.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException("子任务 id 重复: " + task.id());
            }
        }

        Map<String, Set<String>> children = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (SubagentTask task : graph.nodes()) {
            indegree.put(task.id(), task.dependsOn().size());
            children.put(task.id(), new LinkedHashSet<>());
        }
        for (SubagentTask task : graph.nodes()) {
            for (String dependency : task.dependsOn()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "子任务 " + task.id() + " 依赖不存在的节点: " + dependency);
                }
                children.get(dependency).add(task.id());
            }
        }
        for (Map.Entry<String, Set<String>> entry : children.entrySet()) {
            if (entry.getValue().size() > MAX_FANOUT) {
                throw new IllegalArgumentException(
                        "子任务 " + entry.getKey() + " 下游超过 " + MAX_FANOUT
                                + " 个，当前 " + entry.getValue().size());
            }
        }

        List<List<SubagentTask>> levels = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        int processed = 0;
        while (!ready.isEmpty()) {
            List<SubagentTask> level = new ArrayList<>();
            int size = ready.size();
            for (int i = 0; i < size; i++) {
                String id = ready.poll();
                level.add(byId.get(id));
                processed++;
            }
            for (SubagentTask task : level) {
                for (String child : children.get(task.id())) {
                    int left = indegree.merge(child, -1, Integer::sum);
                    if (left == 0) {
                        ready.add(child);
                    }
                }
            }
            levels.add(level);
        }
        if (processed != graph.nodes().size()) {
            throw new IllegalArgumentException("任务图存在环，无法调度");
        }
        if (levels.size() - 1 > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "任务图深度超过 " + MAX_DEPTH + "，当前 " + (levels.size() - 1));
        }
        return levels;
    }
}
