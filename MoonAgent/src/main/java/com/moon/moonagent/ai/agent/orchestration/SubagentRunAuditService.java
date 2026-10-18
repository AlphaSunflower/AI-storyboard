package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentSubagentRun;
import com.moon.moonagent.mapper.AgentSubagentRunMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 子智能体节点审计。审计失败只告警，绝不影响任务图执行。
 */
@Service
@RequiredArgsConstructor
public class SubagentRunAuditService {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunAuditService.class);
    private static final int MAX_INSTRUCTION_CHARS = 20_000;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final AgentSubagentRunMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String started(String graphId, String conversationId, String turnId,
                          String toolCallId, SubagentTask task) {
        return started(graphId, conversationId, turnId, toolCallId, task, null, null);
    }

    public String started(String graphId, String conversationId, String turnId,
                          String toolCallId, SubagentTask task,
                          SubagentTaskGraph graph, String contextRef) {
        String id = id(graphId, task.id());
        try {
            AgentSubagentRun run = mapper.selectById(id);
            boolean isNew = run == null;
            if (isNew) {
                run = new AgentSubagentRun();
                run.setId(id);
                run.setCreatedAt(OffsetDateTime.now());
            }
            run.setGraphId(graphId);
            run.setConversationId(conversationId);
            run.setTurnId(turnId);
            run.setToolCallId(toolCallId);
            run.setNodeId(task.id());
            run.setRole(task.role());
            run.setStatus("RUNNING");
            run.setInstruction(truncate(task.instruction(), MAX_INSTRUCTION_CHARS));
            run.setTaskJson(truncate(writeJson(task), MAX_OUTPUT_CHARS));
            run.setGraphJson(truncate(writeJson(graph), MAX_OUTPUT_CHARS));
            run.setContextRef(truncate(contextRef, 512));
            run.setUpdatedAt(OffsetDateTime.now());
            if (isNew) {
                mapper.insert(run);
            } else {
                mapper.updateById(run);
            }
        } catch (RuntimeException e) {
            log.warn("[Subagent] 审计写入失败: graph={}, node={}, error={}",
                    graphId, task.id(), e.getMessage());
        }
        return id;
    }

    public void completed(String graphId, String nodeId, SubagentTaskResult result) {
        try {
            AgentSubagentRun run = mapper.selectById(id(graphId, nodeId));
            boolean isNew = run == null;
            if (isNew) {
                run = new AgentSubagentRun();
                run.setId(id(graphId, nodeId));
                run.setGraphId(graphId);
                run.setNodeId(nodeId);
                run.setCreatedAt(OffsetDateTime.now());
            }
            run.setStatus(result.status().name());
            run.setOutput(truncate(result.output(), MAX_OUTPUT_CHARS));
            run.setError(truncate(result.error(), MAX_OUTPUT_CHARS));
            run.setTokens(result.tokens());
            run.setDurationMs(result.durationMs());
            run.setUpdatedAt(OffsetDateTime.now());
            if (isNew) {
                mapper.insert(run);
            } else {
                mapper.updateById(run);
            }
        } catch (RuntimeException e) {
            log.warn("[Subagent] 审计更新失败: graph={}, node={}, error={}",
                    graphId, nodeId, e.getMessage());
        }
    }

    public java.util.Map<String, SubagentTaskResult> completedForGraph(String graphId) {
        java.util.Map<String, SubagentTaskResult> completed = new java.util.LinkedHashMap<>();
        if (graphId == null || graphId.isBlank()) return completed;
        try {
            java.util.List<AgentSubagentRun> runs = mapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentSubagentRun>()
                            .eq(AgentSubagentRun::getGraphId, graphId)
                            .eq(AgentSubagentRun::getStatus, "COMPLETED"));
            for (AgentSubagentRun run : runs) {
                completed.put(run.getNodeId(), new SubagentTaskResult(
                        run.getNodeId(), SubagentTaskStatus.COMPLETED, run.getOutput(),
                        null, run.getDurationMs() == null ? 0 : run.getDurationMs(),
                        run.getTokens() == null ? 0 : run.getTokens()));
            }
        } catch (RuntimeException e) {
            log.warn("[Subagent] 恢复已完成节点失败: graph={}, error={}", graphId, e.getMessage());
        }
        return completed;
    }

    private static String id(String graphId, String nodeId) {
        return graphId + ":" + nodeId;
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[Subagent] 快照序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
