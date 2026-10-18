package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.dto.response.SubagentRunView;
import com.moon.moonagent.entity.AgentSubagentRun;
import com.moon.moonagent.mapper.AgentSubagentRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SubagentRunQueryService {

    private final AgentSubagentRunMapper mapper;

    public List<SubagentRunView> list(String conversationId, String graphId, int limit) {        int size = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<AgentSubagentRun> query = new LambdaQueryWrapper<AgentSubagentRun>()
                .eq(AgentSubagentRun::getConversationId, conversationId)
                .orderByDesc(AgentSubagentRun::getCreatedAt)
                .last("LIMIT " + size);
        if (graphId != null && !graphId.isBlank()) {
            query.eq(AgentSubagentRun::getGraphId, graphId);
        }
        return mapper.selectList(query).stream().map(SubagentRunQueryService::view).toList();
    }

    public java.util.Map<String, Object> retryPrompt(String runId, String conversationId) {
        AgentSubagentRun run = mapper.selectById(runId);
        if (run == null || !conversationId.equals(run.getConversationId())) {
            throw new IllegalArgumentException("节点不存在或不属于该会话");
        }
        boolean write = run.getTaskJson() != null && run.getTaskJson().contains("\"allowWrite\":true");
        String tool = write ? "run_write_task_graph" : "run_task_graph";
        String taskJson = run.getTaskJson();
        if (taskJson == null || taskJson.isBlank()) {
            taskJson = "{\"id\":\"" + run.getNodeId() + "\",\"role\":\""
                    + run.getRole() + "\",\"instruction\":\""
                    + String.valueOf(run.getInstruction()).replace("\"", "\\\"")
                    + "\",\"maxTurns\":4}";
        }
        String prompt = "请只调用 " + tool + " 重试这个失败节点。保持节点 id、role、tools 不变；"
                + "完成后汇报实际结果，不要伪造成功。\n"
                + "graphId: " + run.getGraphId() + "\n"
                + "node: " + taskJson;
        return java.util.Map.of(
                "conversationId", run.getConversationId(),
                "turnId", run.getTurnId() == null ? "" : run.getTurnId(),
                "prompt", prompt,
                "tool", tool);
    }

    private static SubagentRunView view(AgentSubagentRun run) {
        return new SubagentRunView(run.getId(), run.getGraphId(), run.getConversationId(),
                run.getTurnId(), run.getToolCallId(), run.getNodeId(), run.getRole(),
                run.getStatus(), run.getInstruction(), run.getOutput(), run.getError(),
                run.getTokens(), run.getDurationMs(), run.getCreatedAt());
    }
}
