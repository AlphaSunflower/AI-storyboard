package com.moon.moonagent.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TaskPlanValidator {

    public TaskPlanValidationResult validate(TaskPlan plan, Map<String, CapabilityDescriptor> capabilities) {
        List<String> errors = new ArrayList<>();
        Map<String, CapabilityDescriptor> safeCapabilities = capabilities == null ? Map.of() : capabilities;

        if (plan.nodes().size() > plan.maxNodes()) {
            errors.add("node budget exceeded: " + plan.nodes().size() + " > " + plan.maxNodes());
        }

        Set<String> nodeIds = new HashSet<>();
        for (TaskNode node : plan.nodes()) {
            if (node == null) {
                errors.add("null node entry");
                continue;
            }

            String nodeId = node.nodeId();
            if (nodeId == null || nodeId.isBlank()) {
                errors.add("node ID must not be null or blank");
                continue;
            }

            if (nodeIds.add(nodeId)) {
                CapabilityDescriptor descriptor = safeCapabilities.get(node.capabilityKey());
                if (descriptor == null) {
                    errors.add("unknown capability: " + node.capabilityKey());
                } else if (node.attemptCount() > Math.min(plan.maxAttemptsPerNode(), descriptor.maxAttempts())) {
                    errors.add("attempt budget exceeded: " + node.nodeId());
                }
            } else {
                errors.add("duplicate node: " + nodeId);
            }
        }

        for (TaskNode node : plan.nodes()) {
            if (node == null || node.nodeId() == null || node.nodeId().isBlank()) {
                continue;
            }
            for (String dependency : node.dependencies()) {
                if (!nodeIds.contains(dependency)) {
                    errors.add("missing dependency: " + node.nodeId() + " -> " + dependency);
                }
            }
        }

        for (TaskEdge edge : plan.edges()) {
            if (!nodeIds.contains(edge.fromNodeId()) || !nodeIds.contains(edge.toNodeId())) {
                errors.add("edge references missing node: " + edge.fromNodeId() + " -> " + edge.toNodeId());
            }
        }

        String cycle = detectCycle(plan, nodeIds);
        if (cycle != null) {
            errors.add("cycle detected: " + cycle);
        }

        return errors.isEmpty() ? TaskPlanValidationResult.ok() : TaskPlanValidationResult.invalid(errors);
    }

    private static String detectCycle(TaskPlan plan, Set<String> validNodeIds) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (TaskNode node : plan.nodes()) {
            if (node == null || !validNodeIds.contains(node.nodeId())) {
                continue;
            }

            List<String> dependencies = new ArrayList<>();
            for (String dependency : node.dependencies()) {
                if (validNodeIds.contains(dependency)) {
                    dependencies.add(dependency);
                }
            }
            outgoing.put(node.nodeId(), dependencies);
        }
        for (TaskEdge edge : plan.edges()) {
            if (edge == null
                    || !validNodeIds.contains(edge.fromNodeId())
                    || !validNodeIds.contains(edge.toNodeId())) {
                continue;
            }
            outgoing.computeIfAbsent(edge.toNodeId(), ignored -> new ArrayList<>()).add(edge.fromNodeId());
        }

        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> path = new ArrayDeque<>();
        for (String nodeId : outgoing.keySet()) {
            String cycle = visit(nodeId, outgoing, visiting, visited, path);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static String visit(String nodeId, Map<String, List<String>> outgoing,
                                Set<String> visiting, Set<String> visited, ArrayDeque<String> path) {
        if (visited.contains(nodeId)) {
            return null;
        }
        if (!visiting.add(nodeId)) {
            return String.join(" -> ", path) + (path.isEmpty() ? nodeId : " -> " + nodeId);
        }
        path.addLast(nodeId);
        for (String dependency : outgoing.getOrDefault(nodeId, List.of())) {
            String cycle = visit(dependency, outgoing, visiting, visited, path);
            if (cycle != null) {
                return cycle;
            }
        }
        path.removeLast();
        visiting.remove(nodeId);
        visited.add(nodeId);
        return null;
    }
}
