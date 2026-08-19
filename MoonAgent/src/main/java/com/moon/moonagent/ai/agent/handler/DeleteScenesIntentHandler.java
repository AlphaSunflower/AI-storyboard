package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentCheckpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * intent-delete 分镜删除链：用户说「删除/清空分镜」→ 直接删除当前项目全部分镜，无 HITL 确认。
 *
 * <p>与 aisplit 的多步确认流相反：删除意图规则前置命中即路由至此，
 * 一轮 workflow → message → message_end 完成。sceneCount 传删除后总数 0，
 * 前端（sceneCount !== 会话开始时数量 判据）刷新分镜列表为空。
 */
@Component
@RequiredArgsConstructor
public class DeleteScenesIntentHandler implements IntentHandler {

    private final AgentOrchestratorSupport support;
    private final StoryboardClient storyboardClient;

    @Override
    public String intentType() {
        return "intent-delete";
    }

    @Override
    public Set<String> resumeActions() {
        // 纯删除无 HITL 确认点，不注册 resume action
        return Set.of();
    }

    @Override
    public String handle(OrchestrationRequest request) {
        String projectId = request.getConversation().getProjectId();
        long existing = storyboardClient.getProjectScenes(projectId).size();
        if (existing <= 0) {
            String msg = "当前项目没有分镜，无需删除。";
            support.sendMessage(request, msg);
            support.sendEvent(request, "message_end",
                    Map.of("messageId", "", "sceneCount", 0L, "content", msg));
            return msg;
        }
        support.sendEvent(request, "workflow",
                Map.of("title", "正在删除全部 " + existing + " 个分镜…", "status", "node_started"));
        storyboardClient.deleteProjectScenes(projectId);
        support.sendEvent(request, "workflow", Map.of("title", "", "status", "node_finished"));
        String msg = "✅ 已删除全部 " + existing + " 个分镜，分镜列表已清空。";
        support.sendMessage(request, msg);
        support.sendEvent(request, "message_end",
                Map.of("messageId", "", "sceneCount", 0L, "content", msg));
        return msg;
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 无 HITL 确认点，不会被 resume 调用
        return "";
    }
}
