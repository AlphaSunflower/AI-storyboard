package com.moon.moonagent.ai.agent;

import java.util.List;
import java.util.Map;

/**
 * 一轮编排（run 或 resume turn）的完整结果，供调用方落库 assistant 消息、
 * 推送 trace / reasoning / suggestedActions、标记 HITL 暂停。
 *
 * @param content          本轮最后一条 message 内容（供落库；无消息为空串）
 * @param metadata         落库用结构化元数据（trace/reasoning/suggestedActions/hitl 等；null 时置空 Map）
 * @param agentTrace       本轮轨迹事件列表
 * @param reasoning        推理摘要快照（可空）
 * @param suggestedActions 推荐动作按钮列表
 * @param hitlPaused       本轮是否 HITL 暂停（true=命中 runHITLStage，已落库 assistant 消息，不得重复插入）
 * @param messageId        已落库/待落库的 assistant 消息 id（HITL 卡片消息 id；非 HITL 时为空）
 */
public record AgentTurnResult(
    String content,
    Map<String, Object> metadata,
    List<AgentTraceEvent> agentTrace,
    AgentReasoningSnapshot reasoning,
    List<SuggestedAction> suggestedActions,
    boolean hitlPaused,
    String messageId
) {
    /** 普通完成（无 HITL 暂停）：调用方落库 assistant 消息。 */
    public static AgentTurnResult done(String content) {
        return new AgentTurnResult(
                content == null ? "" : content,
                Map.of(),
                List.of(),
                null,
                List.of(),
                false,
                "");
    }

    /**
     * HITL 暂停：调用方不再重复插入 assistant 消息（runHITLStage 已落库），
     * 仅更新该条消息 metadata / 标记已提交。
     *
     * @param content   卡片文案
     * @param metadata  HITL 卡 metadata（kind/formContent/actions/formToken/expirationTime 等）
     * @param messageId 已落库的 assistant 消息 id
     */
    public static AgentTurnResult pendingHitl(String content, Map<String, Object> metadata, String messageId) {
        return new AgentTurnResult(
                content == null ? "" : content,
                metadata == null ? Map.of() : metadata,
                List.of(),
                null,
                List.of(),
                true,
                messageId == null ? "" : messageId
        );
    }
}
