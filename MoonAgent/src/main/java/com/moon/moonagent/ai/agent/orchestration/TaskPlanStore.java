package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentTaskEdge;
import com.moon.moonagent.entity.AgentTaskNode;
import com.moon.moonagent.entity.AgentTaskPlan;
import com.moon.moonagent.mapper.AgentTaskEdgeMapper;
import com.moon.moonagent.mapper.AgentTaskNodeMapper;
import com.moon.moonagent.mapper.AgentTaskPlanMapper;
import com.moon.moonagent.orchestration.TaskEdge;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanState;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskPlanStore {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanStore.class);
    private static final List<String> ACTIVE_STATES = List.of(
            TaskPlanState.DRAFT.name(),
            TaskPlanState.READY.name(),
            TaskPlanState.RUNNING.name(),
            TaskPlanState.WAITING_HUMAN.name(),
            TaskPlanState.WAITING_ASYNC.name(),
            TaskPlanState.REPLANNING.name());

    private final AgentTaskPlanMapper planMapper;
    private final AgentTaskNodeMapper nodeMapper;
    private final AgentTaskEdgeMapper edgeMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public TaskPlan save(TaskPlan plan) {
        TaskPlan persisted = withId(plan);
        AgentTaskPlan row = toRow(persisted);
        planMapper.insert(row);
        mirrorNodesAndEdges(persisted);
        return persisted;
    }

    @Transactional
    public TaskPlan update(TaskPlan plan) {
        TaskPlan persisted = withId(plan);
        planMapper.updateById(toRow(persisted));
        nodeMapper.delete(new LambdaQueryWrapper<AgentTaskNode>().eq(AgentTaskNode::getPlanId, persisted.planId()));
        edgeMapper.delete(new LambdaQueryWrapper<AgentTaskEdge>().eq(AgentTaskEdge::getPlanId, persisted.planId()));
        mirrorNodesAndEdges(persisted);
        return persisted;
    }

    public Optional<TaskPlan> find(String planId) {
        return Optional.ofNullable(planMapper.selectById(planId)).flatMap(this::fromRow);
    }

    public Optional<TaskPlan> findActive(String conversationId) {
        AgentTaskPlan row = planMapper.selectOne(new LambdaQueryWrapper<AgentTaskPlan>()
                .eq(AgentTaskPlan::getConversationId, conversationId)
                .in(AgentTaskPlan::getStatus, ACTIVE_STATES)
                .orderByDesc(AgentTaskPlan::getUpdatedAt)
                .last("LIMIT 1"));
        return Optional.ofNullable(row).flatMap(this::fromRow);
    }

    private void mirrorNodesAndEdges(TaskPlan plan) {
        for (TaskNode node : plan.nodes()) {
            AgentTaskNode row = new AgentTaskNode();
            row.setPlanId(plan.planId());
            row.setNodeId(node.nodeId());
            row.setCapabilityKey(node.capabilityKey());
            row.setTitle(node.title());
            row.setState(node.state().name());
            row.setAttemptCount(node.attemptCount());
            row.setHitlPolicy(node.hitlPolicy().name());
            row.setEvaluationMode(node.evaluationMode().name());
            row.setQualityCriteria(node.qualityCriteria());
            row.setOutputSummary(node.outputSummary());
            row.setAsyncTaskId(node.asyncTaskId());
            row.setError(node.error());
            nodeMapper.insert(row);
        }
        for (TaskEdge edge : plan.edges()) {
            AgentTaskEdge row = new AgentTaskEdge();
            row.setPlanId(plan.planId());
            row.setFromNodeId(edge.fromNodeId());
            row.setToNodeId(edge.toNodeId());
            edgeMapper.insert(row);
        }
    }

    private AgentTaskPlan toRow(TaskPlan plan) {
        AgentTaskPlan row = new AgentTaskPlan();
        row.setId(plan.planId());
        row.setConversationId(plan.conversationId());
        row.setGoal(plan.goal());
        row.setStatus(plan.state().name());
        row.setVersion(plan.version());
        row.setPlanSignature(plan.planSignature());
        row.setMaxNodes(plan.maxNodes());
        row.setMaxAttemptsPerNode(plan.maxAttemptsPerNode());
        row.setMaxReplans(plan.maxReplans());
        row.setReplanCount(plan.replanCount());
        row.setError(plan.error());
        try {
            row.setPlanJson(objectMapper.writeValueAsString(plan));
        } catch (Exception e) {
            throw new IllegalStateException("task plan serialization failed", e);
        }
        return row;
    }

    private Optional<TaskPlan> fromRow(AgentTaskPlan row) {
        if (row == null || row.getPlanJson() == null || row.getPlanJson().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(row.getPlanJson(), TaskPlan.class));
        } catch (Exception e) {
            log.warn("task plan deserialization failed: planId={}, error={}", row.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static TaskPlan withId(TaskPlan plan) {
        if (plan.planId() != null && !plan.planId().isBlank()) return plan;
        return new TaskPlan(
                UUID.randomUUID().toString(), plan.conversationId(), plan.goal(), plan.state(), plan.version(),
                plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans(), plan.replanCount(),
                plan.nodes(), plan.edges(), plan.error(), plan.planSignature());
    }
}
