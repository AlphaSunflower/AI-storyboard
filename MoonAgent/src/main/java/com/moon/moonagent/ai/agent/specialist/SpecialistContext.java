package com.moon.moonagent.ai.agent.specialist;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 专家 Agent 执行上下文（从 OrchestrationRequest 提炼，供 SpecialistAgent 使用）。
 */
public record SpecialistContext(
        AgentConversation conversation,
        String userMessage,
        java.util.List<String> picUrls,
        SseEmitter emitter,
        /** 上一个专家的移交结果（如有） */
        SpecialistResult previousResult,
        /** SKILL.md 元数据+提示词 */
        AgentSkill skill,
        /** resume 时的 action（空串=非 resume） */
        String action,
        /** resume 时的自定义文本 */
        String customText,
        /** resume 时的参数 */
        Map<String, String> params,
        /** checkpoint 真实 action（区别于提交 action，如 user 点的选项 id） */
        String cpAction,
        /** checkpoint plan JSON（方案载荷；缺失时 resume 读字段得空串） */
        String cpPlan
) {
    public static SpecialistContext from(OrchestrationRequest request, AgentSkill skill) {
        return new SpecialistContext(
                request.getConversation(),
                request.getContent(),
                request.getPicUrls(),
                request.getEmitter(),
                null,
                skill,
                request.getAction(),
                request.getCustomText(),
                request.getParams(),
                "",
                ""
        );
    }
}
