package com.moon.moonagent.service.agent.handler;

import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.service.agent.AgentAnswerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * other 回答链：AgentAnswerService 主回答（流式打字机 message 增量 + message_end 收尾）。
 * 无 HITL 阶段，resume 无认领动作。
 */
@Component
@RequiredArgsConstructor
public class OtherIntentHandler implements IntentHandler {

    private final AgentOrchestratorSupport support;
    private final AgentAnswerService answerService;

    @Override
    public String intentType() {
        return "intent-other";
    }

    @Override
    public Set<String> resumeActions() {
        return Set.of();
    }

    @Override
    public String handle(OrchestrationRequest request) {
        // streamAnswer 内部逐 token 发 message 事件（打字机效果），返回完整文本
        String answer = answerService.streamAnswer(
                request.getConversation(), request.getContent(), request.getEmitter());
        // 完整文本作为本轮最后一条 message（streamAnswer 的增量不走 support.sendEvent，需显式记录）
        request.setLastMessage(answer);
        support.sendEvent(request, "message_end",
                Map.of("messageId", "", "sceneCount", -1L, "content", answer));
        return answer;
    }

    @Override
    public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        // 无 HITL checkpoint 认领（resumeActions 为空，编排器不会分发到本处理器）
        return "";
    }
}
