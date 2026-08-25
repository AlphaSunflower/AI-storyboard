package com.moon.moonagent.ai.agent.specialist.impl;

import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentCheckpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 委托型专家 —— 将现有 IntentHandler 适配为 SpecialistAgent 接口。
 *
 * <p>ponytail: 不重写 handler 逻辑，只加一层适配。有移交能力但当前 always done。
 * 升级路径：handler 返回值改为 SpecialistResult（含移交信号）。
 */
public class HandlerDelegatingSpecialist implements SpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(HandlerDelegatingSpecialist.class);

    private final IntentHandler handler;

    public HandlerDelegatingSpecialist(IntentHandler handler) {
        this.handler = handler;
    }

    @Override
    public String intentType() {
        return handler.intentType();
    }

    @Override
    public SpecialistResult execute(SpecialistContext context) {
        try {
            OrchestrationRequest request = new OrchestrationRequest(
                    context.conversation(),
                    context.userMessage(),
                    context.picUrl(),
                    context.emitter()
            );
            request.setIntentType(handler.intentType());

            String lastMessage;
            if (context.action() != null && !context.action().isBlank()) {
                // resume 路径
                request.setAction(context.action());
                request.setCustomText(context.customText() != null ? context.customText() : "");
                request.setParams(context.params() != null ? context.params() : java.util.Map.of());
                AgentCheckpoint cp = new AgentCheckpoint();
                cp.setAction(context.cpAction());
                cp.setPlan(context.cpPlan());
                lastMessage = handler.resume(request, cp);
            } else {
                lastMessage = handler.handle(request);
            }

            return SpecialistResult.done(lastMessage != null ? lastMessage : "");
        } catch (Exception e) {
            log.error("HandlerDelegatingSpecialist({}) 执行失败: {}", handler.intentType(), e.getMessage(), e);
            return SpecialistResult.done("处理出了点问题：" + e.getMessage());
        }
    }
}
