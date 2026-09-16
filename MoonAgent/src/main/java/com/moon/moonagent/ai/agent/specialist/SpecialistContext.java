package com.moon.moonagent.ai.agent.specialist;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
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
        /** resume 时前端携带的勾选 assetIds（分镜/资产 id 列表） */
        java.util.List<String> assetIds,
        /** checkpoint 真实 action（区别于提交 action，如 user 点的选项 id） */
        String cpAction,
        /** checkpoint plan JSON（方案载荷；缺失时 resume 读字段得空串） */
        String cpPlan,
        /** 本轮轨迹累积器（可空；null 表示无 trace） */
        AgentTraceAccumulator trace,
        /** 画布编辑模式上下文（可空；非空时技能只能操作指定画布） */
        CanvasSessionContext canvasContext
) {
    /** 兼容旧 12 参数构造器：trace 置 null（无轨迹）。 */
    public SpecialistContext(AgentConversation conversation, String userMessage,
                             java.util.List<String> picUrls, SseEmitter emitter,
                             SpecialistResult previousResult, AgentSkill skill,
                             String action, String customText, Map<String, String> params,
                             java.util.List<String> assetIds, String cpAction, String cpPlan) {
        this(conversation, userMessage, picUrls, emitter, previousResult, skill,
                action, customText, params, assetIds, cpAction, cpPlan, null, null);
    }

    /** 兼容旧 13 参数构造器：canvasContext 置 null。 */
    public SpecialistContext(AgentConversation conversation, String userMessage,
                             java.util.List<String> picUrls, SseEmitter emitter,
                             SpecialistResult previousResult, AgentSkill skill,
                             String action, String customText, Map<String, String> params,
                             java.util.List<String> assetIds, String cpAction, String cpPlan,
                             AgentTraceAccumulator trace) {
        this(conversation, userMessage, picUrls, emitter, previousResult, skill,
                action, customText, params, assetIds, cpAction, cpPlan, trace, null);
    }

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
                request.getAssetIds(),
                "",
                "",
                request.getTrace(),
                request.getCanvasContext()
        );
    }
}
