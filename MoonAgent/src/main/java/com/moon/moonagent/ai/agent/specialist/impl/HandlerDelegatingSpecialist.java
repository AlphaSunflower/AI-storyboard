package com.moon.moonagent.ai.agent.specialist.impl;

import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.AgentInterruptException;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentCheckpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 委托型专家 —— 将现有 IntentHandler 适配为 SpecialistAgent 接口。
 *
 * <p>ponytail: 不重写 handler 逻辑，只加一层适配。有移交能力但当前 always done。
 * 升级路径：handler 返回值改为 SpecialistResult（含移交信号）。
 *
 * <p>轨迹：execution 前后与移交时记录 {@code Moon -> 子智能体 -> Moon} 轨迹
 * （经 {@link AgentTraceAccumulator} 下发 agent_trace，不暴露 prompt）。
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
        AgentTraceAccumulator trace = context.trace();
        String intent = handler.intentType();
        String traceId = traceStart(trace, "specialist_execute", intent, "执行 " + handler.intentType(), context.cpAction());
        try {
            OrchestrationRequest request = new OrchestrationRequest(
                    context.conversation(),
                    context.userMessage(),
                    context.picUrls(),
                    context.emitter()
            );
            request.setIntentType(handler.intentType());
            request.setTrace(trace);

            String lastMessage;
            if (context.action() != null && !context.action().isBlank()) {
                log.info("Agent[{}] 续流(resume): action={} cpAction={} 会话={}", handler.intentType(), context.action(), context.cpAction(), context.conversation().getId());
                // resume 路径
                request.setAction(context.action());
                request.setCustomText(context.customText() != null ? context.customText() : "");
                request.setParams(context.params() != null ? context.params() : java.util.Map.of());
                request.setAssetIds(context.assetIds() != null ? context.assetIds() : java.util.List.of());
                AgentCheckpoint cp = new AgentCheckpoint();
                cp.setAction(context.cpAction());
                cp.setPlan(context.cpPlan());
                lastMessage = handler.resume(request, cp);
            } else {
                log.info("Agent[{}] 处理(handle): 会话={}", handler.intentType(), context.conversation().getId());
                request.setAssetIds(context.assetIds() != null ? context.assetIds() : java.util.List.of());
                lastMessage = handler.handle(request);
            }

            // 4.3 移交通电：handler 在 request 上声明了移交目标 → 转 SpecialistResult.handoff（默认 done 保持现状）
            String handoffTarget = request.getHandoffTarget();
            if (handoffTarget != null && !handoffTarget.isBlank()) {
                log.info("HandlerDelegatingSpecialist({}): handler 声明移交 → {}", handler.intentType(), handoffTarget);
                traceEnd(trace, traceId, "node_completed", "完成，移交至 " + handoffTarget);
                traceAdd(trace, "handoff", intent, "移交", intent, handoffTarget, "route_changed");
                copyTurnMetadata(trace, request);
                return SpecialistResult.handoff(handoffTarget, "环节完成，自动移交",
                        request.getHandoffState() != null ? request.getHandoffState() : java.util.Map.of());
            }
            String result = lastMessage != null ? lastMessage : "";
            traceEnd(trace, traceId, "node_completed", "完成");
            copyTurnMetadata(trace, request);
            return SpecialistResult.done(result);
        } catch (AgentInterruptException e) {
            // 用户主动停止（/interrupt）或中断协作：不是业务错误，不落「处理出了点问题」文案，干净收尾。
            log.info("HandlerDelegatingSpecialist({}) 被停止: {}", handler.intentType(), e.getMessage());
            traceEnd(trace, traceId, "cancelled", "用户停止生成");
            return SpecialistResult.done("");
        } catch (Exception e) {
            log.error("HandlerDelegatingSpecialist({}) 执行失败: {}", handler.intentType(), e.getMessage(), e);
            traceEnd(trace, traceId, "failed", "执行失败");
            return SpecialistResult.done("处理出了点问题：" + e.getMessage());
        }
    }

    /** helper：开始一个轨迹阶段；trace 为空返回 null（调用方自行判空）。 */
    private String traceStart(AgentTraceAccumulator trace, String phase, String specialist,
                              String title, String currentIntent) {
        return trace == null ? null : trace.start(phase, specialist, title, currentIntent);
    }

    private void traceEnd(AgentTraceAccumulator trace, String id, String status, String summary) {
        if (trace == null || id == null) return;
        trace.end(id, status, summary);
    }

    private void traceAdd(AgentTraceAccumulator trace, String phase, String specialist, String title,
                          String currentIntent, String targetIntent, String status) {
        if (trace == null) return;
        trace.add(new com.moon.moonagent.ai.agent.AgentTraceEvent(
                UUID.randomUUID().toString(), phase, "moon",
                specialist == null ? "" : specialist, status == null ? "trace" : status,
                title, "", currentIntent == null ? "" : currentIntent,
                targetIntent == null ? "" : targetIntent,
                OffsetDateTime.now(), null));
    }

    private void copyTurnMetadata(AgentTraceAccumulator trace, OrchestrationRequest request) {
        if (trace == null || request == null) return;
        trace.setTurnMetadata(request.getTurnMetadata());
        trace.setHitlPaused(request.isHitlPaused());
        trace.setHitlMessageId(request.getHitlMessageId());
        trace.setSuggestedActions(request.getSuggestedActions());
        trace.reasoning(request.getReasoning());
    }
}
