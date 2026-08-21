package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentCheckpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * intent-delete 分镜删除链：用户说「删除/清空分镜」→ HITL 二次确认 → 删除。
 *
 * <p>handle 弹确认卡片（human_input），用户点「确认删除」才真正执行；
 * 点「取消」则不删除，保持现状。
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
        // checkpoint action = delete-confirm，由 Orchestrator 特判路由
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
        // HITL 二次确认：弹确认卡片，用户必须点「确认删除」才执行
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "当前项目共有 " + existing + " 个分镜（含已生成的图片/视频素材），确认要全部删除吗？\n此操作不可撤销。",
                "delete-confirm",
                List.of(Map.of("existingCount", existing)),
                "human_input",
                List.of(
                        Map.of("id", "confirm-delete", "title", "确认删除全部分镜"),
                        Map.of("id", "cancel-delete", "title", "取消，保留分镜"))));
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        String action = request.getAction();
        if ("cancel-delete".equals(action)) {
            String msg = "好的，已取消删除，现有分镜保持不变。";
            support.sendMessage(request, msg);
            support.sendEvent(request, "message_end",
                    Map.of("messageId", "", "sceneCount", -1L, "content", msg));
            return msg;
        }
        // confirm-delete：执行删除
        String projectId = request.getConversation().getProjectId();
        long existing = storyboardClient.getProjectScenes(projectId).size();
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
}
