package com.moon.moonagent.dto.response;

import com.moon.moonagent.orchestration.TaskEdge;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskPlan;

import java.util.List;

public record AgentTaskPlanView(
        String planId,
        String conversationId,
        String goal,
        String state,
        int version,
        int replanCount,
        String error,
        List<Node> nodes,
        List<Edge> edges
) {
    public static AgentTaskPlanView from(TaskPlan plan) {
        return new AgentTaskPlanView(
                plan.planId(),
                plan.conversationId(),
                plan.goal(),
                plan.state().name(),
                plan.version(),
                plan.replanCount(),
                plan.error(),
                plan.nodes().stream().map(Node::from).toList(),
                plan.edges().stream().map(Edge::from).toList());
    }

    public record Node(
            String nodeId,
            String capabilityKey,
            String title,
            String state,
            int attemptCount,
            List<String> dependencies,
            String hitlPolicy,
            String evaluationMode,
            String qualityCriteria,
            String outputSummary,
            String asyncTaskId,
            String error
    ) {
        static Node from(TaskNode node) {
            return new Node(
                    node.nodeId(), node.capabilityKey(), node.title(), node.state().name(),
                    node.attemptCount(), node.dependencies(), node.hitlPolicy().name(),
                    node.evaluationMode().name(), node.qualityCriteria(), node.outputSummary(),
                    node.asyncTaskId(), node.error());
        }
    }

    public record Edge(String fromNodeId, String toNodeId) {
        static Edge from(TaskEdge edge) {
            return new Edge(edge.fromNodeId(), edge.toNodeId());
        }
    }
}
