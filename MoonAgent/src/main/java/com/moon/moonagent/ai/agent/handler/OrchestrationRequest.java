package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.SuggestedAction;
import com.moon.moonagent.entity.AgentConversation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 一轮编排的输入上下文：会话 + 消息 + 参考图 + SSE 输出。
 *
 * <p>{@code lastMessage} 为每请求可变状态：记录本轮最后一条 message 事件的内容
 * （流式增量时逐段更新），供 run 返回后调用方落库 assistant 消息。
 * per-request 实例，天然并发安全（替代原 Orchestrator 实例字段，修复多会话并发互踩）。
 */
@Getter
@Setter
@RequiredArgsConstructor
public class OrchestrationRequest {

    private final AgentConversation conversation;
    private final String content;
    private final java.util.List<String> picUrls;
    private final SseEmitter emitter;

    /** resume 时从 checkpoint 恢复的参考图；非空时覆盖构造入参 picUrls */
    private java.util.List<String> picUrlsOverride;

    /** 当前有效参考图（resume 恢复优先）；新逻辑一律用 getPicUrls()。 */
    public java.util.List<String> getPicUrls() {
        return picUrlsOverride != null ? picUrlsOverride : picUrls;
    }

    public void setPicUrls(java.util.List<String> urls) {
        this.picUrlsOverride = urls;
    }

    /** 兼容：返回首张参考图（无图返回 null）。 */
    public String getPicUrl() {
        var urls = getPicUrls();
        return (urls == null || urls.isEmpty()) ? null : urls.getFirst();
    }

    /** 本轮最后一条 message 事件内容（流式增量时逐段更新；空串=尚未发出） */
    private String lastMessage = "";

    /** resume 阶段用户点选的选项 id（run 阶段为空串；由 Orchestrator.resume 设置） */
    private String action = "";

    /** resume 阶段自定义输入文本（action=custom 时携带，其余为空串；由 Orchestrator.resume 设置） */
    private String customText = "";

    /** resume 阶段用户提交的生成参数选择（如 {model, resolution, duration}；空 Map=未提交，由 Orchestrator.resume 设置） */
    private Map<String, String> params = Map.of();

    /** resume 阶段用户勾选的资产 ID 列表（资产选择卡片提交；空=未勾选/未涉及资产，由 Orchestrator.resume 设置） */
    private java.util.List<String> assetIds = java.util.List.of();

    /** 当前意图类型（如 intent-aisplit；由 Orchestrator 分发/续流时设置，供 Skill 按需取提示词） */
    private String intentType = "";

    /**
     * resume 阶段 handler 声明的移交目标 intentType（非空=本环节已完成、请交给下一专家）。
     * per-request 可变状态，修复多会话并发互踩；由 Specialist 读后转 {@code SpecialistResult.handoff}。
     */
    private String handoffTarget = "";

    /** 移交附带的下游状态（如 projectId / sceneNums）；与 {@link #handoffTarget} 一起被下一专家消费 */
    private Map<String, Object> handoffState = Map.of();

    /** 本轮轨迹累积器（不参与状态序列化；由 PlanGraph 在 runTurn/resumeTurn 创建）。 */
    private AgentTraceAccumulator trace;

    /** 本轮 turn 落库用的结构化元数据（trace/reasoning/suggestedActions/hitl 等）。 */
    private Map<String, Object> turnMetadata = Map.of();

    /** 本轮是否 HITL 暂停（true=runHITLStage 已落库 assistant 消息，不得重复插入）。 */
    private boolean hitlPaused = false;

    /** HITL 卡片已落库的 assistant 消息 id（供 markHitlCardSubmitted 更新 metadata）。 */
    private String hitlMessageId = "";

    /** 本轮推理摘要快照。 */
    private AgentReasoningSnapshot reasoning;

    /** 本轮推荐的后续动作按钮（如「审查分镜」）。 */
    private java.util.List<SuggestedAction> suggestedActions = java.util.List.of();

    public AgentTraceAccumulator getTrace() {
        return trace;
    }

    public void setTrace(AgentTraceAccumulator trace) {
        this.trace = trace;
    }

    public Map<String, Object> getTurnMetadata() {
        return turnMetadata == null ? Map.of() : turnMetadata;
    }

    public void setTurnMetadata(Map<String, Object> turnMetadata) {
        this.turnMetadata = turnMetadata == null ? Map.of() : turnMetadata;
    }

    public boolean isHitlPaused() {
        return hitlPaused;
    }

    public void setHitlPaused(boolean hitlPaused) {
        this.hitlPaused = hitlPaused;
    }

    public String getHitlMessageId() {
        return hitlMessageId == null ? "" : hitlMessageId;
    }

    public void setHitlMessageId(String hitlMessageId) {
        this.hitlMessageId = hitlMessageId == null ? "" : hitlMessageId;
    }

    public AgentReasoningSnapshot getReasoning() {
        return reasoning;
    }

    public void setReasoning(AgentReasoningSnapshot reasoning) {
        this.reasoning = reasoning;
    }

    public java.util.List<SuggestedAction> getSuggestedActions() {
        return suggestedActions == null ? java.util.List.of() : suggestedActions;
    }


    /** 显式路由提示（前端「换个话题」等按钮；非空时意图识别绕过高置信度锁定目标链）。 */
    private String routingHint = "";

    /** 画布编辑模式上下文；非空时本轮只能进入画布技能。 */
    private CanvasSessionContext canvasContext;

    public String getRoutingHint() {
        return routingHint == null ? "" : routingHint;
    }

    public void setRoutingHint(String routingHint) {
        this.routingHint = routingHint == null ? "" : routingHint;
    }

    public CanvasSessionContext getCanvasContext() {
        return canvasContext;
    }

    public void setCanvasContext(CanvasSessionContext canvasContext) {
        this.canvasContext = canvasContext;
    }

    public void setSuggestedActions(java.util.List<SuggestedAction> suggestedActions) {
        this.suggestedActions = suggestedActions == null ? java.util.List.of() : suggestedActions;
    }
}
